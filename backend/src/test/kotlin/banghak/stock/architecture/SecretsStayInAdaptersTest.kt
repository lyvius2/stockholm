package banghak.stock.architecture

import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 비밀값을 읽는 경로는 출력 어댑터 안에만 있음. application·진입 어댑터는 값을 만지지 못함(절대 규칙 2). */
class SecretsStayInAdaptersTest {
    @Test
    @DisplayName("SecretReader 는 adapter.out 과 engine 조립 설정 밖에서 참조하지 않음")
    fun secretReaderIsUsedOnlyByOutboundAdapters() {
        noClasses()
            .that()
            .resideOutsideOfPackages(
                "banghak.stock.engine.adapter.out..",
                "banghak.stock.engine.config..",
            )
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("banghak.stock.engine.adapter.out.keychain.SecretReader")
            .check(ProductionClasses.all)
    }

    @Test
    @DisplayName("SecretValue.reveal 은 adapter.out 밖에서 호출하지 않음")
    fun revealIsCalledOnlyByOutboundAdapters() {
        noClasses()
            .that()
            .resideOutsideOfPackage("banghak.stock.engine.adapter.out..")
            .should()
            .callMethod("banghak.stock.core.domain.account.SecretValue", "reveal")
            .check(ProductionClasses.all)
    }
}
