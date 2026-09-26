package banghak.stock.engine.adapter.out.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "app_user")
class AppUserEntity(
    @Id @Column(name = "user_id") val userId: String,
    @Column(nullable = false) var role: String,
    @Column(name = "display_name", nullable = false) var displayName: String,
    @Column(name = "password_hash", nullable = false) var passwordHash: String,
    @Column(name = "totp_enrolled_at") var totpEnrolledAt: Instant?,
    @Column(name = "totp_last_counter", nullable = false) var totpLastCounter: Long,
    @Column(name = "failed_logins", nullable = false) var failedLogins: Int,
    @Column(name = "locked_until") var lockedUntil: Instant?,
    @Column(nullable = false) var status: String,
    @Column(name = "toss_key_decision", nullable = false) var tossKeyDecision: String,
    @Column(name = "extra_key_wrap", nullable = false) var extraKeyWrap: Boolean,
    @Column(name = "auto_stop_on_logout", nullable = false) var autoStopOnLogout: Boolean,
    @Column var email: String?,
    @Column(name = "slack_user_id") var slackUserId: String?,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
)
