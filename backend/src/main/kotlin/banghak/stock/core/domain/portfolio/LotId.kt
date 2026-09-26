package banghak.stock.core.domain.portfolio

import banghak.stock.core.domain.error.InvalidValue
import banghak.stock.core.domain.identity.Ulid

/** 매수 건(lot) 식별자. ULID. */
@JvmInline
value class LotId(val value: String) {
    init {
        if (!PATTERN.matches(value)) throw InvalidValue("LotId 형식이 아님: '$value'")
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("[0-9A-HJKMNP-TV-Z]{26}")

        fun from(ulid: Ulid): LotId = LotId(ulid.value)
    }
}
