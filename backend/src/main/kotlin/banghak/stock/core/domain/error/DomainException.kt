package banghak.stock.core.domain.error

/** core가 던지는 예외의 뿌리. 분류 기준은 호출자가 무엇을 할 수 있는가임. */
abstract class DomainException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** 값 객체 생성 실패. 입력이 잘못된 것이므로 재시도하지 않음. */
class InvalidValue(message: String) : DomainException(message)

/** 통화가 다른 금액끼리 연산함. 국내·해외 한도를 섞는 코드 오류의 신호. */
class CurrencyMismatch(message: String) : DomainException(message)
