package banghak.stock.shared.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 동시 실행 상한. 설정으로 낮출 수만 있고 기본값이 코드 상한임.
 *
 * @property llmCalls 동시에 진행하는 LLM 호출 수
 * @property collectors 동시에 도는 수집 배치 수
 */
@ConfigurationProperties("stockholm.concurrency")
data class ConcurrencyLimits(
    val llmCalls: Int = DEFAULT_LLM_CALLS,
    val collectors: Int = DEFAULT_COLLECTORS,
) {
    init {
        require(llmCalls in 1..DEFAULT_LLM_CALLS) {
            "llmCalls는 1..$DEFAULT_LLM_CALLS 범위여야 함: $llmCalls"
        }
        require(collectors in 1..DEFAULT_COLLECTORS) {
            "collectors는 1..$DEFAULT_COLLECTORS 범위여야 함: $collectors"
        }
    }

    companion object {
        const val DEFAULT_LLM_CALLS = 4
        const val DEFAULT_COLLECTORS = 8
    }
}
