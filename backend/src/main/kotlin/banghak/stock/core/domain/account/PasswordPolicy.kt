package banghak.stock.core.domain.account

import banghak.stock.core.domain.error.WeakPasswordException

/** 비밀번호 규칙. 12자 이상, 확인 입력과 일치, 흔한 비밀번호 거부. */
object PasswordPolicy {
    const val MIN_LENGTH = 12

    private val COMMON =
        setOf(
            "password1234",
            "123456789012",
            "qwertyuiop12",
            "passwordpassword",
            "stockholm123",
            "adminadmin12",
            "111111111111",
        )

    fun validate(password: CharArray, confirmation: CharArray) {
        if (password.size < MIN_LENGTH) throw WeakPasswordException("비밀번호는 ${MIN_LENGTH}자 이상이어야 함")
        if (!password.contentEquals(confirmation)) throw WeakPasswordException("비밀번호와 확인 입력이 다름")
        if (String(password).lowercase() in COMMON) throw WeakPasswordException("너무 흔한 비밀번호임")
    }

    fun validateDisplayName(displayName: String) {
        if (displayName.isBlank() || displayName.length > UserAccount.DISPLAY_NAME_MAX)
            throw banghak.stock.core.domain.error.InvalidValueException(
                "표시 이름은 1~${UserAccount.DISPLAY_NAME_MAX}자여야 함"
            )
    }
}
