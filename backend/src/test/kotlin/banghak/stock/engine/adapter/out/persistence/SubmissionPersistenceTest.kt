package banghak.stock.engine.adapter.out.persistence

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.Ulid
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.OrderStatus
import banghak.stock.core.domain.trading.OrderSubmission
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.SubmissionRecord
import banghak.stock.core.domain.trading.SubmissionState
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.port.SubmissionStorePort
import banghak.stock.engine.application.trading.OrderJournal
import banghak.stock.support.EngineDatabaseTest
import com.zaxxer.hikari.HikariDataSource
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/** 주문 요청 기록이 SQLite 에서 멱등 키·사용자 범위·트랜잭션 규칙을 지킴. */
class SubmissionPersistenceTest : EngineDatabaseTest() {
    @Autowired private lateinit var store: SubmissionStorePort
    @Autowired private lateinit var journal: OrderJournal
    @Autowired private lateinit var engineWriteDataSource: HikariDataSource

    private val write by lazy { JdbcTemplate(engineWriteDataSource) }
    private val user = TradingFixtures.user
    private val other = UserId.from(Ulid.of(TradingFixtures.now, ByteArray(10) { 9 }))
    private val sentAt = Instant.parse("2026-09-30T01:00:00Z")
    private val key = ClientOrderId("01K6REQ0000000000000000001")

    @BeforeEach
    fun clearTables() {
        write.update("delete from order_submission")
        write.update("delete from broker_order")
        write.update("delete from event_log")
    }

    @Test
    @DisplayName("같은 멱등 키는 한 번만 기록되고, 다시 읽으면 같은 요청임")
    fun beginsOncePerKey() {
        val record = sending()

        assertThat(store.tryBegin(record)).isTrue()
        assertThat(store.tryBegin(record)).isFalse()
        assertThat(store.find(user, key)).isEqualTo(record)
        assertThat(store.find(other, key)).isNull()
    }

    @Test
    @DisplayName("정정 요청은 원주문 번호를 함께 남김")
    fun keepsReplacedOrder() {
        val amendKey = ClientOrderId("01K6AMD0000000000000000001")
        val amend = sending(amendKey).copy(replacesBrokerOrderId = "B-1")

        store.tryBegin(amend)

        assertThat(store.find(user, amendKey)?.replacesBrokerOrderId).isEqualTo("B-1")
    }

    @Test
    @DisplayName("결과가 정해지지 않은 요청만 확인 대상이고, 접수로 연결된 주문 번호를 알려 줌")
    fun tracksUnresolvedAndClaimed() {
        val second = ClientOrderId("01K6REQ0000000000000000002")
        store.tryBegin(sending())
        store.tryBegin(sending(second))

        store.markAccepted(user, second, "B-9", sentAt)
        store.markUnknown(user, key, "타임아웃", sentAt)

        assertThat(store.findUnresolved(user).map { it.clientOrderId }).containsExactly(key)
        assertThat(store.findUnresolved(other)).isEmpty()
        assertThat(store.claimedBrokerOrderIds(user)).containsExactly("B-9")
    }

    @Test
    @DisplayName("접수 기록이 중간에 실패하면 한 트랜잭션이 통째로 되돌려져 요청은 보내는 중으로 남음")
    fun acceptedRecordingIsAtomic() {
        val record = sending()
        journal.beginSubmission(record)
        write.update(
            "insert into broker_order (broker_order_id, user_id, market, code, side, kind, time_in_force, status, filled_quantity, origin, trigger_type, ordered_at, updated_at, fetched_at) " +
                "values ('B-1', ?, 'KR', '005930', 'BUY', 'LIMIT', 'DAY', 'PENDING', '0', 'MANUAL', 'EXTERNAL', $NOW, $NOW, $NOW)",
            other.value,
        )

        assertThatThrownBy { journal.recordAccepted(record, "B-1") }
            .isInstanceOf(InvalidValueException::class.java)

        assertThat(store.find(user, key)?.state).isEqualTo(SubmissionState.SENDING)
        assertThat(eventTypes()).containsExactly("OrderIntended")
    }

    @Test
    @DisplayName("반영할 때마다 행 버전이 오르고, 두 스레드가 같은 주문을 동시에 반영해도 최종 상태는 뒤로 가지 않음")
    fun concurrentProgressNeverRegresses() {
        val partial =
            TradingFixtures.brokerRecord(
                brokerOrderId = "B-7",
                status = OrderStatus.PARTIALLY_FILLED,
                filled = Quantity.of(4),
            )
        val filled = partial.copy(status = OrderStatus.FILLED, filledQuantity = Quantity.of(10))
        journal.recordBrokerProgress(TradingFixtures.device, user, partial)
        val versionBefore = version("B-7")

        val pool = Executors.newFixedThreadPool(2)
        repeat(REPEATS) {
            listOf(partial, filled)
                .map { record ->
                    pool.submit {
                        journal.recordBrokerProgress(TradingFixtures.device, user, record)
                    }
                }
                .forEach { it.get(5, TimeUnit.SECONDS) }
        }
        pool.shutdown()

        assertThat(
                write.queryForObject(
                    "select status from broker_order where broker_order_id = 'B-7'",
                    String::class.java,
                )
            )
            .isEqualTo("FILLED")
        assertThat(version("B-7")).isGreaterThan(versionBefore)
    }

    private fun version(brokerOrderId: String): Long =
        write.queryForObject(
            "select version from broker_order where broker_order_id = ?",
            Long::class.java,
            brokerOrderId,
        )!!

    private fun sending(clientOrderId: ClientOrderId = key) =
        SubmissionRecord(
            OrderSubmission(
                TradingFixtures.limitBuy(),
                clientOrderId,
                isHighValueConfirmed = false,
            ),
            TradingFixtures.device,
            SubmissionState.SENDING,
            null,
            null,
            sentAt,
        )

    private fun eventTypes(): List<String?> =
        write.queryForList("select type from event_log order by seq", String::class.java)

    companion object {
        private const val REPEATS = 20
        private const val NOW = "'2026-09-30T00:00:00.000Z'"
    }
}
