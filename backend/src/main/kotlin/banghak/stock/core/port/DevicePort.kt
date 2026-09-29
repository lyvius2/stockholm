package banghak.stock.core.port

import banghak.stock.core.domain.account.Device
import banghak.stock.core.domain.identity.DeviceId

/**
 * 디바이스 등록부.
 * 단독 모드에서는 이 설치 하나뿐임.
 */
interface DevicePort {
    fun findAll(): List<Device>

    fun save(device: Device)

    /**
     * 이 설치의 디바이스.
     * 단독 모드에서는 폐기되지 않은 디바이스가 하나뿐임.
     * 여러 디바이스(8단계)에서는 lease 보유 디바이스로 바꿀 것.
     */
    fun localDevice(): DeviceId =
        findAll().firstOrNull { it.revokedAt == null }?.deviceId ?: error("이 설치의 디바이스가 등록되지 않음")
}
