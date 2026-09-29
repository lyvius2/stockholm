package banghak.stock.engine.adapter.out.toss

import banghak.stock.core.domain.error.DomainException
import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.market.KrTradingDetail
import banghak.stock.core.domain.market.ListingBoard
import banghak.stock.core.domain.market.ListingStatus
import banghak.stock.core.domain.market.SecurityType
import banghak.stock.core.domain.market.StockProfile
import banghak.stock.core.domain.market.StockWarning
import banghak.stock.core.domain.market.StockWarningType
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.port.StockCatalogPort
import banghak.stock.shared.config.RuntimeProfiles
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.ratelimiter.annotation.RateLimiter
import java.time.LocalDate
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 토스 종목 정보 어댑터.
 * 공용 정보라 admin 의 키로 부름.
 * 시장별 전체 목록은 코드·이름·종류만 주므로 나머지는 [profiles] 로 200건씩 채움.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class TossStockCatalogAdapter(
    private val stocks: TossStockClient,
    private val callers: TossCallerResolver,
) : StockCatalogPort {
    // 형식에 맞지 않는 코드(신규 규격 등)는 한 종목 때문에 시장 전체를 버리지 않도록 건너뜀
    @RateLimiter(name = "toss-stock-all")
    @CircuitBreaker(name = "toss-stock", fallbackMethod = "listedSymbolsUnavailable")
    override fun listedSymbols(board: ListingBoard): List<Symbol> {
        val listed =
            TossResponses.marketResultOf(
                stocks.listedStocks(callers.publicMarketCaller(), board.name).execute()
            )
        val symbols = listed.mapNotNull { symbolOrNull(board, it.symbol) }
        if (symbols.size < listed.size)
            log.warn("{} 종목 {}건은 코드 형식이 맞지 않아 건너뜀", board, listed.size - symbols.size)
        return symbols
    }

    fun listedSymbolsUnavailable(board: ListingBoard, cause: Throwable): List<Symbol> =
        TossResponses.marketFallback(cause)

    @RateLimiter(name = "toss-stock")
    @CircuitBreaker(name = "toss-stock", fallbackMethod = "profilesUnavailable")
    override fun profiles(symbols: List<Symbol>): List<StockProfile> {
        if (symbols.isEmpty()) return emptyList()
        if (symbols.size > StockCatalogPort.MAX_SYMBOLS)
            throw InvalidValueException(
                "종목 정보는 한 번에 ${StockCatalogPort.MAX_SYMBOLS}개까지 조회함: ${symbols.size}"
            )
        val byCode = symbols.associateBy { it.code }
        val result =
            TossResponses.marketResultOf(
                stocks
                    .stocks(callers.publicMarketCaller(), symbols.joinToString(",") { it.code })
                    .execute()
            )
        val profiles = result.mapNotNull { info ->
            byCode[info.symbol]?.let { profileOrNull(it, info) }
        }
        if (profiles.size < result.size)
            log.warn("종목 정보 {}건은 요청과 맞지 않거나 값이 잘못돼 건너뜀", result.size - profiles.size)
        return profiles
    }

    fun profilesUnavailable(symbols: List<Symbol>, cause: Throwable): List<StockProfile> =
        TossResponses.marketFallback(cause)

    @RateLimiter(name = "toss-stock")
    @CircuitBreaker(name = "toss-stock", fallbackMethod = "warningsUnavailable")
    override fun warnings(symbol: Symbol): List<StockWarning> =
        TossResponses.marketResultOf(
                stocks.warnings(callers.publicMarketCaller(), symbol.code).execute()
            )
            .map {
                StockWarning(
                    enumOrUnknown(it.warningType, StockWarningType.UNKNOWN),
                    it.startDate?.let(LocalDate::parse),
                    it.endDate?.let(LocalDate::parse),
                )
            }

    fun warningsUnavailable(symbol: Symbol, cause: Throwable): List<StockWarning> =
        TossResponses.marketFallback(cause)

    // 한 종목의 잘못된 값 때문에 200건 묶음 전체를 버리지 않음
    private fun profileOrNull(symbol: Symbol, info: TossStockInfo): StockProfile? = runCatching {
        profileOf(symbol, info)
    }
        .onFailure { if (it !is DomainException) throw it }
        .getOrNull()

    // 요청한 시장·통화와 다른 응답은 잘못된 종목으로 저장될 수 있어 버림
    private fun profileOf(symbol: Symbol, info: TossStockInfo): StockProfile {
        val board =
            ListingBoard.entries.firstOrNull {
                it.name == info.market && it.market == symbol.market
            } ?: throw MarketDataUnavailableException("$symbol 의 상장 시장이 ${info.market} 로 옴")
        if (info.currency != symbol.market.currency.name)
            throw MarketDataUnavailableException(
                "$symbol 는 ${symbol.market.currency} 인데 ${info.currency} 로 옴"
            )
        return StockProfile(
            symbol = symbol,
            name = info.name,
            englishName = info.englishName,
            isin = info.isinCode,
            board = board,
            securityType = enumOrUnknown(info.securityType, SecurityType.UNKNOWN),
            isCommonShare = info.isCommonShare,
            status = statusOf(symbol, info.status),
            listedOn = info.listDate?.let { dateOf(symbol, it) },
            delistedOn = info.delistDate?.let { dateOf(symbol, it) },
            sharesOutstanding = info.sharesOutstanding,
            leverageFactor = info.leverageFactor,
            krDetail = info.koreanMarketDetail?.let { krDetailOf(symbol, it) },
        )
    }

    private fun statusOf(symbol: Symbol, status: String): ListingStatus =
        ListingStatus.entries.firstOrNull { it.name == status }
            ?: throw MarketDataUnavailableException("$symbol 의 상장 상태를 알 수 없음: $status")

    private fun krDetailOf(symbol: Symbol, detail: TossKrMarketDetail): KrTradingDetail =
        KrTradingDetail(
            isLiquidationTrading =
                detail.liquidationTrading ?: missing(symbol, "liquidationTrading"),
            isNxtSupported = detail.nxtSupported ?: missing(symbol, "nxtSupported"),
            isKrxSuspended = detail.krxTradingSuspended ?: missing(symbol, "krxTradingSuspended"),
            isNxtSuspended = detail.nxtTradingSuspended,
        )

    private fun dateOf(symbol: Symbol, text: String): LocalDate = runCatching {
        LocalDate.parse(text)
    }
        .getOrElse { throw MarketDataUnavailableException("$symbol 의 날짜 형식이 잘못됨: $text") }

    private fun missing(symbol: Symbol, field: String): Nothing =
        throw MarketDataUnavailableException("$symbol 의 국내 거래 상태에 $field 가 없음")

    private fun symbolOrNull(board: ListingBoard, code: String): Symbol? = runCatching {
        Symbol(board.market, code)
    }
        .getOrNull()

    private inline fun <reified E : Enum<E>> enumOrUnknown(value: String, unknown: E): E =
        enumValues<E>().firstOrNull { it.name == value } ?: unknown

    companion object {
        private val log = LoggerFactory.getLogger(TossStockCatalogAdapter::class.java)
    }
}
