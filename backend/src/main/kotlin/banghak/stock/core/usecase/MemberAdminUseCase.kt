package banghak.stock.core.usecase

import banghak.stock.core.domain.account.Principal
import banghak.stock.core.domain.account.TotpEnrollment
import banghak.stock.core.domain.account.UserSummary
import banghak.stock.core.domain.identity.UserId

/** 회원 관리(admin + step-up). admin 은 자기 자신을 정지하지 못하고 admin 이 0명이 되지 않음. */
interface MemberAdminUseCase {
    fun members(admin: Principal): List<UserSummary>

    /** 남은 자리가 있어야 함. 코드는 이 응답에만 있고 24시간·1회용. */
    fun issueRegistrationCode(admin: Principal): String

    fun suspend(admin: Principal, target: UserId)

    fun resume(admin: Principal, target: UserId)

    /** 그 사용자의 토스 키를 Keychain 에서 지움. 다음 로그인 때 다시 입력. */
    fun deleteTossCredential(admin: Principal, target: UserId)

    /** 트랜잭션 하나로 역할을 맞바꿈. */
    fun transferAdmin(admin: Principal, target: UserId)

    /** 등록 코드로 구성원 가입. 세션 없이 호출됨. TOTP 확인 뒤에야 로그인 가능. */
    fun register(command: RegisterMemberCommand): MemberRegistered

    fun confirmMemberTotp(userId: UserId, code: String)
}

class RegisterMemberCommand(
    val registrationCode: String,
    val displayName: String,
    val password: CharArray,
    val passwordConfirmation: CharArray,
) {
    override fun toString(): String = "RegisterMemberCommand(displayName=$displayName)"
}

data class MemberRegistered(val userId: UserId, val totp: TotpEnrollment)
