package banghak.stock.engine.adapter.`in`.web.session

import banghak.stock.core.domain.account.Principal
import banghak.stock.core.domain.account.UserSummary
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.usecase.LoginCommand
import banghak.stock.core.usecase.LoginUseCase
import banghak.stock.core.usecase.MemberAdminUseCase
import banghak.stock.core.usecase.RegisterMemberCommand
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Instant
import java.util.Base64
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class UserSummaryResponse(
    val userId: String,
    val displayName: String,
    val role: String,
    val status: String,
    val isTotpEnrolled: Boolean,
    val tossKeyDecision: String,
    val lockedUntil: Instant?,
) {
    companion object {
        fun from(user: UserSummary) =
            UserSummaryResponse(
                user.userId.value,
                user.displayName,
                user.role.name,
                user.status.name,
                user.isTotpEnrolled,
                user.tossKeyDecision.name,
                user.lockedUntil,
            )
    }
}

data class LoginRequest(
    val userId: String,
    val password: String,
    val totpCode: String? = null,
    val recoveryCode: String? = null,
)

data class LoginResponse(val token: String, val expiresAt: Instant, val user: UserSummaryResponse)

data class StepUpRequest(val totpCode: String)

data class StepUpResponse(val lastStepUpAt: Instant?)

data class RegisterRequest(
    val registrationCode: String,
    val displayName: String,
    val password: String,
    val passwordConfirmation: String,
)

data class RegisteredResponse(
    val userId: String,
    val totpQrPngBase64: String,
    val totpManualKey: String,
)

data class ConfirmTotpRequest(val userId: String, val code: String)

/** 로그인 모달·세션·구성원 가입. 비밀번호는 요청 처리 뒤 지움. */
@RestController
@RequestMapping("/session")
@Profile(RuntimeProfiles.ENGINE)
class SessionController(private val login: LoginUseCase, private val members: MemberAdminUseCase) {
    @GetMapping("/users")
    fun users(): List<UserSummaryResponse> = login.users().map(UserSummaryResponse::from)

    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): LoginResponse {
        val password = request.password.toCharArray()
        try {
            val issued =
                login.login(
                    LoginCommand(
                        UserId(request.userId),
                        password,
                        request.totpCode,
                        request.recoveryCode,
                    )
                )
            return LoginResponse(
                issued.token,
                issued.session.expiresAt,
                UserSummaryResponse.from(issued.user),
            )
        } finally {
            password.fill(Char.MIN_VALUE)
        }
    }

    @PostMapping("/logout")
    fun logout(principal: Principal) {
        login.logout(principal)
    }

    @PostMapping("/step-up")
    fun stepUp(principal: Principal, @RequestBody request: StepUpRequest): StepUpResponse =
        StepUpResponse(login.stepUp(principal, request.totpCode).lastStepUpAt)

    @PostMapping("/register")
    fun register(@RequestBody request: RegisterRequest): RegisteredResponse {
        val password = request.password.toCharArray()
        val confirmation = request.passwordConfirmation.toCharArray()
        try {
            val registered =
                members.register(
                    RegisterMemberCommand(
                        request.registrationCode,
                        request.displayName,
                        password,
                        confirmation,
                    )
                )
            return RegisteredResponse(
                registered.userId.value,
                Base64.getEncoder().encodeToString(registered.totp.qrPng),
                registered.totp.manualKey,
            )
        } finally {
            password.fill(Char.MIN_VALUE)
            confirmation.fill(Char.MIN_VALUE)
        }
    }

    @PostMapping("/register/totp")
    fun confirmTotp(@RequestBody request: ConfirmTotpRequest) {
        members.confirmMemberTotp(UserId(request.userId), request.code)
    }
}
