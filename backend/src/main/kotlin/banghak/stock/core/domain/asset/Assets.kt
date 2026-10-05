package banghak.stock.core.domain.asset

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.money.Money
import java.time.Instant

/** 금융결제원이 알려 주는 계좌 종류. */
enum class AssetKind {
    DEPOSIT,
    SAVINGS,
    SECURITIES,
    FUND,
    CARD,
    INSURANCE,
    LOAN,
    OTHER,
}

/**
 * 외부 기관의 계좌 하나.
 * [maskedAccount] 는 `****1234` 꼴(끝 4자리만)이며 계좌번호 원문은 어디에도 두지 않음.
 * [balance] 는 조회 동의가 없는 계좌면 null.
 */
data class ExternalAsset(
    val institution: String,
    val maskedAccount: String,
    val kind: AssetKind,
    val balance: Money?,
    val asOf: Instant,
) {
    init {
        if (institution.isBlank()) throw InvalidValueException("기관 이름이 비어 있음")
        if (!MASKED.matches(maskedAccount)) throw InvalidValueException("계좌 표기는 끝 4자리만 남긴 꼴이어야 함")
    }

    companion object {
        private const val MASK = "****"
        private const val VISIBLE_DIGITS = 4
        private val MASKED = Regex("\\*{4}[0-9]{0,$VISIBLE_DIGITS}")

        /**
         * 기관이 준 표기를 믿지 않고 숫자 중 끝 4자리만 남김.
         * 숫자가 4자리보다 적으면 있는 만큼만, 없으면 가림표만 남김.
         */
        fun maskLast4(raw: String): String =
            MASK + raw.filter { it.isDigit() }.takeLast(VISIBLE_DIGITS)
    }
}

/**
 * 한 번의 조회 결과.
 * [isStale] 은 이번 조회가 실패해 직전 결과를 돌려준 것임.
 */
data class AssetSnapshot(
    val assets: List<ExternalAsset>,
    val fetchedAt: Instant,
    val isStale: Boolean = false,
) {
    fun markStale() = copy(isStale = true)
}

enum class ConsentStatus {
    NONE,
    ACTIVE,
    EXPIRED,
}

/**
 * 사용자의 금융결제원 동의 상태.
 * 토큰 값은 Keychain 에만 있고 여기에는 동의 만료 시각만 있음.
 * 접근 토큰의 만료는 갱신으로 풀리므로 동의 만료와 다름.
 */
data class AssetConsent(
    val status: ConsentStatus,
    val expiresAt: Instant?,
    val consentedAt: Instant?,
) {
    val isActive: Boolean
        get() = status == ConsentStatus.ACTIVE

    companion object {
        val NONE = AssetConsent(ConsentStatus.NONE, null, null)

        fun of(consentedAt: Instant, expiresAt: Instant, now: Instant): AssetConsent =
            AssetConsent(
                if (now.isBefore(expiresAt)) ConsentStatus.ACTIVE else ConsentStatus.EXPIRED,
                expiresAt,
                consentedAt,
            )
    }
}

/**
 * 동의 흐름 시작.
 * 화면은 [authorizeUrl] 을 기본 브라우저로 염(앱 안 웹뷰 없음).
 */
data class ConsentStart(val authorizeUrl: String)
