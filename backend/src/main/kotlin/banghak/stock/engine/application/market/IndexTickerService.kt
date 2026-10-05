package banghak.stock.engine.application.market

import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.error.SecretMissingException
import banghak.stock.core.domain.market.IndexCode
import banghak.stock.core.domain.market.IndexEntry
import banghak.stock.core.domain.market.IndexQuote
import banghak.stock.core.domain.market.IndexSetChoice
import banghak.stock.core.domain.market.IndexSetRule
import banghak.stock.core.domain.market.IndexSetState
import banghak.stock.core.domain.market.IndexSourceState
import banghak.stock.core.domain.market.IndexTicker
import banghak.stock.core.domain.market.MacroSeries
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.MarketIndicator
import banghak.stock.core.domain.market.MarketSession
import banghak.stock.core.domain.market.SessionWindow
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.trading.CandleInterval
import banghak.stock.core.domain.trading.StreamMessage
import banghak.stock.core.port.IndexQuoteStorePort
import banghak.stock.core.port.MacroIndicatorPort
import banghak.stock.core.port.MarketDataPort
import banghak.stock.core.port.MarketIndicatorPort
import banghak.stock.core.usecase.LookupIndexTickerUseCase
import banghak.stock.core.usecase.MarketStreamUseCase
import banghak.stock.core.usecase.RefreshIndexTickerUseCase
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * 지수 티커를 만듦(5분마다).
 * 세트는 장 달력으로 고르고, 국내는 토스 시장 지표, 미국 장중은 토스 ETF 프록시(SPY·QQQ·DIA), 미국·일본 종가는 FRED 에서 받음.
 * 지수마다 새로 받지 못하면 마지막 값을 두고 지연으로 표시함.
 * 정보 표시일 뿐 자동 주문의 입력이 아님.
 */
