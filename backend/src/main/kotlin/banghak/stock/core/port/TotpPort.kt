package banghak.stock.core.port

import banghak.stock.core.domain.account.TotpEnrollment
import banghak.stock.core.domain.identity.UserId
import java.time.Instant

/** TOTP 등록·검증. 시드는 어댑터가 Keychain 에 두고 밖으로 내지 않음. 재사용 거부(같은 코드 두 번)는 호출자가 마지막 카운터를 저장해 판단함. */
interface TotpPort {
    /** 새 시드를 만들어 저장하고 QR·수동 키를 돌려줌. 다시 부르면 시드가 바뀜. */
    fun enroll(userId: UserId, accountLabel: String): TotpEnrollment

    /** 코드가 맞으면 그 코드의 시간 카운터, 틀리면 null. 앞뒤 한 구간(±30초)을 허용함. */
    fun verify(userId: UserId, code: String, now: Instant): Long?
}
