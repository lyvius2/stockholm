package banghak.stock.engine.adapter.out.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** 자격 메타. 값은 없음(Keychain). `user_id` 가 null 이면 공유 키. */
@Entity
@Table(name = "credential_meta")
class CredentialMetaEntity(
    @Id @Column(name = "credential_id") val credentialId: String,
    @Column(nullable = false) val kind: String,
    @Column(nullable = false) val scope: String,
    @Column(name = "user_id") val userId: String?,
    @Column var last4: String?,
    @Column(nullable = false) var status: String,
    @Column(name = "status_detail") var statusDetail: String?,
    @Column(name = "verified_at") var verifiedAt: Instant?,
    @Column(name = "response_ms") var responseMs: Int?,
    @Column(name = "extra_json") var extraJson: String?,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
)
