package banghak.stock.engine.adapter.out.persistence.entity

import banghak.stock.core.domain.eventlog.SyncScope
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** `event_log` 한 행. 도메인 `EventEnvelope`와는 `JpaEventStore`가 변환함. */
@Entity
@Table(name = "event_log")
class EventLogEntity(
    @Column(name = "user_id", nullable = false) val userId: String,
    @Column(name = "device_id", nullable = false) val deviceId: String,
    @Column(nullable = false) val seq: Long,
    @Column(name = "occurred_at", nullable = false) val occurredAt: Instant,
    @Column(nullable = false) val type: String,
    @Column(name = "payload_version", nullable = false) val payloadVersion: Int,
    @Column(name = "payload_json", nullable = false) val payloadJson: String,
    @Column(name = "sync_scope", nullable = false)
    @Enumerated(EnumType.STRING)
    val syncScope: SyncScope,
    @Column(name = "received_at") val receivedAt: Instant?,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_no")
    var eventNo: Long? = null
}
