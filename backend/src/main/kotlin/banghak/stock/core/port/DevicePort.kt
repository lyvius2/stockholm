package banghak.stock.core.port

import banghak.stock.core.domain.account.Device

/** 디바이스 등록부. 단독 모드에서는 이 설치 하나뿐임. */
interface DevicePort {
    fun findAll(): List<Device>

    fun save(device: Device)
}
