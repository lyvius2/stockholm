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
import banghak.stock.core.port.StockCatalogPort
import banghak.stock.core.port.StockFlagsCachePort
import banghak.stock.core.port.StockMasterPort
import java.math.BigDecimal
import java.time.Instant

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
