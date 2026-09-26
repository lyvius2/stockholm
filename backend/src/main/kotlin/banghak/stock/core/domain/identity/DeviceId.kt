package banghak.stock.core.domain.identity

import banghak.stock.core.domain.error.InvalidValueException

/** 디바이스(설치) 식별자. `d_` 접두 + ULID. 이벤트 seq와 lease의 단위임. */
@JvmInline
value class DeviceId(val value: String) {
    init {
        if (!PATTERN.matches(value)) throw InvalidValueException("DeviceId 형식이 아님: '$value'")
    }

    override fun toString(): String = value

    companion object {
        private const val PREFIX = "d_"
        private val PATTERN = Regex("d_[0-7][0-9A-HJKMNP-TV-Z]{25}")

        fun from(ulid: Ulid): DeviceId = DeviceId(PREFIX + ulid.value)
    }
}
