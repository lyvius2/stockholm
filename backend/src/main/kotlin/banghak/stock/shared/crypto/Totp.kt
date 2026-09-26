package banghak.stock.shared.crypto

import java.nio.ByteBuffer
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** RFC 6238 TOTP(HMAC-SHA1, 30초, 6자리). Google Authenticator 등과 호환됨. 시계는 밖에서 받아 고정 시계로 테스트함. */
object Totp {
    const val DIGITS = 6
    const val STEP_SECONDS = 30L
    const val SECRET_BYTES = 20

    /** 앞뒤로 허용하는 구간 수(±30초). */
    const val WINDOW = 1

    fun counterAt(time: Instant): Long = Math.floorDiv(time.epochSecond, STEP_SECONDS)

    fun generate(secret: ByteArray, counter: Long): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val hash = mac.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(counter).array())
        val offset = hash.last().toInt() and 0x0F
        val binary =
            ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)
        return (binary % MODULUS).toString().padStart(DIGITS, '0')
    }

    /** 맞는 구간의 카운터, 없으면 null. 같은 카운터의 재사용 거부는 호출자가 함. */
    fun matchingCounter(secret: ByteArray, code: String, now: Instant): Long? {
        if (!code.matches(CODE_PATTERN)) return null
        val current = counterAt(now)
        for (delta in -WINDOW..WINDOW) {
            val counter = current + delta
            if (constantTimeEquals(generate(secret, counter), code)) return counter
        }
        return null
    }

    fun otpauthUri(issuer: String, accountLabel: String, secret: ByteArray): String =
        "otpauth://totp/${encode(issuer)}:${encode(accountLabel)}?secret=${Base32.encode(secret)}&issuer=${encode(issuer)}&algorithm=SHA1&digits=$DIGITS&period=$STEP_SECONDS"

    private fun encode(text: String): String =
        java.net.URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")

    private fun constantTimeEquals(a: String, b: String): Boolean =
        java.security.MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private const val MODULUS = 1_000_000
    private val CODE_PATTERN = Regex("[0-9]{6}")
}
