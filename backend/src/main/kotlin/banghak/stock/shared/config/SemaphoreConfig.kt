package banghak.stock.shared.config

import java.util.concurrent.Semaphore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 가상 스레드로 병렬화한 작업의 동시 실행 수를 `Semaphore` 빈으로 묶음. 이름으로 주입받아 씀. */
@Configuration
class SemaphoreConfig {
    @Bean fun llmCallSemaphore(limits: ConcurrencyLimits): Semaphore = Semaphore(limits.llmCalls)

    @Bean
    fun collectorSemaphore(limits: ConcurrencyLimits): Semaphore = Semaphore(limits.collectors)
}
