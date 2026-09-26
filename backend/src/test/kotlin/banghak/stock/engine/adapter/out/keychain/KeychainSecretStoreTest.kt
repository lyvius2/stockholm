package banghak.stock.engine.adapter.out.keychain

import banghak.stock.core.port.SecretStoreContractTest
import banghak.stock.core.port.SecretStorePort
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.condition.EnabledIf
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * 실제 macOS Keychain 으로 계약을 검증함. 항목은 임시 계정 이름 아래 만들고 테스트 뒤 지움. macOS 가 아니거나 `security` 가 없으면
 * 건너뜀(CI).
 */
@EnabledOnOs(OS.MAC)
@EnabledIf("securityCommandExists")
class KeychainSecretStoreTest : SecretStoreContractTest() {
    override fun newStore(): SecretStorePort =
        KeychainSecretStore(
            KeychainProperties(account = "stockholm-test-" + UUID.randomUUID().toString().take(8))
        )

    companion object {
        @JvmStatic
        fun securityCommandExists(): Boolean = Files.isExecutable(Path.of("/usr/bin/security"))
    }
}
