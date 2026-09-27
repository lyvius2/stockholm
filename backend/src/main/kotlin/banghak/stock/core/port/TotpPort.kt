package banghak.stock.core.port

import banghak.stock.core.domain.account.TotpEnrollment
import banghak.stock.core.domain.identity.UserId
import java.time.Instant

/**
 * TOTP 등록·검증. 시드는 어댑터가 Keychain 에 두고 밖으로 내지 않음. 등록은 대기 시드에 쓰고, 첫 코드가 맞으면 활성 시드로 승격함(재등록 중에도 옛 시드가
 * 유효함). 재사용 거부(같은 코드 두 번)는 호출자가 마지막 카운터를 저장해 판단함.
 */
interface TotpPort {
    /** 대기 시드를 새로 만들고 QR·수동 키를 돌려줌. */
    fun enroll(userId: UserId, accountLabel: String): TotpEnrollment

    /** 대기 시드로 코드를 확인하고 맞으면 활성 시드로 승격. 맞으면 카운터, 아니면 null. */
    fun confirmEnrollment(userId: UserId, code: String, now: Instant): Long?

    /** 활성 시드로 확인. 맞으면 그 코드의 시간 카운터, 틀리면 null. 앞뒤 한 구간(±30초) 허용. */
    fun verify(userId: UserId, code: String, now: Instant): Long?

    fun remove(userId: UserId)
}
