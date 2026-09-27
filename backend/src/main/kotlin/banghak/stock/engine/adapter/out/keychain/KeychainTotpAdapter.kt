package banghak.stock.engine.adapter.out.keychain

import banghak.stock.core.domain.account.SecretKey
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.domain.account.TotpEnrollment
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.port.SecretStorePort
import banghak.stock.core.port.TotpPort
import banghak.stock.shared.config.RuntimeProfiles
import banghak.stock.shared.crypto.Base32
import banghak.stock.shared.crypto.Totp
import banghak.stock.shared.crypto.UlidGenerator
import com.google.zxing.BarcodeFormat
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayOutputStream
import java.time.Instant
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * TOTP 시드를 Keychain 에 두고 여기서만 읽음. 활성 시드 `TOTP_SEED`, 등록 중인 시드 `TOTP_SEED_PENDING`. 확인이 끝나야 활성으로
 * 승격함.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class KeychainTotpAdapter(
    private val secrets: SecretStorePort,
    private val reader: SecretReader,
    private val ulids: UlidGenerator,
) : TotpPort {
    override fun enroll(userId: UserId, accountLabel: String): TotpEnrollment {
        val seed = ulids.randomBytes(Totp.SECRET_BYTES)
        val manualKey = Base32.encode(seed)
        secrets.put(pendingKey(userId), SecretValue.of(manualKey))
        val uri = Totp.otpauthUri(ISSUER, accountLabel, seed)
        seed.fill(0)
        return TotpEnrollment(qrPng(uri), manualKey)
    }

    override fun confirmEnrollment(userId: UserId, code: String, now: Instant): Long? {
        val pending = reader.read(pendingKey(userId)) ?: return null
        val counter = matching(pending, code, now)
        if (counter != null) {
            secrets.put(activeKey(userId), pending)
            secrets.delete(pendingKey(userId))
        }
        pending.wipe()
        return counter
    }

    override fun verify(userId: UserId, code: String, now: Instant): Long? {
        val stored = reader.read(activeKey(userId)) ?: return null
        return matching(stored, code, now).also { stored.wipe() }
    }

    override fun remove(userId: UserId) {
        secrets.delete(activeKey(userId))
        secrets.delete(pendingKey(userId))
    }

    private fun matching(stored: SecretValue, code: String, now: Instant): Long? {
        val seed = Base32.decode(String(stored.reveal()))
        return try {
            Totp.matchingCounter(seed, code, now)
        } finally {
            seed.fill(0)
        }
    }

    private fun activeKey(userId: UserId) = SecretKey.user(userId, SEED_NAME)

    private fun pendingKey(userId: UserId) = SecretKey.user(userId, PENDING_SEED_NAME)

    private fun qrPng(content: String): ByteArray {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE)
        return ByteArrayOutputStream()
            .also { MatrixToImageWriter.writeToStream(matrix, "PNG", it) }
            .toByteArray()
    }

    companion object {
        const val SEED_NAME = "TOTP_SEED"
        const val PENDING_SEED_NAME = "TOTP_SEED_PENDING"
        private const val ISSUER = "Stockholm"
        private const val QR_SIZE = 240
    }
}
