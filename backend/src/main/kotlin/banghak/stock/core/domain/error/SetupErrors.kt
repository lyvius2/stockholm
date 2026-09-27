package banghak.stock.core.domain.error

/** 최초 구동 상태에서 허용되지 않는 전이·작업. */
class IllegalSetupTransitionException(message: String) : DomainException(message)

/** 비밀번호 규칙 위반. 사용자가 고칠 수 있는 사유를 담음. */
class WeakPasswordException(message: String) : DomainException(message)

/** 최대 인원(4명)을 넘김. */
class TooManyUsersException(message: String) : DomainException(message)

/** TOTP 코드가 틀렸거나 이미 쓴 코드임. */
class TotpRejectedException(message: String) : DomainException(message)

/** 비밀번호·TOTP·복구 코드가 틀렸거나 계정이 잠김·정지됨. 어느 쪽인지는 밝히지 않음. */
class AuthenticationFailedException(message: String) : DomainException(message)

/** 세션이 없거나 만료·폐기됨. */
class SessionInvalidException(message: String) : DomainException(message)

/** admin 권한 또는 step-up 이 필요함. */
class ForbiddenException(message: String) : DomainException(message)

/** 등록 코드가 없거나 만료·사용됨. */
class RegistrationCodeInvalidException(message: String) : DomainException(message)
