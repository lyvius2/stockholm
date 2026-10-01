package banghak.stock.support.fakes

import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.market.KrTradingDetail
import banghak.stock.core.domain.market.ListingBoard
import banghak.stock.core.domain.market.ListingStatus
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.SecurityType
import banghak.stock.core.domain.market.StockFlags
import banghak.stock.core.domain.market.StockProfile
import banghak.stock.core.domain.market.StockWarning
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.market.TradingDay
import banghak.stock.core.domain.trading.Candle
import banghak.stock.core.domain.trading.CandleCoverage
import banghak.stock.core.domain.trading.CandleInterval
import banghak.stock.core.domain.trading.StreamMessage
import banghak.stock.core.domain.trading.StreamViewerId
import banghak.stock.core.port.CandleStorePort
import banghak.stock.core.port.MarketCalendarPort
import banghak.stock.core.port.MarketCalendarStorePort
import banghak.stock.core.port.StockCatalogPort
import banghak.stock.core.port.StockFlagsCachePort
import banghak.stock.core.port.StockMasterPort
import banghak.stock.core.port.StreamPushPort
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** 시장별 목록·종목 정보·유의사항을 손으로 채우는 종목 정보 포트. */
class FakeStockCatalog : StockCatalogPort {
    val listed = mutableMapOf<ListingBoard, List<Symbol>>()
    val warnings = mutableMapOf<Symbol, List<StockWarning>>()
    val krDetails = mutableMapOf<Symbol, KrTradingDetail>()
    val unavailableBoards = mutableSetOf<ListingBoard>()
    val droppedProfiles = mutableSetOf<Symbol>()
    var isUnavailable = false
    val listedCalls = mutableListOf<ListingBoard>()
    val profileCalls = mutableListOf<List<Symbol>>()
    val warningCalls = mutableListOf<Symbol>()

    override fun listedSymbols(board: ListingBoard): List<Symbol> {
        listedCalls += board
        if (isUnavailable || board in unavailableBoards)
            throw MarketDataUnavailableException("$board 조회 실패")
        return listed[board].orEmpty()
    }

    override fun profiles(symbols: List<Symbol>): List<StockProfile> {
        profileCalls += symbols
        if (isUnavailable) throw MarketDataUnavailableException("종목 정보 조회 실패")
        return symbols
            .filterNot { it in droppedProfiles }
            .map { profileOf(it, boardOf(it), krDetails[it]) }
    }

    override fun warnings(symbol: Symbol): List<StockWarning> {
        warningCalls += symbol
        if (isUnavailable) throw MarketDataUnavailableException("유의사항 조회 실패")
        return warnings[symbol].orEmpty()
    }

    private fun boardOf(symbol: Symbol): ListingBoard =
        listed.entries.firstOrNull { symbol in it.value }?.key
            ?: if (symbol.market == Market.KR) ListingBoard.KOSPI else ListingBoard.NASDAQ

    companion object {
        fun profileOf(symbol: Symbol, board: ListingBoard, krDetail: KrTradingDetail? = null) =
            StockProfile(
                symbol = symbol,
                name = "종목${symbol.code}",
                englishName = "Stock ${symbol.code}",
                isin = "XX${symbol.code}",
                board = board,
                securityType = SecurityType.STOCK,
                isCommonShare = true,
                status = ListingStatus.ACTIVE,
                listedOn = null,
                delistedOn = null,
                sharesOutstanding = BigDecimal.ONE,
                leverageFactor = null,
                krDetail = krDetail,
            )
    }
}

class MemoryStockMaster : StockMasterPort {
    val saved = mutableMapOf<Symbol, StockProfile>()
    val delistCalls = mutableListOf<Pair<ListingBoard, Set<Symbol>>>()
    var syncedAt: Instant? = null

    override fun saveAll(profiles: List<StockProfile>, at: Instant) {
        profiles.forEach { saved[it.symbol] = it }
    }

    override fun markDelistedExcept(board: ListingBoard, listed: Set<Symbol>, at: Instant) {
        delistCalls += board to listed
    }

