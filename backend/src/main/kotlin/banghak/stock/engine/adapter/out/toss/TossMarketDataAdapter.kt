package banghak.stock.engine.adapter.out.toss

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.ExchangeRate
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.trading.Candle
import banghak.stock.core.domain.trading.CandleInterval
import banghak.stock.core.domain.trading.CandlePage
import banghak.stock.core.domain.trading.OrderBook
import banghak.stock.core.domain.trading.PriceLimits
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.Quote
import banghak.stock.core.port.MarketDataPort
import banghak.stock.shared.config.RuntimeProfiles
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.ratelimiter.annotation.RateLimiter
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 토스 시세 어댑터.
 * 공용 시세라 admin 의 키로 부름.
 * 그룹별 초당 한도는 RateLimiter 설정(application-engine.yml)에 둠.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class TossMarketDataAdapter(
    private val prices: TossPriceClient,
    private val charts: TossChartClient,
    private val marketInfo: TossMarketInfoClient,
    private val callers: TossCallerResolver,
) : MarketDataPort {
    @RateLimiter(name = "toss-market-data")
    @CircuitBreaker(name = "toss-market-data", fallbackMethod = "quotesUnavailable")
    override fun quotes(symbols: List<Symbol>): List<Quote> {
        if (symbols.isEmpty()) return emptyList()
        if (symbols.size > MarketDataPort.MAX_SYMBOLS)
            throw InvalidValueException(
                "현재가는 한 번에 ${MarketDataPort.MAX_SYMBOLS}개까지 조회함: ${symbols.size}"
            )
        val byCode = symbols.associateBy { it.code }
        val result =
            TossResponses.marketResultOf(
                prices
                    .prices(callers.publicMarketCaller(), symbols.joinToString(",") { it.code })
                    .execute()
            )
        return result.mapNotNull { price -> byCode[price.symbol]?.let { toQuote(it, price) } }
    }

    fun quotesUnavailable(symbols: List<Symbol>, cause: Throwable): List<Quote> =
        TossResponses.marketFallback(cause)

    @RateLimiter(name = "toss-chart")
    @CircuitBreaker(name = "toss-chart", fallbackMethod = "candlesUnavailable")
    override fun candlePage(
        symbol: Symbol,
        interval: CandleInterval,
        before: Instant?,
        count: Int,
    ): CandlePage {
        if (count !in 1..MarketDataPort.MAX_CANDLES)
            throw InvalidValueException("봉 개수는 1~${MarketDataPort.MAX_CANDLES}: $count")
        val page =
            TossResponses.marketResultOf(
                charts
                    .candles(
                        callers.publicMarketCaller(),
                        symbol.code,
                        intervalCode(interval),
                        count,
                        before?.let(::toKst),
                    )
                    .execute()
            )
        return CandlePage(
            page.candles.map { toCandle(symbol, interval, it) },
            page.nextBefore?.let(::parseInstant),
        )
    }

    fun candlesUnavailable(
        symbol: Symbol,
        interval: CandleInterval,
        before: Instant?,
        count: Int,
        cause: Throwable,
    ): CandlePage = TossResponses.marketFallback(cause)

    @RateLimiter(name = "toss-market-data")
    @CircuitBreaker(name = "toss-market-data", fallbackMethod = "orderBookUnavailable")
    override fun orderBook(symbol: Symbol): OrderBook {
        val book =
            TossResponses.marketResultOf(
                prices.orderbook(callers.publicMarketCaller(), symbol.code).execute()
            )
        val currency = requireMarketCurrency(symbol, book.currency)
        val levels = { entries: List<TossOrderbookEntry> ->
            entries.map { OrderBook.Level(Money.of(it.price, currency), Quantity.of(it.volume)) }
        }
        return OrderBook(
            symbol,
            levels(book.asks),
            levels(book.bids),
            book.timestamp?.let(::parseInstant) ?: Instant.EPOCH,
        )
    }

    fun orderBookUnavailable(symbol: Symbol, cause: Throwable): OrderBook =
        TossResponses.marketFallback(cause)

    @RateLimiter(name = "toss-market-data")
    @CircuitBreaker(name = "toss-market-data", fallbackMethod = "priceLimitsUnavailable")
    override fun priceLimits(symbol: Symbol): PriceLimits {
        val limits =
            TossResponses.marketResultOf(
                prices.priceLimits(callers.publicMarketCaller(), symbol.code).execute()
            )
        val currency = requireMarketCurrency(symbol, limits.currency)
        return PriceLimits(
            symbol,
            limits.upperLimitPrice?.let { Money.of(it, currency) },
            limits.lowerLimitPrice?.let { Money.of(it, currency) },
            parseInstant(limits.timestamp),
        )
    }

    fun priceLimitsUnavailable(symbol: Symbol, cause: Throwable): PriceLimits =
        TossResponses.marketFallback(cause)

    @RateLimiter(name = "toss-market-info")
    @CircuitBreaker(name = "toss-market-info", fallbackMethod = "exchangeRateUnavailable")
    override fun exchangeRate(from: Currency, to: Currency): ExchangeRate =
        rateOf(
            from,
            to,
            TossResponses.marketResultOf(
                marketInfo
                    .exchangeRate(callers.publicMarketCaller(), from.name, to.name, null)
                    .execute()
            ),
        )

    fun exchangeRateUnavailable(from: Currency, to: Currency, cause: Throwable): ExchangeRate =
        TossResponses.marketFallback(cause)

    @RateLimiter(name = "toss-market-info")
    @CircuitBreaker(name = "toss-market-info", fallbackMethod = "exchangeRateAtUnavailable")
    override fun exchangeRateAt(from: Currency, to: Currency, at: Instant): ExchangeRate =
        rateOf(
            from,
            to,
            TossResponses.marketResultOf(
                marketInfo
                    .exchangeRate(callers.publicMarketCaller(), from.name, to.name, toKst(at))
                    .execute()
            ),
        )

    fun exchangeRateAtUnavailable(
        from: Currency,
        to: Currency,
        at: Instant,
        cause: Throwable,
    ): ExchangeRate = TossResponses.marketFallback(cause)

    // 요청과 다른 통화쌍이 오면 잘못 환산되므로 조회 실패로 봄
    private fun rateOf(from: Currency, to: Currency, rate: TossExchangeRate): ExchangeRate {
        if (rate.baseCurrency != from.name || rate.quoteCurrency != to.name)
            throw MarketDataUnavailableException(
                "요청한 환율 ${from}→${to} 와 다른 ${rate.baseCurrency}→${rate.quoteCurrency} 가 옴"
            )
        return ExchangeRate(from, to, rate.rate, parseInstant(rate.validFrom))
    }

    // 시각이 없는 현재가는 가장 오래된 시각으로 둬 신선도 검사에서 걸러지게 함
    private fun toQuote(symbol: Symbol, price: TossPrice): Quote =
        Quote(
            symbol,
            Money.of(price.lastPrice, requireMarketCurrency(symbol, price.currency)),
            price.timestamp?.let(::parseInstant) ?: Instant.EPOCH,
        )

    // 토스 1분봉 timestamp 는 봉 종료 시각, 일봉은 그날 00:00(KST)임
    private fun toCandle(symbol: Symbol, interval: CandleInterval, candle: TossCandle): Candle {
        val stamp = parseInstant(candle.timestamp)
        val openTime =
            if (interval == CandleInterval.MINUTE_1) stamp.minus(interval.length) else stamp
        val currency = requireMarketCurrency(symbol, candle.currency)
        return Candle(
            symbol,
            interval,
            openTime,
            Money.of(candle.openPrice, currency),
            Money.of(candle.highPrice, currency),
            Money.of(candle.lowPrice, currency),
            Money.of(candle.closePrice, currency),
            Quantity.of(candle.volume),
        )
    }

    // 응답 통화를 종목 통화로 덮어쓰지 않음.
    // 다르면 환산 없이 잘못된 금액이 가드레일로 가므로 조회 실패로 봄
    private fun requireMarketCurrency(symbol: Symbol, currency: String): Currency {
        val expected = symbol.market.currency
        if (currency != expected.name)
            throw MarketDataUnavailableException("$symbol 는 $expected 인데 토스가 $currency 로 줌")
        return expected
    }

    private fun intervalCode(interval: CandleInterval): String =
        when (interval) {
            CandleInterval.MINUTE_1 -> "1m"
            CandleInterval.DAY_1 -> "1d"
        }

    // OffsetDateTime.toString() 은 0초를 생략하므로(23:30+09:00) 초를 늘 쓰는 ISO 형식으로 보냄(RFC 3339)
    private fun toKst(instant: Instant): String = ISO_WITH_SECONDS.format(instant.atOffset(KST))

    private fun parseInstant(text: String): Instant = OffsetDateTime.parse(text).toInstant()

    companion object {
        private val KST: ZoneOffset = ZoneOffset.ofHours(9)
        private val ISO_WITH_SECONDS: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }
}
