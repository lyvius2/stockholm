package banghak.stock.core.domain.money

import java.math.RoundingMode

/** 반올림 규칙은 이 한곳에서만 정함. */
object RoundingRules {
    /** 금액 반올림. 은행가 반올림으로 누적 편향을 없앰. */
    val MONEY: RoundingMode = RoundingMode.HALF_EVEN

    /** 비율 계산의 소수 자릿수. 0.333333 처럼 6자리. */
    const val RATIO_SCALE = 6
}
