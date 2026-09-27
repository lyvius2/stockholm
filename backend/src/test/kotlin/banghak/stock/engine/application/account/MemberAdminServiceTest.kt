package banghak.stock.engine.application.account

import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.CredentialMeta
import banghak.stock.core.domain.account.CredentialStatus
import banghak.stock.core.domain.account.SecretKey
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.domain.account.TossDecision
import banghak.stock.core.domain.account.UserStatus
import banghak.stock.core.domain.error.ForbiddenException
import banghak.stock.core.domain.error.RegistrationCodeInvalidException
import banghak.stock.core.domain.error.TooManyUsersException
import banghak.stock.core.domain.identity.Role
import banghak.stock.core.usecase.RegisterMemberCommand
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class MemberAdminServiceTest {
    private val f = AuthTestFixture()
    private val service =
        MemberAdminService(
            f.users,
            f.registrationCodes,
            f.credentials,
            f.secrets,
            f.hasher,
            f.totp,
            f.tokens,
            f.audit,
            f.ulids,
            f.clock,
        )
    private val admin = f.user("월터", Role.ADMIN)

    private fun register(code: String, name: String) =
        service.register(
            RegisterMemberCommand(
                code,
                name,
                "correct-horse-battery".toCharArray(),
                "correct-horse-battery".toCharArray(),
            )
        )

    @Test
    @DisplayName("admin 역할과 5분 안 step-up 이 없으면 회원 관리를 못 함")
    fun requiresAdminAndStepUp() {
        val member = f.user("구성원")
        assertThatThrownBy { service.members(f.principal(member, steppedUp = true)) }
            .isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { service.members(f.principal(admin)) }
            .isInstanceOf(ForbiddenException::class.java)
        assertThat(service.members(f.principal(admin, steppedUp = true))).hasSize(2)
    }

    @Test
    @DisplayName("등록 코드는 1회용·24시간이고 4번째 구성원(5번째 사용자)은 발급이 거부됨")
    fun registrationCodes() {
        fun principal() = f.principal(admin, steppedUp = true)
        val code = service.issueRegistrationCode(principal())
        val registered = register(code, "둘째")
        assertThat(f.users.findById(registered.userId)?.role).isEqualTo(Role.MEMBER)
        assertThatThrownBy { register(code, "셋째") }
            .describedAs("같은 코드 재사용")
            .isInstanceOf(RegistrationCodeInvalidException::class.java)
        val expiring = service.issueRegistrationCode(principal())
        f.tick(24 * 3600 + 1)
        assertThatThrownBy { register(expiring, "셋째") }
            .describedAs("만료")
            .isInstanceOf(RegistrationCodeInvalidException::class.java)
        register(service.issueRegistrationCode(principal()), "셋째")
        register(service.issueRegistrationCode(principal()), "넷째")
        assertThatThrownBy { service.issueRegistrationCode(principal()) }
            .isInstanceOf(TooManyUsersException::class.java)
    }

    @Test
    @DisplayName("가입한 구성원은 TOTP 확인 전에는 미등록이고 확인 뒤 등록됨")
    fun memberTotpConfirmation() {
        val registered =
            register(service.issueRegistrationCode(f.principal(admin, steppedUp = true)), "둘째")
        assertThat(f.users.findById(registered.userId)?.isTotpEnrolled).isFalse()
        service.confirmMemberTotp(registered.userId, "123456")
        assertThat(f.users.findById(registered.userId)?.isTotpEnrolled).isTrue()
    }

    @Test
    @DisplayName("정지·해제는 자기 자신에게 못 하고, 토스 키 삭제는 Keychain 과 메타를 지움")
    fun suspendAndDeleteToss() {
        val principal = f.principal(admin, steppedUp = true)
        val member = f.user("구성원")
        assertThatThrownBy { service.suspend(principal, admin.userId) }
            .isInstanceOf(ForbiddenException::class.java)
        service.suspend(principal, member.userId)
        assertThat(f.users.findById(member.userId)?.status).isEqualTo(UserStatus.SUSPENDED)
        service.resume(principal, member.userId)
        assertThat(f.users.findById(member.userId)?.status).isEqualTo(UserStatus.ACTIVE)

        f.secrets.put(
            SecretKey.user(member.userId, "TOSS_CLIENT_SECRET"),
            SecretValue.of("marker-secret"),
        )
        f.credentials.upsert(
            CredentialMeta(
                CredentialKind.TOSS,
                member.userId,
                CredentialStatus.VERIFIED,
                "cret",
                f.clock.instant(),
                null,
            )
        )
        f.users.save(
            f.users.findById(member.userId)!!.copy(tossKeyDecision = TossDecision.REGISTERED)
        )
        service.deleteTossCredential(principal, member.userId)
        assertThat(f.secrets.exists(SecretKey.user(member.userId, "TOSS_CLIENT_SECRET"))).isFalse()
        assertThat(f.credentials.findByUser(member.userId)).isEmpty()
        assertThat(f.users.findById(member.userId)?.tossKeyDecision).isEqualTo(TossDecision.NONE)
    }

    @Test
    @DisplayName("admin 이관은 활성 사용자에게만 되고 역할이 맞바뀜")
    fun transferAdmin() {
        val principal = f.principal(admin, steppedUp = true)
        val member = f.user("구성원")
        service.transferAdmin(principal, member.userId)
        assertThat(f.users.findById(member.userId)?.role).isEqualTo(Role.ADMIN)
        assertThat(f.users.findById(admin.userId)?.role).isEqualTo(Role.MEMBER)
    }
}
