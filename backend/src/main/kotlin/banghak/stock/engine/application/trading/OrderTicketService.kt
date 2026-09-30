package banghak.stock.engine.application.trading

import banghak.stock.core.domain.error.BrokerAccessDeniedException
import banghak.stock.core.domain.error.BrokerUnavailableException
import banghak.stock.core.domain.error.DomainException
import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Percent
import banghak.stock.core.domain.trading.OrderTicket
import banghak.stock.core.domain.trading.PriceLimits
import banghak.stock.core.port.MarketDataPort
import banghak.stock.core.port.TradingPort
import banghak.stock.core.usecase.LookupOrderTicketUseCase
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * 주문 모달에 보일 주문 가능 정보를 모음.
 * 매수 가능 금액·판매 가능 수량은 받지 못하면 예외가 그대로 올라감.
 * 상하한가·수수료율은 참고 정보라 받지 못해도(연결 실패·한도·인증 거부 모두) 비워 두고 나머지를 돌려줌.
 * 상하한가는 admin 키로 받으므로 admin 키가 거부돼도 본인 계좌 정보는 보여야 함.
 * 키·허용 IP 문제는 필수 정보 조회가 같은 예외로 드러냄.
 * 여기 값은 화면 안내용이며 주문을 막는 판단은 가드레일과 토스가 함.
 */
@Service
@Profile(RuntimeProfiles.ENGINE)
class OrderTicketService(
    private val trading: TradingPort,
    private val marketData: MarketDataPort,
    private val clock: Clock,
) : LookupOrderTicketUseCase {
    override fun ticket(userId: UserId, symbol: Symbol): OrderTicket =
        OrderTicket(
            symbol = symbol,
            buyingPower = trading.buyingPower(userId, symbol.market.currency),
            sellableQuantity = trading.sellableQuantity(userId, symbol),
            priceLimits = priceLimitsOf(symbol),
            commissionRate = commissionRateOf(userId, symbol.market),
        )

    private fun priceLimitsOf(symbol: Symbol): PriceLimits? =
        try {
            marketData.priceLimits(symbol)
        } catch (e: MarketDataUnavailableException) {
            unavailable("상하한가", e)
        } catch (e: BrokerAccessDeniedException) {
            unavailable("상하한가", e)
        }

    // 수수료 적용 기간은 KST 날짜 기준임
    private fun commissionRateOf(userId: UserId, market: Market): Percent? {
        val today = clock.instant().atZone(Market.KR.zone).toLocalDate()
        return try {
            trading
                .commissionRates(userId)
                .firstOrNull { it.market == market && it.isEffectiveOn(today) }
                ?.rate
        } catch (e: BrokerUnavailableException) {
            unavailable("수수료율", e)
        } catch (e: BrokerAccessDeniedException) {
            unavailable("수수료율", e)
        }
    }

    private fun unavailable(label: String, cause: DomainException): Nothing? {
        log.warn("주문 가능 정보의 {} 을 받지 못해 비워 둠({})", label, cause::class.simpleName)
        return null
    }

    companion object {
        private val log = LoggerFactory.getLogger(OrderTicketService::class.java)
    }
}
