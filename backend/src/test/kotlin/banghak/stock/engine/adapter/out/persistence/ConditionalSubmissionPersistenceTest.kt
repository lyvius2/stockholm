package banghak.stock.engine.adapter.out.persistence

import banghak.stock.core.domain.identity.Ulid
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.ConditionalFixtures
import banghak.stock.core.domain.trading.ConditionalOrderIntent
import banghak.stock.core.domain.trading.ConditionalOrderSubmission
import banghak.stock.core.domain.trading.ConditionalSubmissionRecord
import banghak.stock.core.domain.trading.SubmissionState
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.port.ConditionalSubmissionStorePort
import banghak.stock.support.EngineDatabaseTest
import com.zaxxer.hikari.HikariDataSource
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/** 조건주문 요청 기록이 SQLite 에서 멱등 키·사용자 범위를 지키고 조건을 그대로 되살림. */
class ConditionalSubmissionPersistenceTest : EngineDatabaseTest() {
    @Autowired private lateinit var store: ConditionalSubmissionStorePort
    @Autowired private lateinit var engineWriteDataSource: HikariDataSource

    private val write by lazy { JdbcTemplate(engineWriteDataSource) }
    private val user = TradingFixtures.user
    private val other = UserId.from(Ulid.of(TradingFixtures.now, ByteArray(10) { 9 }))
    private val sentAt = Instant.parse("2026-09-30T01:00:00Z")
    private val key = ClientOrderId("01K6CND0000000000000000001")

    @BeforeEach
    fun clearTable() {
        write.update("delete from conditional_submission")
    }

    @Test
    @DisplayName("같은 멱등 키는 한 번만 기록되고, OCO 두 조건과 만료일을 그대로 되살림")
    fun beginsOncePerKeyAndRestoresLegs() {
        val record = sending(ConditionalFixtures.oco())

        assertThat(store.tryBegin(record)).isTrue()
        assertThat(store.tryBegin(record)).isFalse()
        assertThat(store.find(user, key)).isEqualTo(record)
        assertThat(store.find(other, key)).isNull()
    }

    @Test
    @DisplayName("SINGLE 은 둘째 조건 없이, 수정 요청은 원래 조건주문 번호와 함께 남음")
    fun keepsSingleAndReplacedId() {
        val amend = sending(ConditionalFixtures.single()).copy(replacesConditionalOrderId = "CO-1")

        store.tryBegin(amend)

        assertThat(store.find(user, key)).isEqualTo(amend)
    }

    @Test
    @DisplayName("결과가 정해지지 않은 요청만 확인 대상이고, 등록으로 연결된 번호를 알려 줌")
    fun tracksUnresolvedAndClaimed() {
        val second = ClientOrderId("01K6CND0000000000000000002")
        store.tryBegin(sending(ConditionalFixtures.oco()))
        store.tryBegin(sending(ConditionalFixtures.single(), second))

        store.markAccepted(user, key, "CO-7", sentAt)
        store.markUnknown(user, second, "응답 없음", sentAt)

        assertThat(store.findUnresolved(user).map { it.clientOrderId }).containsExactly(second)
        assertThat(store.findUnresolved(other)).isEmpty()
        assertThat(store.claimedConditionalOrderIds(user)).containsExactly("CO-7")

        store.markResolved(user, second, SubmissionState.NEEDS_REVIEW, "확인 못 함", sentAt)
        assertThat(store.findUnresolved(user)).isEmpty()
    }

    private fun sending(intent: ConditionalOrderIntent, request: ClientOrderId = key) =
        ConditionalSubmissionRecord(
            ConditionalOrderSubmission(intent, request, isHighValueConfirmed = false),
            SubmissionState.SENDING,
            conditionalOrderId = null,
            reason = null,
            sentAt = sentAt,
            replacesConditionalOrderId = null,
        )
}
