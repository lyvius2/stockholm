package banghak.stock.engine.adapter.`in`.web.me

import banghak.stock.core.domain.account.Principal
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.usecase.AccountUseCase
import banghak.stock.core.usecase.ChangePasswordCommand
import banghak.stock.core.usecase.CredentialAdminUseCase
import banghak.stock.engine.adapter.`in`.web.session.UserSummaryResponse
import banghak.stock.engine.adapter.`in`.web.setup.CredentialCheckResponse
import banghak.stock.engine.adapter.`in`.web.setup.CredentialRequest
import banghak.stock.shared.config.RuntimeProfiles
import java.util.Base64
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
    val newPasswordConfirmation: String,
)

data class TotpStartRequest(val currentCode: String)

data class TotpEnrollmentResponse(val totpQrPngBase64: String, val totpManualKey: String)

data class TotpConfirmRequest(val code: String)

data class RecoveryCodesResponse(val codes: List<String>)

/** 본인 계정. 본인 세션만. */
@RestController
@RequestMapping("/me")
@Profile(RuntimeProfiles.ENGINE)
class MeController(
    private val account: AccountUseCase,
    private val credentials: CredentialAdminUseCase,
) {
    @GetMapping
    fun me(principal: Principal): UserSummaryResponse =
        UserSummaryResponse.from(account.me(principal))

    @PutMapping("/password")
    fun changePassword(principal: Principal, @RequestBody request: ChangePasswordRequest) {
        val current = request.currentPassword.toCharArray()
        val new = request.newPassword.toCharArray()
        val confirmation = request.newPasswordConfirmation.toCharArray()
        try {
            account.changePassword(principal, ChangePasswordCommand(current, new, confirmation))
        } finally {
            listOf(current, new, confirmation).forEach { it.fill(Char.MIN_VALUE) }
        }
    }

    @PostMapping("/totp")
    fun startTotp(
        principal: Principal,
        @RequestBody request: TotpStartRequest,
    ): TotpEnrollmentResponse {
        val enrollment = account.startTotpReenrollment(principal, request.currentCode)
        return TotpEnrollmentResponse(
            Base64.getEncoder().encodeToString(enrollment.qrPng),
            enrollment.manualKey,
        )
    }

    @PostMapping("/totp/confirm")
    fun confirmTotp(principal: Principal, @RequestBody request: TotpConfirmRequest) {
        account.confirmTotpReenrollment(principal, request.code)
    }

    @PostMapping("/recovery-codes")
    fun reissueRecoveryCodes(principal: Principal): RecoveryCodesResponse =
        RecoveryCodesResponse(account.reissueRecoveryCodes(principal))

    @PutMapping("/credentials/toss")
    fun replaceToss(
        principal: Principal,
        @RequestBody request: CredentialRequest,
    ): CredentialCheckResponse =
        CredentialCheckResponse.from(
            credentials.replaceOwnToss(
                principal,
                request.fields
                    .filterValues { it.isNotBlank() }
                    .mapValues { SecretValue.of(it.value) },
            )
        )
}
