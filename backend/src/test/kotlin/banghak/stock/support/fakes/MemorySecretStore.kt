package banghak.stock.support.fakes

import banghak.stock.core.domain.account.SecretKey
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.port.SecretStorePort
import banghak.stock.engine.adapter.out.keychain.SecretReader

/** 테스트용 메모리 비밀 저장소. Keychain 구현과 같은 계약(`SecretStoreContractTest`)을 지킴. */
class MemorySecretStore : SecretStorePort, SecretReader {
    private val values = mutableMapOf<SecretKey, CharArray>()

    override fun put(key: SecretKey, value: SecretValue) {
        values[key] = value.reveal()
    }

    override fun exists(key: SecretKey): Boolean = key in values

    override fun delete(key: SecretKey) {
        values.remove(key)
    }

    override fun read(key: SecretKey): SecretValue? = values[key]?.let { SecretValue(it) }
}
