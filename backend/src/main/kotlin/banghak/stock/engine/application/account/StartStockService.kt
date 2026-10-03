package banghak.stock.engine.application.account

import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.ExchangeRate
import banghak.stock.core.domain.portfolio.BrokerHolding
import banghak.stock.core.domain.trading.PositionValuation
import banghak.stock.core.domain.trading.StartStock
import banghak.stock.core.domain.trading.StartStockReason
import banghak.stock.core.domain.trading.StartStockResolver
import banghak.stock.core.port.MarketDataPort
import banghak.stock.core.port.StockMasterPort
import banghak.stock.core.port.TradingPort
import banghak.stock.core.usecase.LookupStartStockUseCase
import banghak.stock.core.usecase.LookupUserSettingsUseCase
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * 시작 종목 후보를 모아 규칙([StartStockResolver])에 넘김.
 * 후보마다 외부 조회는 2초(설정) 안에 답이 없으면 포기하고 늦게 온 응답은 버림.
 * 시작 화면이 증권사 지연에 묶이지 않게 함.
 */
@Service
@Profile(RuntimeProfiles.ENGINE)
class StartStockService(
    private val settings: LookupUserSettingsUseCase,
    private val stockMaster: StockMasterPort,
    private val trading: TradingPort,
    private val marketData: MarketDataPort,
    @Value("\${stockholm.start-stock.candidate-timeout:PT2S}")
    private val candidateTimeout: Duration,
) : LookupStartStockUseCase {
    private val lookups: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    // 후보가 성립하면 바로 돌려주고 뒤 후보는 조회하지 않음(직전 종목이 있으면 계좌를 묻지 않음)
    override fun startStock(userId: UserId): StartStock {
        withTimeout("직전 종목") { listedLastViewed(userId) }
            ?.let {
                return StartStock(it, StartStockReason.LAST_VIEWED)
            }
        withTimeout("보유 평가") { StartStockResolver.largestPosition(valuationsOf(userId)) }
            ?.let {
                return StartStock(it, StartStockReason.LARGEST_POSITION)
            }
        val market = withTimeout("기본 시장") { settings.defaultMarket(userId) } ?: Market.KR
        return StartStock(StartStockResolver.defaultSymbol(market), StartStockReason.DEFAULT)
    }

    // 설정 조회와 상장 확인을 합쳐 한 후보의 제한 시간 안에서 함
    private fun listedLastViewed(userId: UserId): Symbol? =
        settings.lastViewedStock(userId)?.symbol?.takeIf { stockMaster.isListed(it) }

    // 토스 보유 조회의 평가금액을 그대로 쓰고, 미국은 현재 환율로 원화 환산함.
    // 환율을 받지 못하면 미국 보유만 후보에서 빼고 국내 보유는 그대로 둠
    private fun valuationsOf(userId: UserId): List<PositionValuation> {
        val holdings = trading.holdings(userId).items
        val fx = if (holdings.any { it.symbol.market == Market.US }) usdKrwOrNull() else null
        return holdings.mapNotNull { holding -> valuationOf(holding, fx) }
    }

    private fun valuationOf(holding: BrokerHolding, fx: ExchangeRate?): PositionValuation? {
        if (holding.symbol.market == Market.KR)
            return PositionValuation(holding.symbol, holding.marketValue, holding.purchaseAmount)
        if (fx == null) return null
        return PositionValuation(
            holding.symbol,
            holding.marketValue.convert(fx),
            holding.purchaseAmount.convert(fx),
        )
    }

    private fun usdKrwOrNull(): ExchangeRate? =
        try {
            marketData.exchangeRate(Currency.USD, Currency.KRW)
        } catch (e: MarketDataUnavailableException) {
            log.warn("환율을 받지 못해 미국 보유는 시작 종목 후보에서 뺌({})", e::class.simpleName)
            null
        }

    // 포기한 조회는 취소하고 결과를 쓰지 않음.
    // 실패한 후보는 없는 것으로 봄
    private fun <T> withTimeout(label: String, lookup: () -> T?): T? {
        val future = lookups.submit(lookup)
        return try {
            future.get(candidateTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            log.warn("시작 종목 후보({}) 조회가 {}초 안에 끝나지 않아 건너뜀", label, candidateTimeout.seconds)
            null
        } catch (e: java.util.concurrent.ExecutionException) {
            log.warn("시작 종목 후보({}) 조회 실패({}). 건너뜀", label, e.cause?.let { it::class.simpleName })
            null
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(StartStockService::class.java)
    }
}
