package banghak.stock.engine.adapter.`in`.web.asset

import banghak.stock.core.domain.account.Principal
import banghak.stock.core.domain.asset.AssetConsent
import banghak.stock.core.domain.asset.AssetSnapshot
import banghak.stock.core.domain.asset.ExternalAsset
import banghak.stock.core.domain.error.DomainException
import banghak.stock.core.usecase.AssetConsentUseCase
import banghak.stock.core.usecase.LookupAssetsUseCase
import banghak.stock.engine.adapter.`in`.web.common.MoneyDto
import banghak.stock.shared.config.RuntimeProfiles
import com.fasterxml.jackson.annotation.JsonProperty
import java.nio.charset.StandardCharsets
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class AssetDto(
    val institution: String,
    val maskedAccount: String,
    val kind: String,
    val balance: MoneyDto?,
    val asOf: Instant,
) {
    companion object {
        fun of(asset: ExternalAsset) =
            AssetDto(
                asset.institution,
                asset.maskedAccount,
                asset.kind.name,
                asset.balance?.let(MoneyDto::of),
                asset.asOf,
            )
    }
}

data class AssetSnapshotResponse(
    val assets: List<AssetDto>,
    val fetchedAt: Instant,
    @get:JsonProperty("isStale") val isStale: Boolean,
) {
    companion object {
        fun of(snapshot: AssetSnapshot) =
            AssetSnapshotResponse(
                snapshot.assets.map(AssetDto::of),
                snapshot.fetchedAt,
                snapshot.isStale,
            )
    }
}

data class ConsentResponse(val status: String, val expiresAt: Instant?, val consentedAt: Instant?) {
    companion object {
        fun of(consent: AssetConsent) =
            ConsentResponse(consent.status.name, consent.expiresAt, consent.consentedAt)
    }
}

data class ConsentStartResponse(val authorizeUrl: String)

/**
 * 자산 조회(F16)와 금융결제원 동의.
 * 콜백만 세션 없이 열려 있고 state 로 사용자를 찾음.
 * 자산·토큰 값은 응답 밖으로 나가지 않음(계좌는 가려진 표기만).
 */
@RestController
@RequestMapping("/assets")
@Profile(RuntimeProfiles.ENGINE)
class AssetController(
    private val assets: LookupAssetsUseCase,
    private val consents: AssetConsentUseCase,
) {
    @GetMapping
    fun assets(principal: Principal): AssetSnapshotResponse =
        AssetSnapshotResponse.of(assets.assets(principal.userId))

    @GetMapping("/consent")
    fun consent(principal: Principal): ConsentResponse =
        ConsentResponse.of(consents.status(principal.userId))

    @PostMapping("/consent")
    fun startConsent(principal: Principal): ConsentStartResponse =
        ConsentStartResponse(consents.start(principal).authorizeUrl)

    @DeleteMapping("/consent") fun revokeConsent(principal: Principal) = consents.revoke(principal)

    /**
     * 브라우저가 돌아오는 곳.
     * 사람이 보는 짧은 안내만 돌려주고 토큰·코드는 어디에도 쓰지 않음.
     */
    @GetMapping(CALLBACK_PATH, produces = [MediaType.TEXT_HTML_VALUE])
    fun callback(
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) error: String?,
    ): ResponseEntity<String> {
        if (state.isNullOrBlank()) return page(400, "잘못된 요청입니다. 앱에서 동의를 다시 시작하세요.")
        if (error != null || code.isNullOrBlank()) {
            consents.abandon(state)
            return page(200, "동의가 완료되지 않았습니다. 앱으로 돌아가 다시 시도하세요.")
        }
        return try {
            consents.complete(state, code)
            page(200, "금융결제원 동의가 끝났습니다. 이 창을 닫고 Stockholm 으로 돌아가세요.")
        } catch (e: DomainException) {
            log.warn("금융결제원 동의 콜백 처리 실패({})", e::class.simpleName)
            page(400, "동의를 마치지 못했습니다(${e.message}). 앱에서 다시 시작하세요.")
        }
    }

    private fun page(status: Int, message: String): ResponseEntity<String> =
        ResponseEntity.status(status)
            .contentType(MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
            .body("<!doctype html><meta charset=\"utf-8\"><title>Stockholm</title><p>$message</p>")

    companion object {
        const val CALLBACK_PATH = "/consent/callback"
        private val log = LoggerFactory.getLogger(AssetController::class.java)
    }
}
