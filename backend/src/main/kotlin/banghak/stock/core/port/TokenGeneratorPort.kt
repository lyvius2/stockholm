package banghak.stock.core.port

/** 세션 토큰·복구 코드·등록 코드용 난수와 해시. 구현은 SecureRandom + SHA-256. */
interface TokenGeneratorPort {
    /** URL 에 안전한 무작위 토큰. */
    fun newToken(): String

    /** 사람이 읽고 옮겨 적는 짧은 코드(base32 10자, 4자마다 구분). */
    fun newHumanCode(): String

    fun hash(token: String): String
}
