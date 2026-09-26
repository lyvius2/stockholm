package banghak.stock.engine.adapter.out.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "installation")
class InstallationEntity(
    @Id @Column(name = "installation_id") val installationId: String,
    @Column(name = "setup_state", nullable = false) var setupState: String,
    @Column(name = "admin_user_id") var adminUserId: String?,
    @Column(name = "llm_preset") var llmPreset: String?,
    @Column(name = "last_public_ip") var lastPublicIp: String?,
    @Column(name = "stock_master_synced_at") var stockMasterSyncedAt: Instant?,
    @Column(name = "last_login_user_id") var lastLoginUserId: String?,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
)
