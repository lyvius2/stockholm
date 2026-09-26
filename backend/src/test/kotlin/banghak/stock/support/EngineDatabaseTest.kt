package banghak.stock.support

import banghak.stock.shared.config.RuntimeProfiles
import java.nio.file.Files
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** engine 프로필을 임시 데이터 폴더(새 SQLite 파일)로 띄우는 통합 테스트의 공통 설정. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles(RuntimeProfiles.ENGINE)
abstract class EngineDatabaseTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun dataDir(registry: DynamicPropertyRegistry) {
            val dir = Files.createTempDirectory("stockholm-test-")
            registry.add("stockholm.data-dir") { dir.toString() }
        }
    }
}
