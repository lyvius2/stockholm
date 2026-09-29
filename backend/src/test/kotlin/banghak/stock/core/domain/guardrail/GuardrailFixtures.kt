package banghak.stock.core.domain.guardrail

import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.MarketSession
import banghak.stock.core.domain.market.SessionWindow
import banghak.stock.core.domain.market.TradingDay
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.ExchangeRate
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.portfolio.DepositBalance
import banghak.stock.core.domain.portfolio.PortfolioSnapshot
import banghak.stock.core.domain.trading.BrokerOrder
import banghak.stock.core.domain.trading.BrokerOrderRecord
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.OrderIntent
import banghak.stock.core.domain.trading.Quote
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

object GuardrailFixtures {
    private val seoul: ZoneId = ZoneId.of("Asia/Seoul")
    val date: LocalDate = LocalDate.of(2026, 9, 30)
    val key: ClientOrderId = ClientOrderId("TEST-KEY-1")

    fun kst(time: String, day: LocalDate = date): Instant =
        day.atTime(LocalTime.parse(time)).atZone(seoul).toInstant()

    /**
     * 국내 거래일.
     * 프리 08:00~09:00, 정규 09:00~15:30(동시호가 15:20~), 애프터 15:30~20:00.
     */
    val krDay: TradingDay =
        TradingDay(
            Market.KR,
            date,
            listOf(
                SessionWindow(
                    MarketSession.PRE,
                    kst("08:00"),
                    kst("09:00"),
                    auctionStart = kst("08:50"),
                ),
                SessionWindow(
                    MarketSession.REGULAR,
                    kst("09:00"),
                    kst("15:30"),
                    auctionStart = kst("15:20"),
                ),
                SessionWindow(
                    MarketSession.AFTER,
                    kst("15:30"),
                    kst("20:00"),
                    auctionEnd = kst("15:40"),
                ),
            ),
        )

    /**
     * 미국 거래일(KST, 실측).
     * 데이마켓 09:00~17:00, 프리 17:00~22:30, 정규 22:30~익일 05:00, 애프터 05:00~08:50.
     */
    val usDay: TradingDay =
        TradingDay(
            Market.US,
            date,
            listOf(
                SessionWindow(MarketSession.DAY_MARKET, kst("09:00"), kst("17:00")),
                SessionWindow(MarketSession.PRE, kst("17:00"), kst("22:30")),
                SessionWindow(MarketSession.REGULAR, kst("22:30"), kst("05:00", date.plusDays(1))),
                SessionWindow(
                    MarketSession.AFTER,
                    kst("05:00", date.plusDays(1)),
                    kst("08:50", date.plusDays(1)),
                ),
            ),
        )

    fun usdKrw(asOf: Instant): ExchangeRate =
        ExchangeRate(Currency.USD, Currency.KRW, BigDecimal("1400"), asOf)

    fun snapshot(
        intent: OrderIntent,
        asOf: Instant,
        openOrders: List<BrokerOrderRecord> = emptyList(),
    ): PortfolioSnapshot {
        val currency = intent.market.currency
        return PortfolioSnapshot(
            intent.userId,
            intent.market,
            emptyList(),
            DepositBalance(mapOf(currency to Money.zero(currency)), asOf),
            openOrders,
            asOf,
        )
    }

    /** 현재가·환율은 기본으로 판정 시각의 값을 씀. */
    fun context(
        intent: OrderIntent,
        now: Instant,
        tradingDay: TradingDay = if (intent.market == Market.KR) krDay else usDay,
        openOrders: List<BrokerOrderRecord> = emptyList(),
        todayOrders: List<BrokerOrder> = emptyList(),
        quote: Quote? = null,
        fx: ExchangeRate? = usdKrw(now),
        snapshotAsOf: Instant = now,
        clientOrderId: ClientOrderId = key,
    ): GuardrailContext =
        GuardrailContext(
            intent,
            clientOrderId,
            snapshot(intent, snapshotAsOf, openOrders),
            todayOrders,
            tradingDay,
            quote,
            fx,
            now,
        )

    fun quote(intent: OrderIntent, last: String, asOf: Instant): Quote {
        return Quote(intent.symbol, Money.of(last, intent.market.currency), asOf)
    }
}
