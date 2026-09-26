package banghak.stock.architecture

import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class CoreHasNoFrameworkTest {
    @Test
    @DisplayName("core는 프레임워크·라이브러리를 import하지 않음")
    fun coreDoesNotDependOnFrameworks() {
        noClasses()
            .that()
            .resideInAPackage("banghak.stock.core..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(*FRAMEWORK_PACKAGES)
            .check(ProductionClasses.all)
    }

    @Test
    @DisplayName("core는 engine·relay·shared를 모름")
    fun coreDoesNotDependOnOuterLayers() {
        noClasses()
            .that()
            .resideInAPackage("banghak.stock.core..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "banghak.stock.engine..",
                "banghak.stock.relay..",
                "banghak.stock.shared..",
            )
            .check(ProductionClasses.all)
    }

    companion object {
        private val FRAMEWORK_PACKAGES =
            arrayOf(
                "org.springframework..",
                "jakarta.persistence..",
                "jakarta.validation..",
                "tools.jackson..",
                "com.fasterxml..",
                "okhttp3..",
                "retrofit2..",
                "org.jooq..",
                "org.hibernate..",
                "org.flywaydb..",
                "io.github.resilience4j..",
                "org.apache.lucene..",
                "kotlinx.coroutines..",
            )
    }
}
