package banghak.stock.engine.adapter.out.keychain

import banghak.stock.core.domain.account.Device
import banghak.stock.core.domain.account.SecretKey
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.port.DevicePort
import banghak.stock.core.port.SecretStorePort
import banghak.stock.shared.config.RuntimeProfiles
import banghak.stock.shared.crypto.UlidGenerator
import java.security.KeyPairGenerator
import java.time.Clock
import java.util.Base64
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 이 설치의 디바이스를 처음 기동할 때 등록함. Ed25519 키쌍을 만들어 개인키는 Keychain, 공개키는 표에 둠. 세션은 이 디바이스에 묶임. 다른 디바이스 등록은
 * 8단계(relay).
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class LocalDeviceRegistrar(
    private val devices: DevicePort,
    private val secrets: SecretStorePort,
    private val ulids: UlidGenerator,
    private val clock: Clock,
) {
    @EventListener(ApplicationReadyEvent::class)
    @Transactional
    fun ensureRegistered() {
        if (devices.findAll().any { it.revokedAt == null }) return
        val keyPair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair()
        val deviceId = DeviceId.from(ulids.next())
        // 디바이스 키는 사용자가 아니라 설치 소유라 공유 범위 항목에 둠
        secrets.put(
            SecretKey.shared(PRIVATE_KEY_NAME),
            SecretValue(Base64.getEncoder().encodeToString(keyPair.private.encoded).toCharArray()),
        )
        devices.save(
            Device(
                deviceId,
                Base64.getEncoder().encodeToString(keyPair.public.encoded),
                DEFAULT_NAME,
                null,
                clock.instant(),
                null,
            )
        )
    }

    companion object {
        private const val ALGORITHM = "Ed25519"
        private const val DEFAULT_NAME = "this-mac"
        const val PRIVATE_KEY_NAME = "DEVICE_PRIVATE_KEY"
    }
}
