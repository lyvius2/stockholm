package banghak.stock.core.domain.account

sealed interface CredentialCheck {
    val isOk: Boolean
        get() = this is Ok

    /** 성공. `detail` 은 값이 아닌 메타(계좌 끝 4자리·모델 수 등). */
    data class Ok(val detail: Map<String, String> = emptyMap()) : CredentialCheck

    /** 키가 틀렸거나 권한이 없음. 사용자가 고칠 수 있는 말로 된 사유. */
    data class Rejected(val reason: String) : CredentialCheck

    /** 상대 서비스에 닿지 못함. 키의 옳고 그름은 모름. */
    data class Unreachable(val reason: String) : CredentialCheck
}

enum class CredentialStatus {
    UNREGISTERED,
    VERIFIED,
    REJECTED,
    UNREACHABLE,
}

/**
 * 자격의 메타데이터. DB 에 두는 것은 이것뿐이고 값은 Keychain 에만 있음.
 *
 * @property last4 값의 끝 4자리. 비밀이 아닌 종류(주소·이메일)는 값 전체가 detail 에 있음
 */
data class CredentialMeta(
    val kind: CredentialKind,
    val userId: banghak.stock.core.domain.identity.UserId?,
    val status: CredentialStatus,
    val last4: String?,
    val verifiedAt: java.time.Instant?,
    val statusDetail: String?,
    val detail: Map<String, String> = emptyMap(),
) {
    val scope: SecretScope
        get() = kind.scope
}
