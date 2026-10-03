package banghak.stock.core.port

import banghak.stock.core.domain.account.UserSetting
import banghak.stock.core.domain.account.UserSettingKey
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.UserId

/**
 * 사용자 설정(`user_setting`).
 * 설정 이벤트(`UserSettingChanged`)의 projection 이며 모든 조회는 사용자 범위 안에서만 동작함.
 */
interface UserSettingsPort {
    fun find(userId: UserId, key: UserSettingKey): UserSetting?

    /** 같은 키는 덮어씀. */
    fun save(setting: UserSetting, updatedBy: DeviceId)
}
