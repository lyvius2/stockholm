package banghak.stock.core.domain.trading

import banghak.stock.core.domain.automation.StrategyId
import banghak.stock.core.domain.error.InvalidValue
import banghak.stock.core.domain.identity.Ulid
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Symbol
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate

/**
 * 주문 멱등 키. 토스 제약: 최대 36자, 영숫자·`-`·`_`, 유효기간 10분. 10분이 지나면 같은 키도 새 주문으로 처리됨. 그래서 재시도는 10분 안에서만 멱등을
 * 믿고, 그 밖에서는 반드시 조회 후 결정함.
 */
@JvmInline
value class ClientOrderId(val value: String) {
    init {
        if (!PATTERN.matches(value))
            throw InvalidValue("clientOrderId 는 1~36자 [A-Za-z0-9_-] 여야 함: '$value'")
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("[A-Za-z0-9_-]{1,36}")
        private const val DETERMINISTIC_LENGTH = 26
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        /** 자동 주문 키. (사용자, 전략, 종목, 거래일, 회차)에서 결정적으로 만들어 이중 주문을 막음. SHA-256 → base32 앞 26자. */
        fun deterministic(
            user: UserId,
            strategy: StrategyId,
            symbol: Symbol,
            tradingDay: LocalDate,
            sequence: Int,
        ): ClientOrderId {
            val material = "$user|$strategy|$symbol|$tradingDay|$sequence"
            val digest =
                MessageDigest.getInstance("SHA-256")
                    .digest(material.toByteArray(StandardCharsets.UTF_8))
            return ClientOrderId(base32(digest).take(DETERMINISTIC_LENGTH))
        }

        /** 수동 주문 키. ULID 26자를 그대로 씀. */
        fun from(ulid: Ulid): ClientOrderId = ClientOrderId(ulid.value)

        private fun base32(bytes: ByteArray): String {
            val out = StringBuilder()
            var accumulator = 0
            var bitCount = 0
            for (byte in bytes) {
                accumulator = (accumulator shl Byte.SIZE_BITS) or (byte.toInt() and 0xFF)
                bitCount += Byte.SIZE_BITS
                while (bitCount >= 5) {
                    bitCount -= 5
                    out.append(ALPHABET[(accumulator ushr bitCount) and 0x1F])
                }
            }
            return out.toString()
        }
    }
}
