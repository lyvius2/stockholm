package banghak.stock.engine.application.account

import banghak.stock.core.domain.account.AuditAction
import banghak.stock.core.domain.account.LoginPolicy
import banghak.stock.core.domain.account.RecoveryCode
import banghak.stock.core.domain.account.UserStatus
import banghak.stock.core.domain.error.AuthenticationFailedException
import banghak.stock.core.domain.error.TotpRejectedException
import banghak.stock.core.usecase.LoginCommand
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class LoginServiceTest {
    private val f = AuthTestFixture()
    private val walter = f.user("월터")

    private fun login(
        password: String = "correct-horse-battery",
        totp: String? = "123456",
        recovery: String? = null,
    ) = f.login.login(LoginCommand(walter.userId, password.toCharArray(), totp, recovery))

    @Test
    @DisplayName("비밀번호와 TOTP 가 맞으면 세션이 발급되고 토큰은 해시로만 저장됨")
    fun loginIssuesSession() {
        val issued = login()
        assertThat(issued.token).isEqualTo("token-1")
        assertThat(f.sessions.sessions.keys).containsExactly("hash:token-1")
        assertThat(f.login.authenticate("token-1")?.userId).isEqualTo(walter.userId)
        assertThat(f.login.authenticate("token-2")).isNull()
        assertThat(f.audit.entries.last().action).isEqualTo(AuditAction.LOGIN_OK)
    }

    @Test
    @DisplayName("틀린 비밀번호·틀린 TOTP·같은 TOTP 재사용은 모두 같은 실패이고 5회면 15분 잠김")
    fun failuresLockAfterFive() {
        assertThatThrownBy { login(password = "wrong-password-123") }
            .isInstanceOf(AuthenticationFailedException::class.java)
        assertThatThrownBy { login(totp = "000000") }
            .isInstanceOf(AuthenticationFailedException::class.java)
        login()
        assertThatThrownBy { login() }
            .describedAs("같은 구간의 코드 재사용")
            .isInstanceOf(AuthenticationFailedException::class.java)
        f.tick(60)
        repeat(LoginPolicy.MAX_FAILURES) {
            assertThatThrownBy { login(password = "wrong-password-123") }
                .isInstanceOf(AuthenticationFailedException::class.java)
        }
        assertThat(f.users.findById(walter.userId)?.lockedUntil)
            .isEqualTo(f.clock.instant().plus(LoginPolicy.LOCK_DURATION))
        assertThatThrownBy { login() }
            .describedAs("잠긴 동안은 맞는 자격도 거부")
            .isInstanceOf(AuthenticationFailedException::class.java)
        f.tick(15 * 60 + 1)
        assertThat(login().user.userId).isEqualTo(walter.userId)
        assertThat(f.audit.entries.map { it.action }).contains(AuditAction.LOCKED)
    }

    @Test
    @DisplayName("복구 코드로도 로그인되고 그 코드는 한 번만 쓰임")
    fun recoveryCodeIsSingleUse() {
        f.recoveryCodes.replaceAll(
            walter.userId,
            listOf(
                RecoveryCode(
                    "r1",
                    walter.userId,
                    f.hasher.hash("ABCDE-FGHIJ".toCharArray()),
                    null,
                    f.clock.instant(),
                )
            ),
        )
        assertThat(login(totp = null, recovery = "ABCDE-FGHIJ").user.userId)
            .isEqualTo(walter.userId)
        assertThatThrownBy { login(totp = null, recovery = "ABCDE-FGHIJ") }
            .isInstanceOf(AuthenticationFailedException::class.java)
    }

    @Test
    @DisplayName("정지된 사용자·TOTP 미등록 사용자는 로그인하지 못함")
    fun suspendedOrUnenrolledCannotLogin() {
        f.users.save(walter.copy(status = UserStatus.SUSPENDED))
        assertThatThrownBy { login() }.isInstanceOf(AuthenticationFailedException::class.java)
        val fresh = f.user("신입", totpEnrolled = false)
        assertThatThrownBy {
                f.login.login(
                    LoginCommand(
                        fresh.userId,
                        "correct-horse-battery".toCharArray(),
                        "123456",
                        null,
                    )
                )
            }
            .isInstanceOf(AuthenticationFailedException::class.java)
    }

    @Test
    @DisplayName("세션은 만료·로그아웃 뒤 무효이고 step-up 은 5분만 유효함")
    fun sessionLifecycleAndStepUp() {
        val issued = login()
        val principal = f.login.authenticate(issued.token) ?: error("세션 없음")
        assertThat(principal.session.hasFreshStepUp(f.clock.instant())).isFalse()
        f.tick(31)
        assertThatThrownBy { f.login.stepUp(principal, "000000") }
            .isInstanceOf(TotpRejectedException::class.java)
        val stepped = f.login.stepUp(principal, "123456")
        assertThat(stepped.hasFreshStepUp(f.clock.instant())).isTrue()
        f.tick(5 * 60)
        assertThat(stepped.hasFreshStepUp(f.clock.instant())).isFalse()
        f.login.logout(principal)
        assertThat(f.login.authenticate(issued.token)).isNull()
        val again = login()
        f.tick(12 * 3600 + 1)
        assertThat(f.login.authenticate(again.token)).describedAs("12시간 뒤 만료").isNull()
    }
}
