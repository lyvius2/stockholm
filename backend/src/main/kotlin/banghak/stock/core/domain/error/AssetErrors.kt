package banghak.stock.core.domain.error

/**
 * 금융결제원 동의(사용자 토큰)가 없거나 만료됨.
 * 화면은 동의 흐름을 먼저 띄움.
 */
class ConsentRequiredException(message: String) : DomainException(message)

/**
 * 자산 조회 기관에 닿지 못하거나 응답을 읽지 못함.
 * 화면은 직전 결과가 있으면 그것과 실패 시각을 보임.
 */
class AssetUnavailableException(message: String, cause: Throwable? = null) :
    DomainException(message, cause)
