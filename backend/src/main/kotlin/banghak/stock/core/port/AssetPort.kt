package banghak.stock.core.port

import banghak.stock.core.domain.asset.AssetConsent
import banghak.stock.core.domain.asset.ExternalAsset
import banghak.stock.core.domain.identity.UserId

/**
 * 본인 자산 조회(F16).
 * 항상 [UserId] 범위이며 admin 예외가 없음.
 */
interface AssetPort {
    /**
     * @param userId 조회하는 사용자
     * @return 동의 범위 안의 계좌. 계좌번호는 가려진 표기만
     * @throws banghak.stock.core.domain.error.ConsentRequiredException 동의(토큰)가 없거나 만료됐으면 발생함
     * @throws banghak.stock.core.domain.error.AssetUnavailableException 기관에 닿지 못하면 발생함
     */
    fun assets(userId: UserId): List<ExternalAsset>
}

/**
 * 금융결제원 동의(OAuth) 수명주기.
 * 토큰은 어댑터가 Keychain 에 넣고 꺼내며 값은 밖으로 나오지 않음.
 */
interface AssetConsentPort {
    /**
     * 사용자 동의 화면 주소.
     * [state] 는 콜백을 사용자와 잇는 1회용 난수임.
     */
    fun authorizeUrl(userId: UserId, state: String): String

    /**
     * 콜백의 코드를 토큰으로 바꿔 보관함.
     *
     * @throws banghak.stock.core.domain.error.AssetUnavailableException 교환에 실패하면 발생함
     */
    fun completeConsent(userId: UserId, code: String): AssetConsent

    fun consent(userId: UserId): AssetConsent

    fun revoke(userId: UserId)
}
