package banghak.stock.core.domain.error

/** 필요한 비밀값이 등록되어 있지 않음. 사용자에게 등록을 안내함. */
class SecretMissingException(message: String) : DomainException(message)

/** 비밀 저장소(Keychain) 호출이 실패함. 메시지에 값은 들어가지 않음. */
class SecretStoreFailureException(message: String, cause: Throwable? = null) :
    DomainException(message, cause)
