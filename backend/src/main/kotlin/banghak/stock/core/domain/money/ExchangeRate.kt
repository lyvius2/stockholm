package banghak.stock.core.domain.money

import banghak.stock.core.domain.error.InvalidValue
import java.math.BigDecimal
import java.time.Instant

/** 환율. 시점을 함께 가짐. 해외 노출액은 "매수 시점 환율"로 환산하는 것이 확정 규칙임. */
data class ExchangeRate(
    val from: Currency,
    val to: Currency,
    val rate: BigDecimal,
    val asOf: Instant,
) {
    init {
        if (from == to) throw InvalidValue("같은 통화 사이의 환율은 없음: $from")
        if (rate.signum() <= 0) throw InvalidValue("환율은 양수여야 함: $rate")
    }
}
