package banghak.stock.engine.application.account

import banghak.stock.core.domain.error.AuthenticationFailedException
import banghak.stock.core.domain.error.ForbiddenException
import banghak.stock.core.domain.error.TotpRejectedException
import banghak.stock.core.usecase.ChangePasswordCommand
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class AccountServiceTest {
    private val f = AuthTestFixture()
    private val walter = f.user("월터")

    @Test
    @DisplayName("비밀번호 변경은 현재 비밀번호가 맞아야 하고 새 비밀번호는 규칙을 지켜야 함")
    fun changePassword() {
        val principal = f.principal(walter)
        assertThatThrownBy {
                f.account.changePassword(
                    principal,
                    ChangePasswordCommand(
                        "wrong-password-123".toCharArray(),
                        "new-horse-battery-1".toCharArray(),
                        "new-horse-battery-1".toCharArray(),
                    ),
                )
            }
            .isInstanceOf(AuthenticationFailedException::class.java)
        f.account.changePassword(
            principal,
            ChangePasswordCommand(
                "correct-horse-battery".toCharArray(),
                "new-horse-battery-1".toCharArray(),
                "new-horse-battery-1".toCharArray(),
            ),
        )
        assertThat(
                f.hasher.matches(
                    "new-horse-battery-1".toCharArray(),
                    f.users.findById(walter.userId)?.passwordHash.orEmpty(),
                )
            )
            .isTrue()
    }

    @Test
    @DisplayName("TOTP 재등록은 현재 코드 확인 → 새 코드 확인 순서이고, 확인 전에는 옛 시드가 유효함")
    fun totpReenrollment() {
        val principal = f.principal(walter)
        assertThatThrownBy { f.account.startTotpReenrollment(principal, "000000") }
            .isInstanceOf(TotpRejectedException::class.java)
        f.account.startTotpReenrollment(principal, "123456")
        assertThat(f.totp.pending).contains(walter.userId)
        assertThat(f.totp.active).contains(walter.userId)
        f.tick(31)
        f.account.confirmTotpReenrollment(principal, "123456")
        assertThat(f.totp.pending).doesNotContain(walter.userId)
    }

    @Test
    @DisplayName("복구 코드 재발급은 step-up 이 필요하고 옛 코드는 전부 무효가 됨")
    fun recoveryCodesNeedStepUp() {
        assertThatThrownBy { f.account.reissueRecoveryCodes(f.principal(walter)) }
            .isInstanceOf(ForbiddenException::class.java)
        val first = f.account.reissueRecoveryCodes(f.principal(walter, steppedUp = true))
        assertThat(first).hasSize(8)
        val second = f.account.reissueRecoveryCodes(f.principal(walter, steppedUp = true))
        assertThat(f.recoveryCodes.findUsableByUserId(walter.userId)).hasSize(8)
        assertThat(f.recoveryCodes.findUsableByUserId(walter.userId).map { it.codeHash })
            .noneMatch { hash -> first.any { f.hasher.matches(it.toCharArray(), hash) } }
        assertThat(f.recoveryCodes.findUsableByUserId(walter.userId).map { it.codeHash })
            .allMatch { hash -> second.any { f.hasher.matches(it.toCharArray(), hash) } }
    }
}
