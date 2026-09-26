package banghak.stock.core.domain.account

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.UserId

enum class SecretScope {
    SHARED,
    USER,
}

data class SecretKey(val scope: SecretScope, val name: String, val userId: UserId? = null) {
    init {
        if (!NAME_PATTERN.matches(name)) throw InvalidValueException("비밀값 이름 형식이 아님: '$name'")
        if (scope == SecretScope.USER && userId == null)
            throw InvalidValueException("개인 키에는 userId 가 필요함: $name")
        if (scope == SecretScope.SHARED && userId != null)
            throw InvalidValueException("공유 키에는 userId 가 없어야 함: $name")
    }

    val path: String
        get() =
            if (scope == SecretScope.SHARED) "$ROOT/shared/$name" else "$ROOT/user/$userId/$name"

    override fun toString(): String = path

    companion object {
        private const val ROOT = "stockholm"
        private val NAME_PATTERN = Regex("[A-Z][A-Z0-9_]{0,63}")

        fun shared(name: String): SecretKey = SecretKey(SecretScope.SHARED, name)

        fun user(userId: UserId, name: String): SecretKey =
            SecretKey(SecretScope.USER, name, userId)
    }
}
