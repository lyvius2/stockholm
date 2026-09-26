package banghak.stock.architecture

import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.asSequence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 제공자·모델 이름은 `engine.adapter.out.llm`과 llm 설정 파일 안에만 있음. 다른 코드는 목적(`LlmPurpose`)만 앎. */
class NoModelNamesOutsideLlmTest {
    @Test
    @DisplayName("제공자 이름을 가진 클래스는 llm 어댑터 패키지에만 있음")
    fun providerClassesLiveInLlmAdapter() {
        classes()
            .that()
            .haveSimpleNameContaining("OpenAi")
            .or()
            .haveSimpleNameContaining("Anthropic")
            .or()
            .haveSimpleNameContaining("DeepSeek")
            .or()
            .haveSimpleNameContaining("Ollama")
            .should()
            .resideInAPackage(LLM_ADAPTER)
            .check(ProductionClasses.all)
    }

    @Test
    @DisplayName("Spring AI는 llm 어댑터 패키지에서만 import함")
    fun springAiIsUsedOnlyByLlmAdapter() {
        noClasses()
            .that()
            .resideOutsideOfPackages(LLM_ADAPTER, "banghak.stock.engine.config..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework.ai..")
            .check(ProductionClasses.all)
    }

    @Test
    @DisplayName("모델 이름 문자열이 llm 어댑터·llm 설정 밖의 소스에 없음")
    fun modelNameLiteralsAppearOnlyInLlmSources() {
        val offenders =
            Files.walk(SOURCE_ROOT).use { paths ->
                paths
                    .asSequence()
                    .filter { it.name.endsWith(".kt") }
                    .filterNot { it.toString().contains(LLM_ADAPTER_DIR) }
                    .filter { MODEL_NAME.containsMatchIn(it.readText()) }
                    .map { SOURCE_ROOT.relativize(it).toString() }
                    .toList()
            }
        assertThat(offenders).describedAs("모델 이름을 품은 소스").isEmpty()
    }

    companion object {
        private const val LLM_ADAPTER = "banghak.stock.engine.adapter.out.llm.."
        private const val LLM_ADAPTER_DIR = "engine/adapter/out/llm"
        private val SOURCE_ROOT: Path = Path.of("src/main/kotlin")
        private val MODEL_NAME =
            Regex(
                """gpt-\d|o\d-mini|claude-\d|claude-(opus|sonnet|haiku)|deepseek-(chat|reasoner)|llama\d|qwen\d|gemma\d|bge-m3""",
                RegexOption.IGNORE_CASE,
            )
    }
}
