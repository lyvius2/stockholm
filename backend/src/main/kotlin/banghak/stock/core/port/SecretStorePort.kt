package banghak.stock.core.port

import banghak.stock.core.domain.account.SecretKey
import banghak.stock.core.domain.account.SecretValue

interface SecretStorePort {
    fun put(key: SecretKey, value: SecretValue)

    fun exists(key: SecretKey): Boolean

    fun delete(key: SecretKey)
}
