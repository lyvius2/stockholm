package banghak.stock.core.domain.account

import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.UserId
import java.time.Instant

/**
 * 이벤트로 표현되지 않는 감사(로그인·키 등록·검증·삭제·마법사).
 * 주문 감사는 이벤트 로그가 맡음.
 */
enum class AuditAction {
    SETUP_ADMIN_CREATED,
    SETUP_TOTP_CONFIRMED,
    SETUP_KEYS_DONE,
    SETUP_TOSS_DECIDED,
    SETUP_COMPLETED,
    KEY_VERIFY,
    KEY_SET,
    KEY_DELETE,
    LOGIN_OK,
    LOGIN_FAIL,
    LOCKED,
    LOGOUT,
    STEP_UP,
    PASSWORD_CHANGED,
    TOTP_REENROLLED,
    RECOVERY_REISSUED,
    RECOVERY_USED,
    REGISTRATION_CODE_ISSUED,
    MEMBER_REGISTERED,
    MEMBER_SUSPENDED,
    MEMBER_RESUMED,
    ADMIN_TRANSFERRED,
    ASSET_CONSENT_STARTED,
    ASSET_CONSENTED,
    ASSET_CONSENT_REVOKED,
    ASSET_VIEWED,
}

/**
 * 감사 한 줄.
 * `detail` 에 키 값·계좌번호·응답 원문을 넣지 않음.
 */
data class AuditEntry(
    val occurredAt: Instant,
    val userId: UserId?,
    val deviceId: DeviceId?,
    val action: AuditAction,
    val target: String?,
    val result: String,
    val detail: Map<String, String> = emptyMap(),
)
