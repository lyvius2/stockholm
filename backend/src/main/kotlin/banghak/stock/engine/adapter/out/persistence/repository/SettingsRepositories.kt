package banghak.stock.engine.adapter.out.persistence.repository

import banghak.stock.engine.adapter.out.persistence.entity.UserSettingEntity
import banghak.stock.engine.adapter.out.persistence.entity.UserSettingKey
import org.springframework.data.jpa.repository.JpaRepository

/** 복합 키(사용자, 키)로만 읽고 씀. */
interface UserSettingRepository : JpaRepository<UserSettingEntity, UserSettingKey>
