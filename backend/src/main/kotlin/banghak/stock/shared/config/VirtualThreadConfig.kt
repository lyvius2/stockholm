package banghak.stock.shared.config

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** I/O 병렬 작업은 가상 스레드로 함. 코루틴은 쓰지 않음. 동시 실행 수는 [SemaphoreConfig]가 제한함. */
@Configuration
class VirtualThreadConfig {
    @Bean(destroyMethod = "close")
    fun virtualThreadExecutor(): ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
}
