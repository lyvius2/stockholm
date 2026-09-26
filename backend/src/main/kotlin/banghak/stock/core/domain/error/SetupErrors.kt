package banghak.stock.core.domain.error

/** 최초 구동 상태에서 허용되지 않는 전이·작업. */
class IllegalSetupTransitionException(message: String) : DomainException(message)

/** 비밀번호 규칙 위반. 사용자가 고칠 수 있는 사유를 담음. */
class WeakPasswordException(message: String) : DomainException(message)

/** 최대 인원(4명)을 넘김. */
class TooManyUsersException(message: String) : DomainException(message)

/** TOTP 코드가 틀렸거나 이미 쓴 코드임. */
class TotpRejectedException(message: String) : DomainException(message)
