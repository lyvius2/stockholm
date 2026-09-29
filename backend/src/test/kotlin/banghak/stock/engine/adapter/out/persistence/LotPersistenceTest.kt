package banghak.stock.engine.adapter.out.persistence

import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.portfolio.BuyOrigin
import banghak.stock.core.domain.portfolio.FifoLotMatcher
import banghak.stock.core.domain.portfolio.FillState
import banghak.stock.core.domain.portfolio.Lot
import banghak.stock.core.domain.portfolio.LotId
import banghak.stock.core.domain.portfolio.Sale
import banghak.stock.core.domain.trading.FillIncrement
import banghak.stock.core.domain.trading.OrderOrigin
import banghak.stock.core.domain.trading.OrderSide
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.port.FillQueuePort
import banghak.stock.core.port.LotLedgerPort
import banghak.stock.core.port.LotStorePort
import banghak.stock.support.EngineDatabaseTest
import com.zaxxer.hikari.HikariDataSource
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/** lot·소진 기록·체결 대기열·원장이 SQLite 에 설계대로 남음. */
class LotPersistenceTest : EngineDatabaseTest() {
    @Autowired private lateinit var lots: LotStorePort
    @Autowired private lateinit var fills: FillQueuePort
    @Autowired private lateinit var ledger: LotLedgerPort
    @Autowired private lateinit var engineWriteDataSource: HikariDataSource

    private val write by lazy { JdbcTemplate(engineWriteDataSource) }
    private val user = TradingFixtures.user
    private val at = Instant.parse("2026-09-30T01:00:00Z")

    @BeforeEach
    fun clearTables() {
        write.update("delete from lot_disposal")
        write.update("delete from lot")
        write.update("delete from fill_queue")
        write.update("delete from lot_ledger")
        write.update("delete from app_user")
        write.update(
            "insert into app_user (user_id, role, display_name, password_hash, status, toss_key_decision, created_at, updated_at) values (?, 'ADMIN', 'w', 'h', 'ACTIVE', 'NONE', '2026-09-30T00:00:00.000Z', '2026-09-30T00:00:00.000Z')",
            user.value,
        )
    }

    @Test
    @DisplayName("lot 을 남기고 다시 읽으며, 기초 lot 표시와 매수 주문 번호가 함께 남음")
    fun savesAndReadsLots() {
        val lot = lot("01K6ABC0000000000000000001", qty = 10, isOpening = true)

        lots.saveOpened(lot, brokerOrderId = null)

        assertThat(lots.openLots(user, Market.KR)).containsExactly(lot)
        assertThat(write.queryForObject("select opening from lot", Int::class.java)).isEqualTo(1)
    }

    @Test
    @DisplayName("매도 소진은 lot 잔여를 줄이고 다 쓴 lot 은 청산하며 소진 기록을 lot 소유자로 남김")
    fun savesDisposals() {
        val first = lot("01K6ABC0000000000000000001", qty = 10)
        val second = lot("01K6ABC0000000000000000002", qty = 10).copy(boughtAt = at.plusSeconds(1))
        lots.saveOpened(first, "B-1")
        lots.saveOpened(second, "B-2")
        val sale =
            Sale(
                user,
                "S-1",
                TradingFixtures.samsung,
                Quantity.of(15),
                TradingFixtures.krw("75000"),
                TradingFixtures.krw("0"),
                TradingFixtures.krw("0"),
                null,
                at.plusSeconds(60),
            )
        val result = FifoLotMatcher.dispose(listOf(first, second), sale)

        lots.saveReduced(result.lotsAfter.filter { it !in listOf(first, second) }, sale.executedAt)
        lots.saveDisposals(user, TradingFixtures.samsung, result.disposals, sale.executedAt)

        assertThat(lots.openLots(user, Market.KR).map { it.remainingQuantity })
            .containsExactly(Quantity.of(5))
        assertThat(
                write.queryForObject(
                    "select count(*) from lot where closed_at is not null",
                    Int::class.java,
                )
            )
            .isEqualTo(1)
        assertThat(
                write.queryForList(
                    "select quantity from lot_disposal order by quantity",
                    String::class.java,
                )
            )
            .containsExactly("10", "5")
    }

    @Test
    @DisplayName("체결 대기열은 넣은 증분을 그대로 읽고, 반영하면 확인 대상에서 빠짐")
    fun fillQueueRoundTrip() {
        val fill =
            FillIncrement(
                user,
                "B-1",
                TradingFixtures.samsung,
                OrderSide.BUY,
                Quantity.of(4),
                TradingFixtures.krw("282000"),
                TradingFixtures.krw("42"),
                TradingFixtures.krw("0"),
                OrderOrigin.AUTO_BUY,
                at,
            )

        fills.enqueue(fill, at)
        val queued = fills.unprocessed(user).single()
        fills.mark(user, queued.id, FillState.DONE, null, at)

        assertThat(queued.fill).isEqualTo(fill)
        assertThat(fills.unprocessed(user)).isEmpty()
    }

    @Test
    @DisplayName("원장은 한 번만 시작되고 두 번째 시작은 첫 시각을 바꾸지 않음")
    fun ledgerStartsOnce() {
        ledger.start(user, at)
        ledger.start(user, at.plusSeconds(10))

        assertThat(ledger.startedAt(user)).isEqualTo(at)
    }

    private fun lot(id: String, qty: Long, isOpening: Boolean = false) =
        Lot(
            id = LotId(id),
            userId = user,
            symbol = TradingFixtures.samsung,
            boughtQuantity = Quantity.of(qty),
            remainingQuantity = Quantity.of(qty),
            unitCost = TradingFixtures.krw("70000"),
            fxAtBuy = null,
            boughtAt = at,
            origin = BuyOrigin.MANUAL,
            isOpening = isOpening,
        )
}
