package banghak.stock.core.domain.asset

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.Money
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class AssetsTest {
    private val asOf = Instant.parse("2026-10-06T00:00:00Z")

    @Test
    @DisplayName("계좌 표기는 끝 4자리만 남긴 꼴만 받고, 기관이 준 표기는 숫자 중 끝 4자리로 다시 만듦")
    fun masksToLastFourDigits() {
        listOf("123456789012", "123-****-1234", "****12345", "1234").forEach { bad ->
            assertThatThrownBy { ExternalAsset("국민은행", bad, AssetKind.DEPOSIT, null, asOf) }
                .describedAs(bad)
                .isInstanceOf(InvalidValueException::class.java)
        }
        assertThat(ExternalAsset.maskLast4("123-****-1234")).isEqualTo("****1234")
        assertThat(ExternalAsset.maskLast4("1234567890123")).isEqualTo("****0123")
        assertThat(ExternalAsset.maskLast4("***-12")).isEqualTo("****12")
        assertThat(ExternalAsset.maskLast4("")).isEqualTo("****")
        assertThat(
                ExternalAsset(
                        "국민은행",
                        "****1234",
                        AssetKind.DEPOSIT,
                        Money.of("1000", Currency.KRW),
                        asOf,
                    )
                    .maskedAccount
            )
            .isEqualTo("****1234")
    }

    @Test
    @DisplayName("동의는 만료 시각 전이면 ACTIVE, 정확히 만료 시각부터 EXPIRED")
    fun consentBoundary() {
        val consentedAt = Instant.parse("2026-01-01T00:00:00Z")
        val expiresAt = Instant.parse("2026-04-01T00:00:00Z")

        assertThat(AssetConsent.of(consentedAt, expiresAt, expiresAt.minusSeconds(1)).status)
            .isEqualTo(ConsentStatus.ACTIVE)
        assertThat(AssetConsent.of(consentedAt, expiresAt, expiresAt).status)
            .isEqualTo(ConsentStatus.EXPIRED)
        assertThat(AssetConsent.NONE.isActive).isFalse()
    }
}
