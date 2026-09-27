package banghak.stock.engine.adapter.`in`.web.admin

import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.Principal
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.usecase.CredentialAdminUseCase
import banghak.stock.core.usecase.MemberAdminUseCase
import banghak.stock.engine.adapter.`in`.web.session.UserSummaryResponse
import banghak.stock.engine.adapter.`in`.web.setup.CredentialCheckResponse
import banghak.stock.engine.adapter.`in`.web.setup.CredentialRequest
import banghak.stock.engine.adapter.`in`.web.setup.CredentialSummary
import banghak.stock.shared.config.RuntimeProfiles
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class RegistrationCodeResponse(val code: String)

data class TransferAdminRequest(val toUserId: String)

/** 회원 관리·공유 키(admin + step-up). 값은 응답에 없음. */
@RestController
@RequestMapping("/admin")
@Profile(RuntimeProfiles.ENGINE)
class AdminController(
    private val members: MemberAdminUseCase,
    private val credentials: CredentialAdminUseCase,
) {
    @GetMapping("/members")
    fun members(admin: Principal): List<UserSummaryResponse> =
        members.members(admin).map(UserSummaryResponse::from)

    @PostMapping("/members/registration-codes")
    fun issueRegistrationCode(admin: Principal): RegistrationCodeResponse =
        RegistrationCodeResponse(members.issueRegistrationCode(admin))

    @PostMapping("/members/{userId}/suspend")
    fun suspend(admin: Principal, @PathVariable userId: String) {
        members.suspend(admin, UserId(userId))
    }

    @PostMapping("/members/{userId}/resume")
    fun resume(admin: Principal, @PathVariable userId: String) {
        members.resume(admin, UserId(userId))
    }

    @DeleteMapping("/members/{userId}/credentials/toss")
    fun deleteToss(admin: Principal, @PathVariable userId: String) {
        members.deleteTossCredential(admin, UserId(userId))
    }

    @PostMapping("/members/transfer-admin")
    fun transferAdmin(admin: Principal, @RequestBody request: TransferAdminRequest) {
        members.transferAdmin(admin, UserId(request.toUserId))
    }

    @GetMapping("/credentials")
    fun sharedCredentials(admin: Principal): List<CredentialSummary> =
        credentials.sharedCredentials(admin).map(CredentialSummary::from)

    @PutMapping("/credentials/{kind}")
    fun replace(
        admin: Principal,
        @PathVariable kind: CredentialKind,
        @RequestBody request: CredentialRequest,
    ): CredentialCheckResponse =
        CredentialCheckResponse.from(
            credentials.replaceShared(
                admin,
                kind,
                request.fields
                    .filterValues { it.isNotBlank() }
                    .mapValues { SecretValue.of(it.value) },
            )
        )

    @DeleteMapping("/credentials/{kind}")
    fun delete(admin: Principal, @PathVariable kind: CredentialKind) {
        credentials.deleteShared(admin, kind)
    }

    @PostMapping("/credentials/{kind}/recheck")
    fun recheck(admin: Principal, @PathVariable kind: CredentialKind): CredentialCheckResponse =
        CredentialCheckResponse.from(credentials.recheckShared(admin, kind))
}
