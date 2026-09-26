package banghak.stock.core.domain.identity

import banghak.stock.core.domain.error.InvalidValue

/** 사용자 식별자. `u_` 접두 + ULID. 모든 조회·명령은 이 값의 범위 안에서만 동작함. */
@JvmInline
value class UserId(val value: String) {
    init {
        if (!PATTERN.matches(value)) throw InvalidValue("UserId 형식이 아님: '$value'")
    }

    override fun toString(): String = value

    companion object {
        private const val PREFIX = "u_"
        private val PATTERN = Regex("u_[0-9A-HJKMNP-TV-Z]{26}")

        fun from(ulid: Ulid): UserId = UserId(PREFIX + ulid.value)
    }
}
