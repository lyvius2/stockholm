package banghak.stock.engine.application.portfolio

import banghak.stock.core.domain.error.BrokerUnavailableException
import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.ExchangeRate
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.portfolio.PortfolioValuation
import banghak.stock.core.domain.portfolio.PortfolioValuator
import banghak.stock.core.domain.trading.CandleInterval
import banghak.stock.core.port.MarketDataPort
import banghak.stock.core.port.TradingPort
import banghak.stock.core.usecase.LookupPortfolioValuationUseCase
import banghak.stock.engine.application.market.CandleHistory
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * 보유주식 평가금액(F18)을 모음.
 * 보유와 매수 가능 금액은 본인 키로, 현재가·전일 종가·환율은 공용 시세로 받음.
 * 참고 값(현재가·환율·전일 종가·매수 가능 금액)은 받지 못해도 나머지를 돌려주고 지연으로 표시함.
 */
@Service
@Profile(RuntimeProfiles.ENGINE)
class PortfolioValuationService(
    private val trading: TradingPort,
    private val marketData: MarketDataPort,
    private val candles: CandleHistory,
    private val clock: Clock,
) : LookupPortfolioValuationUseCase {
    override fun valuation(userId: UserId): PortfolioValuation {
        val holdings = trading.holdings(userId).items
        val symbols = holdings.map { it.symbol }
        var isDelayed = false
        val quotes = quotesOf(symbols) { isDelayed = true }
        if (quotes.size < symbols.size) isDelayed = true
        val fx =
            if (symbols.any { it.market == Market.US })
                usdKrw().also { if (it == null) isDelayed = true }
            else null
        val previousCloses =
            symbols
                .mapNotNull { symbol ->
                    previousCloseOf(symbol) { isDelayed = true }?.let { symbol to it }
                }
                .toMap()
        if (previousCloses.size < symbols.size) isDelayed = true
        val cash = buildMap {
            Currency.entries.forEach { currency ->
                val amount = cashOf(userId, currency)
                if (amount == null) isDelayed = true else put(currency, amount)
            }
        }
        return PortfolioValuator.valuate(
            userId = userId,
            holdings = holdings,
            quotes = quotes,
            previousCloses = previousCloses,
            fxUsdKrw = fx,
            cashBuyingPower = cash,
            isDelayed = isDelayed,
            asOf = clock.instant(),
        )
    }

    // 현재가는 한 번에 200개까지라 나눠 묻고, 한 묶음을 못 받아도 나머지는 씀
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

    private fun usdKrw(): ExchangeRate? =
        try {
            marketData.exchangeRate(Currency.USD, Currency.KRW)
        } catch (e: MarketDataUnavailableException) {
            log.warn("환율을 받지 못해 미국 보유를 원화로 환산하지 않음({})", e::class.simpleName)
            null
        }

    // 전일 종가 = 그 시장의 오늘 거래일(국내 KST·미국 ET) 전 거래일의 일봉 종가.
    // 토스 일봉 시각은 그 거래일 0시 KST 라 봉의 거래일은 KST 날짜로 읽음.
    // 미국 장중(한국 시간 새벽)에는 진행 중인 오늘 봉이 KST 어제 날짜라 KST 기준으로 고르면 그것을 전일로 잘못 봄
    private fun previousCloseOf(symbol: Symbol, onDelayed: () -> Unit): Money? {
        val today = clock.instant().atZone(symbol.market.zone).toLocalDate()
        return try {
            val result =
                candles.page(
                    symbol,
                    CandleInterval.DAY_1,
                    null,
                    RECENT_DAILY_CANDLES,
                    DAILY_MAX_AGE,
                )
            if (result.isDelayed) onDelayed()
            result.page.candles
                .firstOrNull { it.openTime.atZone(KST).toLocalDate().isBefore(today) }
                ?.close
        } catch (e: MarketDataUnavailableException) {
            null
        }
    }

    private fun cashOf(userId: UserId, currency: Currency): Money? =
        try {
            trading.buyingPower(userId, currency)
        } catch (e: BrokerUnavailableException) {
            log.warn("{} 매수 가능 금액을 받지 못해 비워 둠({})", currency, e::class.simpleName)
            null
        }

    companion object {
        private val log = LoggerFactory.getLogger(PortfolioValuationService::class.java)
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")

        // 오늘 봉(진행 중)과 전일 봉, 휴장이 끼어도 찾도록 몇 개 더
        private const val RECENT_DAILY_CANDLES = 5

        // 전일 종가는 하루에 한 번 바뀌므로 받은 지 한 시간 안이면 저장된 일봉을 씀(보유 종목마다 토스를 부르지 않음)
        private val DAILY_MAX_AGE: Duration = Duration.ofHours(1)
    }
}
