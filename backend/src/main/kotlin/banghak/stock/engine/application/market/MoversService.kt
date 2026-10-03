package banghak.stock.engine.application.market

import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.MoverBoard
import banghak.stock.core.domain.market.MoverRanker
import banghak.stock.core.domain.market.RankedStock
import banghak.stock.core.domain.market.RankingPeriod
import banghak.stock.core.domain.market.RankingQuery
import banghak.stock.core.domain.market.RankingType
import banghak.stock.core.domain.market.StockFlags
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Money
import banghak.stock.core.port.MarketDataPort
import banghak.stock.core.port.RankingPort
import banghak.stock.core.usecase.LookupMoversUseCase
import banghak.stock.core.usecase.LookupStockFlagsUseCase
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * 급등락 판을 만듦.
 * 토스 급등·급락 랭킹(1일, 상위 100)은 실시간이 아니라 현재가 다건 조회로 등락률을 다시 계산해 정렬함.
 * 화면이 10초마다 묻되 그 안의 재요청은 같은 판을 돌려주고, 랭킹을 못 받으면 직전 판을 지연 표시로 돌려줌.
 */
@Service
@Profile(RuntimeProfiles.ENGINE)
class MoversService(
    private val rankings: RankingPort,
    private val marketData: MarketDataPort,
    private val flagsLookup: LookupStockFlagsUseCase,
    private val calendar: TradingCalendar,
    private val clock: Clock,
) : LookupMoversUseCase {
    private val boards = ConcurrentHashMap<Market, MoverBoard>()
    private val locks = ConcurrentHashMap<Market, ReentrantLock>()

    override fun board(market: Market): MoverBoard =
        locks
            .computeIfAbsent(market) { ReentrantLock() }
            .withLock {
                val cached = boards[market]
                if (
                    cached != null &&
                        Duration.between(cached.asOf, clock.instant()) < REFRESH_INTERVAL
                )
                    return@withLock cached
                val board = rebuild(market, cached)
                boards[market] = board
                board
            }

    private fun rebuild(market: Market, previous: MoverBoard?): MoverBoard {
        val candidates =
            try {
                candidatesOf(market)
            } catch (e: MarketDataUnavailableException) {
                if (previous == null) throw e
                log.warn("{} 급등락 랭킹을 받지 못해 직전 판을 지연으로 둠({})", market, e::class.simpleName)
                // 순위는 직전 것을 쓰되 장 세션은 지금 달력으로 다시 봄(장이 끝났는데 정규장으로 남지 않게)
                return previous.copy(
                    session = calendar.sessionAt(market, clock.instant()),
                    asOf = clock.instant(),
                    isDelayed = true,
                )
            }
        var isDelayed = false
        val symbols = candidates.map { it.symbol }.distinct()
        val quotes = quotesOf(symbols) { isDelayed = true }
        // 값이 빠진 종목은 랭킹의 옛 가격으로 계산하므로 하나라도 빠지면 지연임
        if (quotes.size < symbols.size) isDelayed = true
        val (gainers, losers) = MoverRanker.rank(candidates, quotes, previous, emptyMap())
        val flags = flagsOf((gainers + losers).map { it.symbol })
        return MoverBoard(
            market = market,
            gainers = gainers.map { it.copy(flags = flags[it.symbol]) },
            losers = losers.map { it.copy(flags = flags[it.symbol]) },
            session = calendar.sessionAt(market, clock.instant()),
            asOf = clock.instant(),
            isDelayed = isDelayed,
        )
    }

    // 급등·급락 후보를 따로 받아 합침(상위 100씩)
    private fun candidatesOf(market: Market): List<RankedStock> =
        listOf(RankingType.TOP_GAINERS, RankingType.TOP_LOSERS).flatMap { type ->
            rankings
                .ranking(
                    RankingQuery(
                        market,
                        type,
                        RankingPeriod.DAY_1,
                        excludesInvestmentCaution = false,
                    )
                )
                .stocks
        }

    private fun quotesOf(symbols: List<Symbol>, onMissing: () -> Unit): Map<Symbol, Money> =
        symbols
            .chunked(MarketDataPort.MAX_SYMBOLS)
            .flatMap { chunk ->
                try {
                    marketData.quotes(chunk).map { it.symbol to it.last }
                } catch (e: MarketDataUnavailableException) {
                    onMissing()
                    emptyList()
                }
            }
            .toMap()

    // 화면에 보이는 10종목만 경고를 받음(종목 정보 호출 초당 5회)
    private fun flagsOf(symbols: List<Symbol>): Map<Symbol, StockFlags> =
        symbols
            .mapNotNull { symbol ->
                try {
                    symbol to flagsLookup.flags(symbol)
                } catch (e: MarketDataUnavailableException) {
                    null
                }
            }
            .toMap()

    companion object {
        private val log = LoggerFactory.getLogger(MoversService::class.java)

        /**
         * 화면 갱신 주기와 같음.
         * 그 안의 재요청은 같은 판.
         */
        val REFRESH_INTERVAL: Duration = Duration.ofSeconds(10)
    }
}
