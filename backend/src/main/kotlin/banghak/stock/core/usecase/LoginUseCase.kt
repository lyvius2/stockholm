package banghak.stock.core.usecase

import banghak.stock.core.domain.account.Principal
import banghak.stock.core.domain.account.Session
import banghak.stock.core.domain.account.UserSummary
import banghak.stock.core.domain.identity.UserId

/** 로그인 모달과 세션 인증. 비밀번호 → TOTP(또는 복구 코드) 순서. */
interface LoginUseCase {
    /** 로그인 모달의 사용자 선택 목록. */
    fun users(): List<UserSummary>

    /** 성공 시 토큰 원문은 이 응답에만 있음. 실패 사유는 밝히지 않고 5회 실패면 15분 잠금. */
    fun login(command: LoginCommand): SessionIssued

    /** 토큰으로 주체를 찾음. 없거나 만료·폐기면 null. */
    fun authenticate(token: String): Principal?

    fun logout(principal: Principal)

    /** 민감 명령 전 TOTP 재인증. 5분 유효. */
    fun stepUp(principal: Principal, totpCode: String): Session
}

class LoginCommand(
    val userId: UserId,
    val password: CharArray,
    val totpCode: String?,
    val recoveryCode: String?,
) {
    override fun toString(): String = "LoginCommand(userId=$userId)"
}

data class SessionIssued(val token: String, val session: Session, val user: UserSummary) {
    override fun toString(): String = "SessionIssued(user=${user.userId})"
}