@Service
@Profile(RuntimeProfiles.ENGINE)
class IndexTickerService(
    private val calendar: TradingCalendar,
    private val indicators: MarketIndicatorPort,
    private val marketData: MarketDataPort,
    private val candles: CandleHistory,
    private val macro: MacroIndicatorPort,
    private val store: IndexQuoteStorePort,
    private val stream: MarketStreamUseCase,
    private val clock: Clock,
) : LookupIndexTickerUseCase, RefreshIndexTickerUseCase {
    private val lock = ReentrantLock()

    @Volatile private var ticker: IndexTicker? = null

    private val lastQuotes: MutableMap<IndexCode, IndexQuote> by lazy {
        store.loadAll().associateBy { it.code }.toMutableMap()
    }

    // 첫 갱신 전에는 저장된 마지막 값으로 세트를 채워 재시작 직후에도 티커가 비지 않게 함
    override fun current(): IndexTicker = ticker ?: fromLastQuotes(clock.instant())

    override fun refresh(): IndexTicker = lock.withLock {
        val now = clock.instant()
        val choice = choose(now)
        val entries =
            choice.codes.map { code ->
                when (val result = fetch(code, choice, now)) {
                    is Fetch.Fresh -> {
                        lastQuotes[code] = result.quote
                        IndexEntry(code, result.quote, IndexSourceState.FRESH)
                    }
                    Fetch.Unconfigured ->
                        IndexEntry(code, lastQuotes[code], IndexSourceState.UNCONFIGURED)
                    Fetch.Failed -> IndexEntry(code, lastQuotes[code], IndexSourceState.DELAYED)
                }
            }
        store.saveAll(entries.filter { it.state == IndexSourceState.FRESH }.mapNotNull { it.quote })
        val built = IndexTicker(choice, entries, now)
        ticker = built
        stream.broadcast(StreamMessage.IndexTickerUpdate(built))
        built
    }

    private fun fromLastQuotes(now: Instant): IndexTicker {
        val choice = choose(now)
        return IndexTicker(
            choice,
            choice.codes.map { IndexEntry(it, lastQuotes[it], IndexSourceState.DELAYED) },
            now,
        )
    }

    private sealed interface Fetch {
        data class Fresh(val quote: IndexQuote) : Fetch

        data object Unconfigured : Fetch

        data object Failed : Fetch
    }

    private fun fetch(code: IndexCode, choice: IndexSetChoice, now: Instant): Fetch =
        try {
            quoteOf(code, choice, now)?.let { Fetch.Fresh(it) } ?: Fetch.Failed
        } catch (e: SecretMissingException) {
            Fetch.Unconfigured
        } catch (e: MarketDataUnavailableException) {
            log.warn("{} 를 받지 못해 마지막 값을 둠({})", code, e::class.simpleName)
            Fetch.Failed
        }

    // 정규장 창을 지난 일주일부터 내일까지 모아 넘김.
    // 미국 장은 한국 날짜를 넘고, 연휴 뒤에는 가장 최근에 닫힌 시장을 며칠 전에서 찾아야 함
    private fun choose(now: Instant): IndexSetChoice =
        IndexSetRule.choose(now, regularWindows(Market.KR, now), regularWindows(Market.US, now))

    private fun regularWindows(market: Market, now: Instant): List<SessionWindow> {
        val date = now.atZone(market.zone).toLocalDate()
        return (-LOOKBACK_DAYS..1L)
            .map { date.plusDays(it) }
            .mapNotNull { day ->
                try {
                    calendar.tradingDay(market, day).window(MarketSession.REGULAR)
                } catch (e: MarketDataUnavailableException) {
                    null
                }
            }
    }

    private fun quoteOf(code: IndexCode, choice: IndexSetChoice, now: Instant): IndexQuote? =
        when (code) {
            IndexCode.KOSPI -> koreanIndex(code, MarketIndicator.KOSPI, choice, now)
            IndexCode.KOSDAQ -> koreanIndex(code, MarketIndicator.KOSDAQ, choice, now)
            IndexCode.NIKKEI225 -> macroClose(code, MacroSeries.NIKKEI225, now)
            IndexCode.DJIA -> usIndex(code, Symbol(Market.US, "DIA"), MacroSeries.DJIA, choice, now)
            IndexCode.NASDAQ ->
                usIndex(code, Symbol(Market.US, "QQQ"), MacroSeries.NASDAQ_COMPOSITE, choice, now)
            IndexCode.SP500 ->
                usIndex(code, Symbol(Market.US, "SPY"), MacroSeries.SP500, choice, now)
        }

    // 장중은 현재가 + 전일 종가, 장 밖은 일봉의 마지막 종가 + 그 전 종가
    private fun koreanIndex(
        code: IndexCode,
        indicator: MarketIndicator,
        choice: IndexSetChoice,
        now: Instant,
    ): IndexQuote? {
        val closes = indicators.dailyCloses(indicator, RECENT_DAILY)
        val today = now.atZone(KST).toLocalDate()
        val finished = closes.filter { it.date.isBefore(today) }
        if (choice.state != IndexSetState.OPEN) {
            val last = finished.firstOrNull() ?: return null
            return IndexQuote.of(
                code,
                last.close,
                finished.getOrNull(1)?.close,
                now,
                true,
                null,
                SOURCE_TOSS,
            )
        }
        val live = indicators.indicatorQuotes(listOf(indicator)).firstOrNull() ?: return null
        return IndexQuote.of(
            code,
            live.value,
            finished.firstOrNull()?.close,
            live.asOf ?: now,
            false,
            null,
            SOURCE_TOSS,
        )
    }

    // 장중·개장 전은 ETF 현재가를 프록시로, 장 밖은 FRED 지수 종가
    private fun usIndex(
        code: IndexCode,
        proxy: Symbol,
        series: MacroSeries,
        choice: IndexSetChoice,
        now: Instant,
    ): IndexQuote? {
        if (choice.state == IndexSetState.CLOSE) return macroClose(code, series, now)
        val quote = marketData.quotes(listOf(proxy)).firstOrNull() ?: return null
        val previousClose = previousEtfClose(proxy, now)
        return IndexQuote.of(
            code,
            quote.last.amount,
            previousClose,
            quote.asOf,
            false,
            proxy.code,
            SOURCE_TOSS_ETF,
        )
    }

    private fun previousEtfClose(proxy: Symbol, now: Instant) =
        candles
            .page(proxy, CandleInterval.DAY_1, null, RECENT_DAILY, DAILY_MAX_AGE)
            .page
            .candles
            .firstOrNull {
                it.openTime
                    .atZone(KST)
                    .toLocalDate()
                    .isBefore(now.atZone(Market.US.zone).toLocalDate())
            }
            ?.close
            ?.amount

    private fun macroClose(code: IndexCode, series: MacroSeries, now: Instant): IndexQuote? {
        val observations = macro.recentObservations(series, 2)
        val latest = observations.firstOrNull() ?: return null
        return IndexQuote.of(
            code,
            latest.value,
            observations.getOrNull(1)?.value,
            latest.date.atStartOfDay(KST).toInstant(),
            true,
            null,
            SOURCE_FRED,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(IndexTickerService::class.java)
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private const val SOURCE_TOSS = "TOSS"
        private const val SOURCE_TOSS_ETF = "TOSS_ETF"
        private const val SOURCE_FRED = "FRED"

        // 오늘 봉과 전일 봉, 휴장이 끼어도 찾도록 몇 개 더
        private const val RECENT_DAILY = 5

        // 가장 최근에 닫힌 장을 찾을 때 되돌아보는 날수(추석·크리스마스 연휴를 덮음)
        private const val LOOKBACK_DAYS = 7L
        private val DAILY_MAX_AGE: Duration = Duration.ofHours(1)
    }
}
