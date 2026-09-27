package banghak.stock.engine.adapter.out.persistence.repository

import banghak.stock.engine.adapter.out.persistence.entity.DeviceEntity
import banghak.stock.engine.adapter.out.persistence.entity.RecoveryCodeEntity
import banghak.stock.engine.adapter.out.persistence.entity.RegistrationCodeEntity
import banghak.stock.engine.adapter.out.persistence.entity.UserSessionEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface UserSessionRepository : JpaRepository<UserSessionEntity, String> {
    @Query("select s from UserSessionEntity s where s.userId = :userId and s.revokedAt is null")
    fun findOpenByUserId(@Param("userId") userId: String): List<UserSessionEntity>
}

interface RecoveryCodeRepository : JpaRepository<RecoveryCodeEntity, String> {
    @Query("select r from RecoveryCodeEntity r where r.userId = :userId and r.usedAt is null")
    fun findUsableByUserId(@Param("userId") userId: String): List<RecoveryCodeEntity>

    @Modifying
    @Query("delete from RecoveryCodeEntity r where r.userId = :userId")
    fun deleteAllByUserId(@Param("userId") userId: String)
}

interface RegistrationCodeRepository : JpaRepository<RegistrationCodeEntity, String> {
    @Query("select r from RegistrationCodeEntity r where r.usedAt is null")
    fun findUnused(): List<RegistrationCodeEntity>
}

interface DeviceRepository : JpaRepository<DeviceEntity, String>
