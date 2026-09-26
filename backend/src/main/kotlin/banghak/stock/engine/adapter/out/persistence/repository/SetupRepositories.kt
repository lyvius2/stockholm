package banghak.stock.engine.adapter.out.persistence.repository

import banghak.stock.engine.adapter.out.persistence.entity.AppUserEntity
import banghak.stock.engine.adapter.out.persistence.entity.AuditLogEntity
import banghak.stock.engine.adapter.out.persistence.entity.CredentialMetaEntity
import banghak.stock.engine.adapter.out.persistence.entity.InstallationEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface InstallationRepository : JpaRepository<InstallationEntity, String>

interface AppUserRepository : JpaRepository<AppUserEntity, String>

interface CredentialMetaRepository : JpaRepository<CredentialMetaEntity, String> {
    @Query("select c from CredentialMetaEntity c where c.userId is null")
    fun findShared(): List<CredentialMetaEntity>

    @Query("select c from CredentialMetaEntity c where c.userId = :userId")
    fun findByUserId(@Param("userId") userId: String): List<CredentialMetaEntity>

    @Query("select c from CredentialMetaEntity c where c.kind = :kind and c.userId is null")
    fun findSharedByKind(@Param("kind") kind: String): CredentialMetaEntity?

    @Query("select c from CredentialMetaEntity c where c.kind = :kind and c.userId = :userId")
    fun findByKindAndUserId(
        @Param("kind") kind: String,
        @Param("userId") userId: String,
    ): CredentialMetaEntity?
}

interface AuditLogRepository : JpaRepository<AuditLogEntity, String>
