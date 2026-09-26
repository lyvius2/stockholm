package banghak.stock

import banghak.stock.shared.config.RuntimeProfiles
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext

/** 같은 산출물이 engine · relay · 둘 다 세 형태로 기동되고, 프로필 없이는 뜨지 않음. */
class ProfileContextTest {
    @TempDir lateinit var dataDir: Path

    @Test
    @DisplayName("engine 프로필만으로 기동됨")
    fun startsWithEngineOnly() {
        start(RuntimeProfiles.ENGINE).use { context ->
            assertThat(beanPackages(context)).contains(ENGINE_PACKAGE).doesNotContain(RELAY_PACKAGE)
        }
    }

    @Test
    @DisplayName("relay 프로필만으로 기동되고 engine 빈은 하나도 없음")
    fun startsWithRelayOnlyAndLoadsNoEngineBeans() {
        start(RuntimeProfiles.RELAY).use { context ->
            assertThat(beanPackages(context)).contains(RELAY_PACKAGE).doesNotContain(ENGINE_PACKAGE)
        }
    }

    @Test
    @DisplayName("engine과 relay를 함께 켜도 기동됨")
    fun startsWithBothProfiles() {
        start(RuntimeProfiles.ENGINE, RuntimeProfiles.RELAY).use { context ->
            assertThat(beanPackages(context)).contains(ENGINE_PACKAGE, RELAY_PACKAGE)
        }
    }

    @Test
    @DisplayName("프로필 없이 기동하면 실패함")
    fun failsWithoutProfile() {
        assertThatThrownBy { start().close() }
            .rootCause()
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("실행 프로필이 없음")
    }

    private fun start(vararg profiles: String): ConfigurableApplicationContext =
        SpringApplicationBuilder(StockholmApplication::class.java)
            .web(WebApplicationType.NONE)
            .profiles(*profiles)
            .properties("stockholm.data-dir=$dataDir")
            .run()

    private fun beanPackages(context: ConfigurableApplicationContext): Set<String> =
        context.beanDefinitionNames
            .mapNotNull { context.getType(it)?.packageName }
            .filter { it.startsWith("banghak.stock.") }
            .map { it.split('.').take(3).joinToString(".") }
            .toSet()

    companion object {
        private const val ENGINE_PACKAGE = "banghak.stock.engine"
        private const val RELAY_PACKAGE = "banghak.stock.relay"
    }
}
