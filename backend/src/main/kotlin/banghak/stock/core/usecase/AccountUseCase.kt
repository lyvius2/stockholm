package banghak.stock.core.usecase

import banghak.stock.core.domain.account.Principal
import banghak.stock.core.domain.account.TotpEnrollment
import banghak.stock.core.domain.account.UserSummary

/** 본인 계정(`/me`). 본인 세션만 부름. */
interface AccountUseCase {
    fun me(principal: Principal): UserSummary

    /** 현재 비밀번호가 맞아야 바꿈. */
    fun changePassword(principal: Principal, command: ChangePasswordCommand)

    /** 현재 TOTP 코드 확인 → 새 대기 시드. 옛 시드는 [confirmTotpReenrollment] 전까지 유효함. */
    fun startTotpReenrollment(principal: Principal, currentCode: String): TotpEnrollment

    fun confirmTotpReenrollment(principal: Principal, newCode: String)

    /** step-up 필요. 옛 코드는 전부 무효가 되고 새 8개는 이 응답에만 있음. */
    fun reissueRecoveryCodes(principal: Principal): List<String>
}

class ChangePasswordCommand(
    val current: CharArray,
    val new: CharArray,
    val confirmation: CharArray,
) {
    override fun toString(): String = "ChangePasswordCommand"
}
