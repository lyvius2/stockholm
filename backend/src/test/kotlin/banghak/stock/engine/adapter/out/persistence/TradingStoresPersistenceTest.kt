package banghak.stock.engine.adapter.out.persistence

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.Ulid
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.trading.FillSummary
import banghak.stock.core.domain.trading.OrderOrigin
import banghak.stock.core.domain.trading.OrderProgress
import banghak.stock.core.domain.trading.OrderStatus
import banghak.stock.core.domain.trading.Quantity
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
    @DisplayName("화면 목록은 열린 주문과 기준 시각 이후 닫힌 주문을 사용자 범위로 읽고 외부 주문도 포함함")
    fun listsOpenAndClosedForScreen() {
        val insert =
            "insert into broker_order (broker_order_id, user_id, market, code, side, kind, time_in_force, status, quantity, filled_quantity, origin, trigger_type, ordered_at, updated_at, fetched_at, filled_at, canceled_at) " +
                "values (?, ?, 'KR', '005930', 'BUY', ?, 'DAY', ?, '10', ?, 'MANUAL', 'EXTERNAL', ?, ?, ?, ?, ?)"
        val row =
            {
                id: String,
                owner: UserId,
                kind: String,
                status: String,
                filled: String,
                orderedAt: String,
                updatedAt: String,
                filledAt: String?,
                canceledAt: String? ->
                write.update(
                    insert,
                    id,
                    owner.value,
                    kind,
                    status,
                    filled,
                    orderedAt,
                    updatedAt,
                    NOW_TEXT,
                    filledAt,
                    canceledAt,
                )
            }
        row(
            "OPEN-1",
            user,
            "LIMIT",
            "PARTIALLY_FILLED",
            "4",
            "2026-09-30T02:00:00.000Z",
            "2026-09-30T02:00:00.000Z",
            null,
            null,
        )
        row(
            "OPEN-MARKET",
            user,
            "MARKET",
            "PENDING",
            "0",
            "2026-09-30T02:30:00.000Z",
            "2026-09-30T02:30:00.000Z",
            null,
            null,
        )
        row(
            "CLOSED-TODAY",
            user,
            "LIMIT",
            "FILLED",
            "10",
            "2026-09-30T01:00:00.000Z",
            "2026-09-30T03:00:00.000Z",
            "2026-09-30T03:00:00.000Z",
            null,
        )
        // 어제 취소된 주문을 오늘 재동기로 수집함(updated_at 은 오늘) — 오늘 목록에 들어오면 안 됨
        row(
            "CLOSED-OLD",
            user,
            "LIMIT",
            "CANCELLED",
            "0",
            "2026-09-29T01:00:00.000Z",
            "2026-09-30T04:00:00.000Z",
            null,
            "2026-09-29T03:00:00.000Z",
        )
        row(
            "OTHER-OPEN",
            other,
            "LIMIT",
            "PENDING",
            "0",
            "2026-09-30T02:00:00.000Z",
            "2026-09-30T02:00:00.000Z",
            null,
            null,
        )

        val open = orders.findOpen(user)
        val closed = orders.findClosedSince(user, Instant.parse("2026-09-30T00:00:00Z"))

        assertThat(open.map { it.brokerOrderId }).containsExactly("OPEN-MARKET", "OPEN-1")
        val limit = open.single { it.brokerOrderId == "OPEN-1" }
        assertThat(limit.remaining).isEqualTo(Quantity.of(6))
        assertThat(limit.isPlacedByStockholm).isFalse()
        assertThat(limit.canAmend).isTrue()
        assertThat(limit.canCancel).isTrue()
        val market = open.single { it.brokerOrderId == "OPEN-MARKET" }
        assertThat(market.canAmend).isFalse()
        assertThat(market.canCancel).isTrue()
        assertThat(closed.map { it.brokerOrderId }).containsExactly("CLOSED-TODAY")
        assertThat(closed.single().closedAt).isEqualTo(Instant.parse("2026-09-30T03:00:00Z"))
        assertThat(closed.single().canAmend).isFalse()
        assertThat(closed.single().canCancel).isFalse()
    }

    @Test
    @DisplayName("증권사 기록은 처음 보는 주문을 외부 주문으로 넣고, 우리 주문은 의도·출처를 두고 상태·체결·금액만 바꿈")
    fun appliesBrokerRecords() {
        orders.recordAccepted(order("B-1"), isHighValueConfirmed = false)
        val filled =
            TradingFixtures.brokerRecord(
                    brokerOrderId = "B-1",
                    status = OrderStatus.FILLED,
                    filled = Quantity.of(10),
                )
                .copy(filledAmount = TradingFixtures.krw("700000"))

        orders.applyBrokerRecord(user, filled, placedAt)
        orders.applyBrokerRecord(
            user,
            TradingFixtures.brokerRecord(brokerOrderId = "APP-1"),
            placedAt,
        )

        assertThat(orders.findRecorded(user, "B-1")?.progress)
            .isEqualTo(OrderProgress(OrderStatus.FILLED, Quantity.of(10)))
        assertThat(orders.findRecorded(other, "B-1")).isNull()
        assertThat(orders.openBrokerOrderIds(user)).containsExactly("APP-1")
        val rows =
            write.queryForList(
                "select broker_order_id, trigger_type, client_order_id, filled_amount_amount from broker_order order by broker_order_id"
            )
        assertThat(rows.map { it["trigger_type"] }).containsExactly("EXTERNAL", "MANUAL")
        assertThat(rows.last()["client_order_id"]).isNotNull()
        assertThat(rows.last()["filled_amount_amount"]).isEqualTo("700000")
        assertThatThrownBy { orders.applyBrokerRecord(other, filled, placedAt) }
            .isInstanceOf(InvalidValueException::class.java)
    }

    @Test
    @DisplayName("체결 대기열에 넣은 요약을 남기고 다시 읽으며, 남긴 적 없으면 0 으로 읽음")
    fun queuedFillSummaryRoundTrip() {
        orders.applyBrokerRecord(
            user,
            TradingFixtures.brokerRecord(brokerOrderId = "APP-1"),
            placedAt,
        )
        assertThat(orders.findRecorded(user, "APP-1")?.queuedFill)
            .isEqualTo(FillSummary.zero(Currency.KRW))
        val queued =
            FillSummary(
                Quantity.of(4),
                TradingFixtures.krw("280000"),
                TradingFixtures.krw("42"),
                TradingFixtures.krw("0"),
            )

        orders.markFillQueued(user, "APP-1", queued)

        assertThat(orders.findRecorded(user, "APP-1")?.queuedFill).isEqualTo(queued)
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
        private const val NOW_TEXT = "2026-09-30T00:00:00.000Z"
        private const val LOT_OPEN = "01K6A00000000000000000000A"
        private const val LOT_CLOSED = "01K6A00000000000000000000B"
        private const val LOT_OTHER = "01K6A00000000000000000000C"
    }
}
