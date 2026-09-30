package banghak.stock.core.usecase

import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.trading.OrderTicket

/** 주문 모달에 보일 주문 가능 정보(매수 가능 금액·판매 가능 수량·상하한가·수수료율). */
interface LookupOrderTicketUseCase {
    /**
     * 본인 계좌 기준으로 조회함.
     *
     * @param userId 조회하는 사용자
     * @param symbol 주문하려는 종목
     * @return 주문 가능 정보. 상하한가·수수료율은 받지 못하면 비어 있음
     * @throws banghak.stock.core.domain.error.BrokerUnavailableException 계좌 정보를 받지 못하면 발생함
     */
    fun ticket(userId: UserId, symbol: Symbol): OrderTicket
}
