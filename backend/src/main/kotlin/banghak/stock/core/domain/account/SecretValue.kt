package banghak.stock.core.domain.account

import banghak.stock.core.domain.error.InvalidValueException

/**
 * 비밀값. 문자열로 들고 다니지 않게 감싸며 `toString`·`equals` 로 값이 새지 않음. 다 쓴 뒤 [wipe] 로 지움. 값을 꺼내는 [reveal] 은 출력
 * 어댑터 안에서만 부름(ArchUnit).
 */
class SecretValue(chars: CharArray) {
    private val chars: CharArray = chars.copyOf()

    init {
        if (chars.isEmpty() || chars.all { it.isWhitespace() })
            throw InvalidValueException("비밀값이 비어 있음")
    }

    val length: Int
        get() = chars.size

    /** 끝 4자리. 화면·메타데이터 표시용. 4자보다 짧으면 전부 가림. */
    val last4: String
        get() = if (chars.size >= 4) String(chars, chars.size - 4, 4) else "****"

    fun reveal(): CharArray = chars.copyOf()

    fun wipe() = chars.fill(Char.MIN_VALUE)

    override fun toString(): String = "SecretValue(****)"

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    companion object {
        fun of(text: String): SecretValue = SecretValue(text.toCharArray())
    }
}
