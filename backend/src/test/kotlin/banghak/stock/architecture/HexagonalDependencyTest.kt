package banghak.stock.architecture

import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 의존 방향은 adapter → application → core.usecase/port → core.domain 뿐임. */
class HexagonalDependencyTest {
    @Test
    @DisplayName("core.domain은 usecase·port·바깥 계층을 모름")
    fun domainDependsOnNothingOutside() {
        noClasses()
            .that()
            .resideInAPackage("banghak.stock.core.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("banghak.stock.core.usecase..", "banghak.stock.core.port..")
            .check(ProductionClasses.all)
    }

    @Test
    @DisplayName("application은 adapter·config를 참조하지 않음")
    fun applicationDoesNotDependOnAdapters() {
        noClasses()
            .that()
            .resideInAPackage("..application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "..adapter..",
                "banghak.stock.engine.config..",
                "banghak.stock.relay.config..",
            )
            .check(ProductionClasses.all)
    }

    @Test
    @DisplayName("진입 어댑터는 usecase만 부르고 application 구현을 직접 참조하지 않음")
    fun inboundAdaptersCallOnlyUseCases() {
        noClasses()
            .that()
            .resideInAPackage("..adapter.in..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..application..")
            .check(ProductionClasses.all)
    }

    @Test
    @DisplayName("출력 어댑터는 application·진입 어댑터를 참조하지 않음")
    fun outboundAdaptersDoNotDependOnApplication() {
        noClasses()
            .that()
            .resideInAPackage("..adapter.out..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..application..", "..adapter.in..")
            .check(ProductionClasses.all)
    }

    @Test
    @DisplayName("shared는 engine·relay를 모름")
    fun sharedDoesNotDependOnProfiles() {
        noClasses()
            .that()
            .resideInAPackage("banghak.stock.shared..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("banghak.stock.engine..", "banghak.stock.relay..")
            .check(ProductionClasses.all)
    }
}
