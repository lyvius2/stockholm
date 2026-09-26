package banghak.stock.shared.config

import java.time.Clock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 시간은 항상 이 `Clock`을 주입받아 씀. `Instant.now()` 직접 호출 금지. */
@Configuration
class ClockConfig {
    @Bean fun clock(): Clock = Clock.systemUTC()
}
