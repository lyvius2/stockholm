package banghak.stock.architecture

import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 최상위 모듈(core · engine · relay · shared)과 core 안의 개념 패키지 사이에 순환이 없어야 함. */
class NoCyclesTest {
    @Test
    @DisplayName("최상위 모듈 사이에 순환이 없음")
    fun topLevelModulesAreFreeOfCycles() {
        slices()
            .matching("banghak.stock.(*)..")
            .should()
            .beFreeOfCycles()
            .check(ProductionClasses.all)
    }

    @Test
    @DisplayName("core.domain의 개념 패키지 사이에 순환이 없음")
    fun domainConceptsAreFreeOfCycles() {
        slices()
            .matching("banghak.stock.core.domain.(*)..")
            .should()
            .beFreeOfCycles()
            .check(ProductionClasses.all)
    }
}
