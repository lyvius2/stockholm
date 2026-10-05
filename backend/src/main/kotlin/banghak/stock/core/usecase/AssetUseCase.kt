package banghak.stock.core.usecase

import banghak.stock.core.domain.account.Principal
import banghak.stock.core.domain.asset.AssetConsent
import banghak.stock.core.domain.asset.AssetSnapshot
import banghak.stock.core.domain.asset.ConsentStart
import banghak.stock.core.domain.identity.UserId

/**
 * 자산 모달(F16)의 조회.
 * 본인 것만.
 */
interface LookupAssetsUseCase {
    /**
     * 1분 안에 다시 부르면 직전 결과를 돌려줌.
     * 이번 조회가 실패하면 직전 결과를 stale 로 표시해 돌려줌.
     *
     * @throws banghak.stock.core.domain.error.ConsentRequiredException 동의가 없거나 만료됐으면 발생함
     * @throws banghak.stock.core.domain.error.AssetUnavailableException 처음 조회부터 실패하면 발생함
     */
    fun assets(userId: UserId): AssetSnapshot
}

/**
 * 금융결제원 동의 흐름.
 * 브라우저 동의 → `127.0.0.1` 콜백 → 토큰 보관.
 */
interface AssetConsentUseCase {
    /** 1회용 state 를 만들어 동의 화면 주소를 돌려줌. */
    fun start(principal: Principal): ConsentStart

    /**
     * 브라우저 콜백.
     * 세션이 없으므로 [state] 로만 사용자를 찾음.
     *
     * @return 동의한 사용자
     * @throws banghak.stock.core.domain.error.InvalidValueException state 가 없거나 만료·재사용이면 발생함
     * @throws banghak.stock.core.domain.error.AssetUnavailableException 토큰 교환에 실패하면 발생함
     */
    fun complete(state: String, code: String): UserId

    /**
     * 동의 화면에서 거절·오류로 돌아온 경우.
     * state 를 거둠.
     */
    fun abandon(state: String)

    fun status(userId: UserId): AssetConsent

    fun revoke(principal: Principal)
}
