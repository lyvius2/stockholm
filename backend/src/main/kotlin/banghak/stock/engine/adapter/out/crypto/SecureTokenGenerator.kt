package banghak.stock.engine.adapter.out.crypto

import banghak.stock.core.port.TokenGeneratorPort
import banghak.stock.shared.config.RuntimeProfiles
import banghak.stock.shared.crypto.Base32
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile(RuntimeProfiles.ENGINE)
class SecureTokenGenerator : TokenGeneratorPort {
    private val random = SecureRandom()

    override fun newToken(): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))

    /** base32 10자(50비트)를 5자마다 끊어 적음. 예: `K7QX2-9MZP4`. */
    override fun newHumanCode(): String {
        val raw =
            Base32.encode(ByteArray(HUMAN_CODE_BYTES).also(random::nextBytes))
                .take(HUMAN_CODE_CHARS)
        return raw.chunked(HUMAN_CODE_CHARS / 2).joinToString("-")
    }

    override fun hash(token: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))

    companion object {
        private const val TOKEN_BYTES = 32
        private const val HUMAN_CODE_BYTES = 7
        private const val HUMAN_CODE_CHARS = 10
    }
}
