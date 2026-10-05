package banghak.stock.engine.application.account

import banghak.stock.core.domain.account.AuditAction
import banghak.stock.core.domain.account.AuditEntry
import banghak.stock.core.domain.account.Principal
import banghak.stock.core.domain.asset.AssetConsent
import banghak.stock.core.domain.asset.AssetSnapshot
import banghak.stock.core.domain.asset.ConsentStart
import banghak.stock.core.domain.error.AssetUnavailableException
import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.port.AssetConsentPort
import banghak.stock.core.port.AssetPort
import banghak.stock.core.port.AuditLogPort
import banghak.stock.core.port.TokenGeneratorPort
import banghak.stock.core.usecase.AssetConsentUseCase
import banghak.stock.core.usecase.LookupAssetsUseCase
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * 자산 조회와 금융결제원 동의 흐름.
 * 조회 결과는 메모리에만 두고 저장·동기화하지 않음.
 * 콜백은 세션 없이 오므로 10분짜리 1회용 state 로만 사용자를 찾음.
 */
@Service
@Profile(RuntimeProfiles.ENGINE)
class AssetService(
    private val assets: AssetPort,
    private val consents: AssetConsentPort,
    private val tokens: TokenGeneratorPort,
    private val audit: AuditLogPort,
    private val clock: Clock,
) : LookupAssetsUseCase, AssetConsentUseCase {
    private data class PendingConsent(val userId: UserId, val issuedAt: Instant)

    private val pending = ConcurrentHashMap<String, PendingConsent>()
    private val snapshots = ConcurrentHashMap<UserId, AssetSnapshot>()

    override fun assets(userId: UserId): AssetSnapshot {
        val now = clock.instant()
        snapshots[userId]
            ?.takeIf { isFresh(it, now) }
            ?.let {
                return it
            }
        return try {
            AssetSnapshot(assets.assets(userId), now).also {
                snapshots[userId] = it
                audit.record(AuditEntry(now, userId, null, AuditAction.ASSET_VIEWED, null, "OK"))
            }
        } catch (e: AssetUnavailableException) {
            // 직전 결과가 있으면 그것을 stale 로 돌려주고, 없으면 그대로 올림
            val previous = snapshots[userId] ?: throw e
            log.warn("자산 조회 실패({}). 직전 결과를 보임", e::class.simpleName)
            previous.markStale()
        }
    }

    override fun start(principal: Principal): ConsentStart {
        val now = clock.instant()
        expirePending(now)
        val state = tokens.newToken()
        pending[state] = PendingConsent(principal.userId, now)
        audit.record(
            AuditEntry(
                now,
                principal.userId,
                principal.session.deviceId,
                AuditAction.ASSET_CONSENT_STARTED,
                null,
                "OK",
            )
        )
        return ConsentStart(consents.authorizeUrl(principal.userId, state))
    }

    // state 는 꺼내는 즉시 지워 두 번 쓰지 못함
    override fun complete(state: String, code: String): UserId {
        val now = clock.instant()
        val claim = pending.remove(state) ?: throw InvalidValueException("모르거나 이미 쓴 동의 요청")
        if (Duration.between(claim.issuedAt, now) > STATE_VALIDITY)
            throw InvalidValueException("동의 요청이 만료됨. 다시 시작할 것")
        consents.completeConsent(claim.userId, code)
        snapshots.remove(claim.userId)
        audit.record(AuditEntry(now, claim.userId, null, AuditAction.ASSET_CONSENTED, null, "OK"))
        return claim.userId
    }

    override fun abandon(state: String) {
        pending.remove(state)
    }

    override fun status(userId: UserId): AssetConsent = consents.consent(userId)

    override fun revoke(principal: Principal) {
        consents.revoke(principal.userId)
        snapshots.remove(principal.userId)
        audit.record(
            AuditEntry(
                clock.instant(),
                principal.userId,
                principal.session.deviceId,
                AuditAction.ASSET_CONSENT_REVOKED,
                null,
                "OK",
            )
        )
    }

    private fun isFresh(snapshot: AssetSnapshot, now: Instant): Boolean =
        !snapshot.isStale && Duration.between(snapshot.fetchedAt, now) < REFRESH_INTERVAL

    private fun expirePending(now: Instant) {
        pending.entries.removeIf { Duration.between(it.value.issuedAt, now) > STATE_VALIDITY }
    }

    companion object {
        private val log = LoggerFactory.getLogger(AssetService::class.java)

        /** 새로고침은 1분 간격(설계). */
        val REFRESH_INTERVAL: Duration = Duration.ofMinutes(1)

        /** 브라우저에서 동의를 마치고 돌아올 시간. */
        val STATE_VALIDITY: Duration = Duration.ofMinutes(10)
    }
}
