package banghak.stock.core.port

/** 비밀번호 해시(Argon2id). 원문은 `CharArray` 로만 받고 호출자가 지움. */
interface PasswordHasherPort {
    fun hash(password: CharArray): String

    fun matches(password: CharArray, hash: String): Boolean
}
