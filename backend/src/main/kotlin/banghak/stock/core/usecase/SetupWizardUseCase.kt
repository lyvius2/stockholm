package banghak.stock.core.usecase

import banghak.stock.core.domain.account.CredentialCheck
import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.LlmPreset
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.domain.account.SetupProgress
import banghak.stock.core.domain.account.TossDecision
import banghak.stock.core.domain.account.TotpEnrollment
import banghak.stock.core.domain.identity.UserId

/**
 * 최초 구동 마법사. 상태 전이는 이 usecase 만 함. ① createAdmin → confirmAdminTotp, ② registerSharedCredential… →
 * finishSharedKeys, ③ registerTossCredential? → decideToss, ④ complete.
 */
interface SetupWizardUseCase {
    fun progress(): SetupProgress

    /** NOT_STARTED 에서만. admin 계정을 만들고 TOTP 등록을 시작함. 상태는 TOTP 확인 뒤에 바뀜. */
    fun createAdmin(command: CreateAdminCommand): AdminCreated

    /** admin 의 첫 TOTP 코드 확인. 맞으면 ADMIN_CREATED. */
    fun confirmAdminTotp(code: String): SetupProgress

    /** 공유 키 검증 후 성공한 값만 저장. ADMIN_CREATED 이후 COMPLETE 전까지. */
    fun registerSharedCredential(
        kind: CredentialKind,
        fields: Map<String, SecretValue>,
    ): CredentialCheck

    /** LLM 1개 이상 + DART 가 검증되어야 SHARED_KEYS_DONE. */
    fun finishSharedKeys(preset: LlmPreset): SetupProgress

    /** admin 본인의 토스 키 검증·저장. SHARED_KEYS_DONE 이후. */
    fun registerTossCredential(fields: Map<String, SecretValue>): CredentialCheck

    /** REGISTERED 는 토스 키가 검증되어 있어야 함. LATER 는 조회 제한 모드. → TOSS_DECIDED */
    fun decideToss(decision: TossDecision): SetupProgress

    /** TOSS_DECIDED → COMPLETE. 이후 마법사 API 는 닫힘. */
    fun complete(): SetupProgress
}

/** 비밀번호는 호출자가 다 쓴 뒤 지움. */
class CreateAdminCommand(
    val displayName: String,
    val password: CharArray,
    val passwordConfirmation: CharArray,
) {
    override fun toString(): String = "CreateAdminCommand(displayName=$displayName)"
}

data class AdminCreated(val userId: UserId, val totp: TotpEnrollment)
