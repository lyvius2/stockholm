package banghak.stock.engine.adapter.out.persistence

import banghak.stock.core.domain.account.UserSetting
import banghak.stock.core.domain.account.UserSettingKey
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.port.UserSettingsPort
import banghak.stock.engine.adapter.out.persistence.entity.UserSettingEntity
import banghak.stock.engine.adapter.out.persistence.repository.UserSettingRepository
import banghak.stock.shared.config.RuntimeProfiles
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
@Profile(RuntimeProfiles.ENGINE)
class JpaUserSettingsStore(private val repository: UserSettingRepository) : UserSettingsPort {
    @Transactional(readOnly = true)
    override fun find(userId: UserId, key: UserSettingKey): UserSetting? =
        repository.findById(keyOf(userId, key)).orElse(null)?.let {
            UserSetting(userId, key, it.valueJson, it.updatedAt)
        }

    @Transactional
    override fun save(setting: UserSetting, updatedBy: DeviceId) {
        val key = keyOf(setting.userId, setting.key)
        val row = repository.findById(key).orElse(null)
        if (row == null) {
            repository.save(
                UserSettingEntity(key, setting.value, setting.updatedAt, updatedBy.value)
            )
            return
        }
        row.valueJson = setting.value
        row.updatedAt = setting.updatedAt
        row.updatedByDeviceId = updatedBy.value
    }

    private fun keyOf(userId: UserId, key: UserSettingKey) =
        banghak.stock.engine.adapter.out.persistence.entity.UserSettingKey(userId.value, key.name)
}
