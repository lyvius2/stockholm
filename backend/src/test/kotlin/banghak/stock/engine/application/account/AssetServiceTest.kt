package banghak.stock.engine.application.account

import banghak.stock.core.domain.account.AuditAction
import banghak.stock.core.domain.asset.AssetConsent
import banghak.stock.core.domain.asset.AssetKind
import banghak.stock.core.domain.asset.ExternalAsset
import banghak.stock.core.domain.error.AssetUnavailableException
import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.Money
import banghak.stock.core.port.AssetConsentPort
import banghak.stock.core.port.AssetPort
import banghak.stock.core.port.TokenGeneratorPort
import banghak.stock.support.MutableClock
import banghak.stock.support.fakes.MemoryAuditLogPort
import banghak.stock.support.web.ApiTestSupport
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 동의 state 의 1회용·만료, 조회 캐시 1분, 실패 시 직전 결과, 감사 기록. */
class AssetServiceTest {
    private val clock = MutableClock(Instant.parse("2026-10-06T00:00:00Z"))
    private val principal = ApiTestSupport.principal
    private val userId = principal.userId
    private val audit = MemoryAuditLogPort()
    private var fetchCount = 0
    private var failNext = false
    private val completed = mutableListOf<Pair<UserId, String>>()
    private val revoked = mutableListOf<UserId>()
    private val assetPort =
        object : AssetPort {
            override fun assets(userId: UserId): List<ExternalAsset> {
                fetchCount += 1
                if (failNext) throw AssetUnavailableException("닿지 않음")
                return listOf(
                    ExternalAsset(
                        "국민은행",
                        "****1234",
                        AssetKind.DEPOSIT,
                        Money.of("1000", Currency.KRW),
                        clock.instant(),
                    )
                )
            }
        }
    private val consentPort =
        object : AssetConsentPort {
            override fun authorizeUrl(userId: UserId, state: String) =
                "https://kftc/authorize?state=$state"

            override fun completeConsent(userId: UserId, code: String): AssetConsent {
                completed += userId to code
                return AssetConsent.of(
                    clock.instant(),
                    clock.instant().plus(Duration.ofDays(90)),
                    clock.instant(),
                )
            }

            override fun consent(userId: UserId) = AssetConsent.NONE

            override fun revoke(userId: UserId) {
                revoked += userId
            }
        }
    private val tokens =
        object : TokenGeneratorPort {
            private var counter = 0

            override fun newToken() = "state-${++counter}"

            override fun newHumanCode() = "CODE"

            override fun hash(token: String) = "h:$token"
        }
    private val service = AssetService(assetPort, consentPort, tokens, audit, clock)

    @Test
    @DisplayName("동의 시작은 1회용 state 를 넣은 주소를 주고, 콜백은 그 state 로만 사용자를 찾아 토큰을 교환함")
    fun startThenComplete() {
        val start = service.start(principal)
        assertThat(start.authorizeUrl).endsWith("state=state-1")

        val user = service.complete("state-1", "code-9")

        assertThat(user).isEqualTo(userId)
        assertThat(completed).containsExactly(userId to "code-9")
        assertThat(audit.entries.map { it.action })
            .containsExactly(AuditAction.ASSET_CONSENT_STARTED, AuditAction.ASSET_CONSENTED)
    }

    @Test
    @DisplayName("같은 state 를 두 번 쓰거나 모르는 state 면 거부하고, 10분이 지나면 만료")
    fun stateIsSingleUseAndExpires() {
        service.start(principal)
        service.complete("state-1", "code")
        assertThatThrownBy { service.complete("state-1", "code") }
            .isInstanceOf(InvalidValueException::class.java)
        assertThatThrownBy { service.complete("unknown", "code") }
            .isInstanceOf(InvalidValueException::class.java)

        service.start(principal)
        clock.advance(Duration.ofMinutes(10).plusSeconds(1))
        assertThatThrownBy { service.complete("state-2", "code") }
            .isInstanceOf(InvalidValueException::class.java)
            .hasMessageContaining("만료")
        assertThat(completed).hasSize(1)
    }

    @Test
    @DisplayName("거절·오류로 돌아온 콜백은 state 를 거둬 뒤늦은 코드로 완료할 수 없음")
    fun abandonDropsState() {
        service.start(principal)

        service.abandon("state-1")

        assertThatThrownBy { service.complete("state-1", "code") }
            .isInstanceOf(InvalidValueException::class.java)
    }

    @Test
    @DisplayName("1분 안의 재조회는 직전 결과를 돌려주고, 1분이 지나면 다시 받음")
    fun cachesForOneMinute() {
        service.assets(userId)
        clock.advance(Duration.ofSeconds(59))
        service.assets(userId)
        assertThat(fetchCount).isEqualTo(1)

        clock.advance(Duration.ofSeconds(1))
        service.assets(userId)

        assertThat(fetchCount).isEqualTo(2)
        assertThat(audit.entries.filter { it.action == AuditAction.ASSET_VIEWED }).hasSize(2)
    }

    @Test
    @DisplayName("조회가 실패하면 직전 결과를 stale 로 돌려주고, 직전 결과가 없으면 예외를 그대로 올림")
    fun staleOnFailure() {
        failNext = true
        assertThatThrownBy { service.assets(userId) }
            .isInstanceOf(AssetUnavailableException::class.java)

        failNext = false
        val fresh = service.assets(userId)
        clock.advance(Duration.ofMinutes(2))
        failNext = true
        val stale = service.assets(userId)

        assertThat(stale.isStale).isTrue()
        assertThat(stale.assets).isEqualTo(fresh.assets)
        assertThat(stale.fetchedAt).isEqualTo(fresh.fetchedAt)
    }

    @Test
    @DisplayName("동의 철회는 토큰을 지우고 직전 결과도 비움")
    fun revokeClearsCache() {
        service.assets(userId)

        service.revoke(principal)
        failNext = true

        assertThat(revoked).containsExactly(userId)
        assertThatThrownBy { service.assets(userId) }
            .isInstanceOf(AssetUnavailableException::class.java)
        assertThat(audit.entries.last().action).isEqualTo(AuditAction.ASSET_CONSENT_REVOKED)
    }
}
