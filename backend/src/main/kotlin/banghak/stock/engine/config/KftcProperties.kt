package banghak.stock.engine.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 금융결제원 오픈뱅킹 접속 설정.
 * 키는 없음(앱 자격은 공유 키, 사용자 토큰은 개인 키로 Keychain).
 *
 * @property clientUseCode 이용기관코드(10자리). 거래고유번호 앞부분이며 비밀이 아님
 * @property redirectUri 금융결제원에 등록한 콜백 주소와 글자까지 같아야 함
 *
 * TODO(학습 테스트로 확인): 운영 호스트, redirect URI 로 127.0.0.1 등록 가능 여부, 범위 문자열
 */
@ConfigurationProperties("stockholm.kftc")
data class KftcProperties(
    val baseUrl: String = "https://testapi.openbanking.or.kr/",
    val redirectUri: String = "http://127.0.0.1:2609/assets/consent/callback",
    val clientUseCode: String = "",
    val scope: String = "login inquiry",
    /**
     * 동의 유효 기간.
     * 토큰 응답에 없어 설정으로 둠.
     *
     * TODO(학습 테스트로 확인): 실제 기간
     */
    val consentValidityDays: Long = 90,
)
