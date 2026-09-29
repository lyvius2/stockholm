package banghak.stock.core.domain.guardrail

import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.Money
import java.time.Duration

/**
 * 가드레일 상수.
 * 설정은 이 값을 낮출 수만 있음.
 */
object GuardrailDefaults {
    /** 이보다 오래된 계좌 스냅샷으로는 주문하지 않음(fail-safe). */
    val SNAPSHOT_MAX_AGE: Duration = Duration.ofSeconds(30)

    /**
     * 토스: 1억원 이상 주문은 `confirmHighValueOrder=true` 가 필수임.
     * 화면은 확인 창을 띄움.
     */
    val HIGH_VALUE_ORDER: Money = Money.of(100_000_000L, Currency.KRW)

    /** 토스: 30억원을 넘는 주문은 낼 수 없음. */
    val MAX_ORDER_VALUE: Money = Money.of(3_000_000_000L, Currency.KRW)

    /** 토스: 시장가(소수점 매도·금액 매수)는 정규장 시작부터 종료 1시간 전까지만 접수함. */
    val MARKET_ORDER_CLOSING_MARGIN: Duration = Duration.ofHours(1)

    /** 이보다 오래된 현재가로는 주문 금액을 판정하지 않음. */
    val QUOTE_MAX_AGE: Duration = Duration.ofSeconds(30)

    /**
     * 이보다 오래된 환율로는 원화 환산을 하지 않음.
     * 토스 환율은 약 5분마다 바뀜.
     */
    val FX_MAX_AGE: Duration = Duration.ofMinutes(10)
}
