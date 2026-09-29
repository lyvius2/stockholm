package banghak.stock.core.domain.error

import java.math.BigDecimal

/**
 * 증권사에 닿지 못함(네트워크·5xx·서킷 열림).
 * 호출자는 재시도·알림.
 * 주문은 내지 않음.
 */
class BrokerUnavailableException(message: String, cause: Throwable? = null) :
    DomainException(message, cause)

/**
 * 증권사가 주문·정정·취소를 거부함.
 * 사용자에게 사유를 그대로 보임.
 * 호가 단위 불일치면 토스가 준 `tickSize`·`nearestPrices` 를 함께 실어 화면이 제안 칩으로 보임.
 * 값의 통화는 주문 시장의 통화임(error 패키지가 money 에 기대지 않도록 숫자만 둠).
 */
class OrderRejectedException(
    message: String,
    val tickSize: BigDecimal? = null,
    val nearestPrices: List<BigDecimal> = emptyList(),
) : DomainException(message)

/**
 * 주문 요청의 결과를 모름(타임아웃).
 * 재시도 전에 반드시 증권사에 조회해 확정함.
 */
class OrderResultUnknownException(message: String, cause: Throwable? = null) :
    DomainException(message, cause)

/**
 * 시세·캔들·환율을 받지 못함.
 * 조회는 마지막 캐시 + 지연 표시, 주문 경로는 멈춤.
 */
class MarketDataUnavailableException(message: String, cause: Throwable? = null) :
    DomainException(message, cause)

/**
 * 가드레일 위반으로 주문을 내지 않음.
 * 화면은 [violations] 목록을 그대로 보임(규칙 이름과 사유).
 */
class GuardrailViolationException(val violations: List<String>) :
    DomainException("가드레일 위반: " + violations.joinToString("; "))

/**
 * 증권사가 요청을 거부함(허용 IP 미등록, 키 거부).
 * 재시도로 풀리지 않으므로 사용자에게 IP 등록·키 확인을 안내함.
 */
class BrokerAccessDeniedException(message: String) : DomainException(message)
