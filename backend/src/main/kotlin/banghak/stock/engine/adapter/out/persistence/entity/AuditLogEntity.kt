package banghak.stock.engine.adapter.out.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "audit_log")
class AuditLogEntity(
    @Id @Column(name = "audit_id") val auditId: String,
    @Column(name = "occurred_at", nullable = false) val occurredAt: Instant,
    @Column(name = "user_id") val userId: String?,
    @Column(name = "device_id") val deviceId: String?,
    @Column(nullable = false) val action: String,
    @Column val target: String?,
    @Column(nullable = false) val result: String,
    @Column(name = "detail_json") val detailJson: String?,
)
