package banghak.stock.architecture

import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class EngineRelayIsolationTest {
    @Test
    @DisplayName("engine은 relay를 참조하지 않음")
    fun engineDoesNotDependOnRelay() {
        noClasses()
            .that()
            .resideInAPackage("banghak.stock.engine..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("banghak.stock.relay..")
            .check(ProductionClasses.all)
    }

    @Test
    @DisplayName("relay는 engine을 참조하지 않음")
    fun relayDoesNotDependOnEngine() {
        noClasses()
            .that()
            .resideInAPackage("banghak.stock.relay..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("banghak.stock.engine..")
            .check(ProductionClasses.all)
    }
}