    override fun lastSyncedAt(): Instant? = syncedAt

    override fun recordSync(at: Instant) {
        syncedAt = at
    }
}

class MemoryStockFlagsCache : StockFlagsCachePort {
    val flags = mutableMapOf<Symbol, StockFlags>()

    override fun find(symbol: Symbol): StockFlags? = flags[symbol]

    override fun save(flags: StockFlags) {
        this.flags[flags.symbol] = flags
    }
}

/**
 * 봉 저장소 가짜.
 * 포트 계약(범위 양 끝 포함, 최신순, 같은 시각은 덮어씀)을 그대로 지킴.
 */
class MemoryCandleStore : CandleStorePort {
    private val candles = mutableMapOf<Triple<Symbol, CandleInterval, Instant>, Candle>()
    private val coverages = mutableMapOf<Pair<Symbol, CandleInterval>, CandleCoverage>()
    var reads = 0

    override fun coverage(symbol: Symbol, interval: CandleInterval): CandleCoverage? =
        coverages[symbol to interval]

    override fun candles(
        symbol: Symbol,
        interval: CandleInterval,
        from: Instant,
        before: Instant,
        limit: Int,
    ): List<Candle> {
        reads++
        return candles.values
            .filter { it.symbol == symbol && it.interval == interval }
            .filter { !it.openTime.isBefore(from) && !it.openTime.isAfter(before) }
            .sortedByDescending { it.openTime }
            .take(limit)
    }

    override fun save(candles: List<Candle>, coverage: CandleCoverage?, fetchedAt: Instant) {
        candles.forEach { this.candles[Triple(it.symbol, it.interval, it.openTime)] = it }
        if (coverage != null) coverages[coverage.symbol to coverage.interval] = coverage
    }

    override fun purge(interval: CandleInterval, olderThan: Instant): Int {
        val expired =
            candles.filterValues { it.interval == interval && it.openTime.isBefore(olderThan) }.keys
        expired.toList().forEach(candles::remove)
        coverages
            .filterKeys { it.second == interval }
            .forEach { (key, coverage) ->
                val trimmed = coverage.trimmedTo(olderThan)
                if (trimmed == null) coverages.remove(key) else coverages[key] = trimmed
            }
        return expired.size
    }

    fun count(): Int = candles.size
}

/** 화면 연결에 민 메시지를 모아 둠. */
class FakeStreamPush : StreamPushPort {
    val pushed = mutableListOf<Pair<StreamViewerId, List<StreamMessage>>>()

    override fun push(viewer: StreamViewerId, messages: List<StreamMessage>) {
        pushed += viewer to messages
    }

    fun messagesTo(viewer: StreamViewerId): List<StreamMessage> =
        pushed.filter { it.first == viewer }.flatMap { it.second }
}

/**
 * 날짜별 장 달력 가짜.
 * 넣어 둔 날은 그것을, 없는 날은 휴장(세션 없음)을 돌려주며 호출 횟수를 셈.
 */
class ScriptedMarketCalendar : MarketCalendarPort {
    val days = mutableMapOf<Pair<Market, LocalDate>, TradingDay>()
    val requests = mutableListOf<Pair<Market, LocalDate>>()
    var failure: RuntimeException? = null

    override fun tradingDay(market: Market, date: LocalDate): TradingDay {
        requests += market to date
        failure?.let { throw it }
        return days[market to date] ?: TradingDay(market, date, emptyList())
    }
}

class MemoryMarketCalendarStore : MarketCalendarStorePort {
    private val rows = mutableMapOf<Pair<Market, LocalDate>, MarketCalendarStorePort.Stored>()

    override fun find(market: Market, date: LocalDate): MarketCalendarStorePort.Stored? =
        rows[market to date]

    override fun save(day: TradingDay, fetchedAt: Instant) {
        rows[day.market to day.date] = MarketCalendarStorePort.Stored(day, fetchedAt)
    }

    fun count(): Int = rows.size
}
