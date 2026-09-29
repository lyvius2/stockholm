package banghak.stock.engine.adapter.out.persistence

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.Ulid
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.trading.OrderOrigin
import banghak.stock.core.domain.trading.OrderStatus
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.port.BrokerOrderStorePort
import banghak.stock.core.port.LotStorePort
import banghak.stock.support.EngineDatabaseTest
import com.zaxxer.hikari.HikariDataSource
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/** 주문 기록·lot 저장소가 SQLite 에서 사용자 범위와 기록 규칙을 지킴. */
class TradingStoresPersistenceTest : EngineDatabaseTest() {
    @Autowired private lateinit var orders: BrokerOrderStorePort
    @Autowired private lateinit var lots: LotStorePort
    @Autowired private lateinit var engineWriteDataSource: HikariDataSource

    private val write by lazy { JdbcTemplate(engineWriteDataSource) }
    private val user = TradingFixtures.user
    private val other = UserId.from(Ulid.of(TradingFixtures.now, ByteArray(10) { 9 }))
    private val placedAt = Instant.parse("2026-09-30T01:00:00Z")

    @BeforeEach
    fun clearTables() {
        write.update("delete from broker_order")
        write.update("delete from lot")
        write.update("delete from app_user")
    }

    @Test
    @DisplayName("접수 기록은 주문 의도·트리거·고액 확인을 남기고, 다시 읽으면 의도 시각만 주문 시각으로 바뀜")
    fun recordsAndReadsBack() {
        val order = order("B-1")

        orders.recordAccepted(order, isHighValueConfirmed = true)

        assertThat(orders.findPlacedSince(user, Market.KR, placedAt))
            .containsExactly(order.copy(intent = order.intent.copy(intendedAt = placedAt)))
        val row = write.queryForMap("select trigger_type, high_value_confirmed from broker_order")
        assertThat(row["trigger_type"]).isEqualTo("MANUAL")
        assertThat(row["high_value_confirmed"]).isEqualTo(1)
    }

    @Test
    @DisplayName("실시간 이벤트가 먼저 만든 행이 있으면 상태·체결은 그대로 두고 의도만 채움")
    fun keepsBrokerStateWhenRowExists() {
        write.update(
            "insert into broker_order (broker_order_id, user_id, market, code, side, kind, time_in_force, limit_price_amount, limit_price_currency, quantity, status, filled_quantity, origin, trigger_type, ordered_at, updated_at, fetched_at) " +
                "values ('B-1', ?, 'KR', '005930', 'BUY', 'LIMIT', 'DAY', '70000', 'KRW', '10', 'PARTIALLY_FILLED', '4', 'MANUAL', 'EXTERNAL', '2026-09-30T01:00:00.000Z', '2026-09-30T01:00:01.000Z', '2026-09-30T01:00:01.000Z')",
            user.value,
        )

        orders.recordAccepted(order("B-1"), isHighValueConfirmed = false)

        val saved = orders.findPlacedSince(user, Market.KR, placedAt).single()
        assertThat(saved.status).isEqualTo(OrderStatus.PARTIALLY_FILLED)
        assertThat(saved.filledQuantity.value).isEqualByComparingTo("4")
        assertThat(saved.intent.origin).isEqualTo(OrderOrigin.MANUAL)
    }

    @Test
    @DisplayName("다른 사용자의 행은 덮어쓰지 않고, 조회는 사용자·시장·시각과 외부 주문으로 거름")
    fun scopesByUserAndExcludesExternal() {
        write.update(
            "insert into broker_order (broker_order_id, user_id, market, code, side, kind, time_in_force, status, filled_quantity, origin, trigger_type, ordered_at, updated_at, fetched_at) " +
                "values ('APP-1', ?, 'KR', '005930', 'SELL', 'LIMIT', 'DAY', 'PENDING', '0', 'MANUAL', 'EXTERNAL', '2026-09-30T02:00:00.000Z', '2026-09-30T02:00:00.000Z', '2026-09-30T02:00:00.000Z')",
            user.value,
        )
        write.update(
            "insert into broker_order (broker_order_id, user_id, market, code, side, kind, time_in_force, status, filled_quantity, origin, trigger_type, ordered_at, updated_at, fetched_at) " +
                "values ('OTHER-1', ?, 'KR', '005930', 'BUY', 'LIMIT', 'DAY', 'PENDING', '0', 'MANUAL', 'EXTERNAL', '2026-09-30T02:00:00.000Z', '2026-09-30T02:00:00.000Z', '2026-09-30T02:00:00.000Z')",
            other.value,
        )
        orders.recordAccepted(order("B-EARLY").copy(updatedAt = placedAt.minusSeconds(1)), false)
        orders.recordAccepted(order("B-LATE").copy(updatedAt = placedAt), false)

        assertThatThrownBy { orders.recordAccepted(order("OTHER-1"), false) }
            .isInstanceOf(InvalidValueException::class.java)
        assertThat(orders.findPlacedSince(user, Market.KR, placedAt).map { it.brokerOrderId })
            .containsExactly("B-LATE")
    }

    @Test
    @DisplayName("lot 은 이 사용자·시장의 미청산 것만 읽음")
    fun readsOnlyOpenLotsOfUser() {
        listOf(user, other).forEach {
            write.update(
                "insert into app_user (user_id, role, display_name, password_hash, status, toss_key_decision, created_at, updated_at) values (?, 'ADMIN', ?, 'h', 'ACTIVE', 'NONE', $NOW, $NOW)",
                it.value,
                it.value,
            )
        }
        insertLot(LOT_OPEN, user, closedAt = null)
        insertLot(LOT_CLOSED, user, closedAt = NOW)
        insertLot(LOT_OTHER, other, closedAt = null)

        assertThat(lots.openLots(user, Market.KR).map { it.id.value }).containsExactly(LOT_OPEN)
        assertThat(lots.openLots(user, Market.US)).isEmpty()
    }

    private fun insertLot(lotId: String, owner: UserId, closedAt: String?) {
        write.update(
            "insert into lot (lot_id, user_id, market, code, bought_quantity, remaining_quantity, unit_cost_amount, unit_cost_currency, bought_at, origin, closed_at, created_at, updated_at) " +
                "values (?, ?, 'KR', '005930', '10', '10', '70000', 'KRW', $NOW, 'MANUAL', ${closedAt ?: "null"}, $NOW, $NOW)",
            lotId,
            owner.value,
        )
    }

    private fun order(brokerOrderId: String) =
        TradingFixtures.brokerOrder(brokerOrderId = brokerOrderId).copy(updatedAt = placedAt)

    companion object {
        private const val NOW = "'2026-09-30T00:00:00.000Z'"
        private const val LOT_OPEN = "01K6A00000000000000000000A"
        private const val LOT_CLOSED = "01K6A00000000000000000000B"
        private const val LOT_OTHER = "01K6A00000000000000000000C"
    }
}
