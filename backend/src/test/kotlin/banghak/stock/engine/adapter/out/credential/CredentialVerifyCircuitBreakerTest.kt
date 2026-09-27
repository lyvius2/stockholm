package banghak.stock.engine.adapter.out.credential

import banghak.stock.core.domain.account.CredentialCheck
import banghak.stock.shared.config.RuntimeProfiles
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles(RuntimeProfiles.ENGINE)
class CredentialVerifyCircuitBreakerTest(@Autowired private val registry: CircuitBreakerRegistry) {
    @Test
    @DisplayName("검증기 서킷 브레이커는 Unreachable 결과를 실패로 세고 Rejected 는 세지 않음")
    fun countsUnreachableResultsAsFailures() {
        val names =
            listOf(
                "openai-verify",
                "anthropic-verify",
                "deepseek-verify",
                "ollama-verify",
                "dart-verify",
                "fred-verify",
            )
        for (name in names) {
            val predicate = registry.circuitBreaker(name).circuitBreakerConfig.recordResultPredicate
            assertThat(predicate.test(CredentialCheck.Unreachable("HTTP 503")))
                .describedAs(name)
                .isTrue()
            assertThat(predicate.test(CredentialCheck.Rejected("키 거부"))).describedAs(name).isFalse()
            assertThat(predicate.test(CredentialCheck.Ok(emptyMap()))).describedAs(name).isFalse()
        }
    }
}
