package banghak.stock.core.domain.portfolio

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.trading.OrderOrigin
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class BuyOriginTest {
    @Test
    @DisplayName("매수 주문의 출처가 lot 의 출처가 되고, 자동 매도는 lot 을 만들지 않음")
    fun mapsOrderOrigin() {
        assertThat(BuyOrigin.of(OrderOrigin.MANUAL)).isEqualTo(BuyOrigin.MANUAL)
        assertThat(BuyOrigin.of(OrderOrigin.AI_RECOMMENDED)).isEqualTo(BuyOrigin.AI_RECOMMENDED)
        assertThat(BuyOrigin.of(OrderOrigin.AUTO_BUY)).isEqualTo(BuyOrigin.AUTO_BUY)
        assertThatThrownBy { BuyOrigin.of(OrderOrigin.AUTO_SELL) }
            .isInstanceOf(InvalidValueException::class.java)
    }
}
