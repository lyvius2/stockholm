package banghak.stock.core.domain.identity

import banghak.stock.core.domain.error.InvalidValueException
import java.time.Instant

/** ULID. 시각 48비트 + 엔트로피 80비트를 Crockford base32 26자로 적음. 난수·시계는 밖에서 받으므로 core는 순수하고 결정적임. */
@JvmInline
value class Ulid private constructor(val value: String) : Comparable<Ulid> {
    fun timestamp(): Instant {
        var millis = 0L
        for (index in 0 until TIME_CHARS) millis =
            (millis shl BITS_PER_CHAR) or DECODE.getValue(value[index]).toLong()
        return Instant.ofEpochMilli(millis)
    }

    override fun compareTo(other: Ulid): Int = value.compareTo(other.value)

    override fun toString(): String = value

    companion object {
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private const val BITS_PER_CHAR = 5
        private const val TIME_CHARS = 10
        private const val ENTROPY_BYTES = 10
        private const val LENGTH = 26
        private val DECODE: Map<Char, Int> =
            ALPHABET.withIndex().associate { (index, char) -> char to index }
        // 48비트 시각을 10자(50비트)에 담으므로 첫 글자는 상위 2비트가 0인 0~7뿐임
        private val PATTERN = Regex("[0-7][0-9A-HJKMNP-TV-Z]{25}")
        private const val MAX_TIME_MILLIS = (1L shl 48) - 1

        fun of(time: Instant, entropy: ByteArray): Ulid {
            if (entropy.size != ENTROPY_BYTES)
                throw InvalidValueException("ULID 엔트로피는 ${ENTROPY_BYTES}바이트여야 함: ${entropy.size}")
            var millis = time.toEpochMilli()
            if (millis < 0 || millis > MAX_TIME_MILLIS)
                throw InvalidValueException("ULID 시각은 0..2^48-1 밀리초여야 함: $millis")
            val bits = StringBuilder(LENGTH)
            val timeChars = CharArray(TIME_CHARS)
            for (index in TIME_CHARS - 1 downTo 0) {
                timeChars[index] = ALPHABET[(millis and 0x1F).toInt()]
                millis = millis ushr BITS_PER_CHAR
            }
            bits.append(timeChars)
            bits.append(encodeEntropy(entropy))
            return Ulid(bits.toString())
        }

        fun parse(value: String): Ulid {
            if (!PATTERN.matches(value)) throw InvalidValueException("ULID 형식이 아님: '$value'")
            return Ulid(value)
        }

        // 80비트를 5비트씩 16자로 자름
        private fun encodeEntropy(entropy: ByteArray): String {
            var accumulator = 0L
            var bitCount = 0
            val out = StringBuilder(LENGTH - TIME_CHARS)
            for (byte in entropy) {
                accumulator = (accumulator shl Byte.SIZE_BITS) or (byte.toLong() and 0xFF)
                bitCount += Byte.SIZE_BITS
                while (bitCount >= BITS_PER_CHAR) {
                    bitCount -= BITS_PER_CHAR
                    out.append(ALPHABET[((accumulator ushr bitCount) and 0x1F).toInt()])
                }
            }
            return out.toString()
        }
    }
}
