package banghak.stock.core.domain.account

import java.time.Duration
import java.time.Instant

/** 로그인 시도 제한. 5회 실패면 15분 잠금. */
object LoginPolicy {
    const val MAX_FAILURES = 5
    val LOCK_DURATION: Duration = Duration.ofMinutes(15)

    fun isLocked(account: UserAccount, now: Instant): Boolean =
        account.lockedUntil?.isAfter(now) == true

    fun afterFailure(account: UserAccount, now: Instant): UserAccount {
        val failures = account.failedLogins + 1
        val lockedUntil =
            if (failures >= MAX_FAILURES) now.plus(LOCK_DURATION) else account.lockedUntil
        return account.copy(
            failedLogins = if (failures >= MAX_FAILURES) 0 else failures,
            lockedUntil = lockedUntil,
            updatedAt = now,
        )
    }

    fun afterSuccess(account: UserAccount, now: Instant): UserAccount =
        account.copy(failedLogins = 0, lockedUntil = null, updatedAt = now)
}
