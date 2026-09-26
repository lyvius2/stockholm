package banghak.stock.shared.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/** 외부 HTTP 호출의 공통 설정. 인증 헤더 같은 비밀값은 어댑터가 Keychain에서 꺼내 붙임. */
@ConfigurationProperties("stockholm.http")
data class HttpProperties(
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val readTimeout: Duration = Duration.ofSeconds(15),
    val userAgent: String = "Stockholm/0.1",
)
