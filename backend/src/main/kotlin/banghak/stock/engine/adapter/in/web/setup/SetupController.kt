package banghak.stock.engine.adapter.`in`.web.setup

import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.usecase.CreateAdminCommand
import banghak.stock.core.usecase.SetupWizardUseCase
import banghak.stock.shared.config.RuntimeProfiles
import java.util.Base64
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/setup")
@Profile(RuntimeProfiles.ENGINE)
class SetupController(private val setup: SetupWizardUseCase) {
    @GetMapping("/state")
    fun state(): SetupStateResponse = SetupStateResponse.from(setup.progress())

    /** ② 화면의 키 종류 목록. 값·상태 없이 종류 메타만이라 마법사 세션 없이 열림. */
    @GetMapping("/catalog")
    fun catalog(): List<CredentialKindInfo> = CredentialKind.entries.map(CredentialKindInfo::from)

    @PostMapping("/admin")
    fun createAdmin(@RequestBody request: CreateAdminRequest): AdminCreatedResponse {
        val password = request.password.toCharArray()
        val confirmation = request.passwordConfirmation.toCharArray()
        try {
            val created =
                setup.createAdmin(CreateAdminCommand(request.displayName, password, confirmation))
            return AdminCreatedResponse(
                userId = created.userId.value,
                totpQrPngBase64 = Base64.getEncoder().encodeToString(created.totp.qrPng),
                totpManualKey = created.totp.manualKey,
            )
        } finally {
            password.fill(Char.MIN_VALUE)
            confirmation.fill(Char.MIN_VALUE)
        }
    }

    /** TOTP 확인이 끝나면 ②~④ 에 쓸 마법사 세션 토큰을 함께 돌려줌. */
    @PostMapping("/admin/totp")
    fun confirmTotp(@RequestBody request: TotpCodeRequest): WizardSessionResponse {
        val progress = setup.confirmAdminTotp(request.code)
        return WizardSessionResponse(SetupStateResponse.from(progress), setup.issueWizardSession())
    }

    /** 앱 재시작 등으로 마법사 세션이 없을 때 admin 비밀번호 + TOTP 로 다시 엶. */
    @PostMapping("/session")
    fun reopenSession(@RequestBody request: WizardSessionRequest): WizardSessionResponse {
        val password = request.password.toCharArray()
        try {
            val token = setup.reopenWizardSession(password, request.totpCode)
            return WizardSessionResponse(SetupStateResponse.from(setup.progress()), token)
        } finally {
            password.fill(Char.MIN_VALUE)
        }
    }

    @PostMapping("/keys/{kind}")
    fun registerSharedKey(
        @PathVariable kind: CredentialKind,
        @RequestBody request: CredentialRequest,
    ): CredentialCheckResponse =
        CredentialCheckResponse.from(setup.registerSharedCredential(kind, toSecrets(request)))

    @PostMapping("/keys/done")
    fun finishSharedKeys(@RequestBody request: FinishSharedKeysRequest): SetupStateResponse =
        SetupStateResponse.from(setup.finishSharedKeys(request.llmPreset))

    @PostMapping("/toss/keys")
    fun registerToss(@RequestBody request: CredentialRequest): CredentialCheckResponse =
        CredentialCheckResponse.from(setup.registerTossCredential(toSecrets(request)))

    @PostMapping("/toss")
    fun decideToss(@RequestBody request: TossDecisionRequest): SetupStateResponse =
        SetupStateResponse.from(setup.decideToss(request.decision))

    @PostMapping("/complete")
    fun complete(): SetupStateResponse = SetupStateResponse.from(setup.complete())

    private fun toSecrets(request: CredentialRequest): Map<String, SecretValue> =
        request.fields
            .filterValues { it.isNotBlank() }
            .mapValues { (_, value) -> SecretValue.of(value) }
}
