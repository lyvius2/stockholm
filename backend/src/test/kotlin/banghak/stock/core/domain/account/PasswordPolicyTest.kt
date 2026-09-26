package banghak.stock.core.domain.account

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.error.WeakPasswordException
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PasswordPolicyTest {
    @Test
    @DisplayName("12자 이상이고 확인 입력과 같아야 하며 흔한 비밀번호는 거부함")
    fun validatesPassword() {
        assertThatCode {
            PasswordPolicy.validate(
                "correct-horse-battery".toCharArray(),
                "correct-horse-battery".toCharArray(),
            )
        }
            .doesNotThrowAnyException()
        assertThatThrownBy {
                PasswordPolicy.validate("short123456".toCharArray(), "short123456".toCharArray())
            }
            .isInstanceOf(WeakPasswordException::class.java)
        assertThatThrownBy {
                PasswordPolicy.validate(
                    "correct-horse-battery".toCharArray(),
                    "correct-horse-batterx".toCharArray(),
                )
            }
            .isInstanceOf(WeakPasswordException::class.java)
        assertThatThrownBy {
                PasswordPolicy.validate("Password1234".toCharArray(), "Password1234".toCharArray())
            }
            .isInstanceOf(WeakPasswordException::class.java)
    }

    @Test
    @DisplayName("표시 이름은 1~20자")
    fun validatesDisplayName() {
        assertThatCode { PasswordPolicy.validateDisplayName("admin") }.doesNotThrowAnyException()
        assertThatThrownBy { PasswordPolicy.validateDisplayName(" ") }
            .isInstanceOf(InvalidValueException::class.java)
        assertThatThrownBy { PasswordPolicy.validateDisplayName("a".repeat(21)) }
            .isInstanceOf(InvalidValueException::class.java)
    }
}
