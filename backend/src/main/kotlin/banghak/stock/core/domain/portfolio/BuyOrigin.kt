package banghak.stock.core.domain.portfolio

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.trading.OrderOrigin

/**
 * lot의 출처.
 * 손익 분석·자동 매수 한도 계산·학습의 공통 키.
 */
enum class BuyOrigin {
    MANUAL,
    AI_RECOMMENDED,
    AUTO_BUY;

    companion object {
        /**
         * 매수 주문의 출처.
         * 자동 매도는 매수가 아니므로 받지 않음.
         */
        fun of(origin: OrderOrigin): BuyOrigin =
            when (origin) {
                OrderOrigin.MANUAL -> MANUAL
                OrderOrigin.AI_RECOMMENDED -> AI_RECOMMENDED
                OrderOrigin.AUTO_BUY -> AUTO_BUY
                OrderOrigin.AUTO_SELL -> throw InvalidValueException("자동 매도 주문은 lot 을 만들지 않음")
            }
    }
}
