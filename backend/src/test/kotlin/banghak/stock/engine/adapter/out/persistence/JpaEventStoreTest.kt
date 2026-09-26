package banghak.stock.engine.adapter.out.persistence

import banghak.stock.core.domain.eventlog.EventStoreContractTest
import banghak.stock.core.port.EventStore
import banghak.stock.shared.config.RuntimeProfiles
import com.zaxxer.hikari.HikariDataSource
import java.nio.file.Files
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * 영속 구현이 메모리 fake 와 같은 `EventStore` 계약을 지킴. 테스트마다 표를 비워 격리함. 실제 커밋과 읽기 풀 경로를 그대로 타게 하려는 것이며, 상속한
 * 테스트 메서드에는 하위 클래스의 @Transactional 이 적용되지 않기도 함(선언 클래스 기준).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles(RuntimeProfiles.ENGINE)
class JpaEventStoreTest : EventStoreContractTest() {
    @Autowired private lateinit var jpaEventStore: JpaEventStore
    @Autowired private lateinit var engineWriteDataSource: HikariDataSource

    @BeforeEach
    fun clearEventLog() {
        JdbcTemplate(engineWriteDataSource).update("delete from event_log")
    }

    override fun newStore(): EventStore = jpaEventStore

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun dataDir(registry: DynamicPropertyRegistry) {
            val dir = Files.createTempDirectory("stockholm-test-")
            registry.add("stockholm.data-dir") { dir.toString() }
        }
    }
}
