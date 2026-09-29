package banghak.stock.engine.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 외부 API 의 기본 주소.
 * 테스트는 WireMock 주소로 바꿔 끼움.
 * 키는 여기 없음(Keychain).
 */
@ConfigurationProperties("stockholm.external")
data class ExternalEndpointProperties(
    val openaiBaseUrl: String = "https://api.openai.com/",
    val anthropicBaseUrl: String = "https://api.anthropic.com/",
    val deepseekBaseUrl: String = "https://api.deepseek.com/",
    val dartBaseUrl: String = "https://opendart.fss.or.kr/",
    val fredBaseUrl: String = "https://api.stlouisfed.org/",
    val tossBaseUrl: String = "https://openapi.tossinvest.com/",
)
