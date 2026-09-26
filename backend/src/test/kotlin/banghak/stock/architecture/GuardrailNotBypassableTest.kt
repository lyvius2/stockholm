package banghak.stock.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 모든 주문은 가드레일을 거침. `TradingPort`의 주문 메서드는 주문 usecase 구현 한 곳에서만 부름. */
class GuardrailNotBypassableTest {
    @Test
    @DisplayName("TradingPort의 주문 메서드는 engine.application.trading 에서만 호출함")
    fun onlyTradingApplicationPlacesOrders() {
        noClasses()
            .that()
            .resideOutsideOfPackage(ORDER_APPLICATION)
            .should()
            .callMethodWhere(placingOrderOnTradingPort())
            .check(ProductionClasses.all)
    }

    @Test
    @DisplayName("주문 메서드를 부르는 클래스는 EvaluateGuardrailUseCase에 의존함")
    fun orderPlacersDependOnGuardrailUseCase() {
        classes()
            .that(callPlaceOrder())
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName(GUARDRAIL_USE_CASE)
            .check(ProductionClasses.all)
    }

    @Test
    @DisplayName("진입 어댑터는 TradingPort를 직접 참조하지 않음")
    fun inboundAdaptersNeverTouchTradingPort() {
        noClasses()
            .that()
            .resideInAPackage("..adapter.in..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName(TRADING_PORT)
            .check(ProductionClasses.all)
    }

    private fun placingOrderOnTradingPort() =
        object : DescribedPredicate<JavaMethodCall>("place an order on TradingPort") {
            override fun test(call: JavaMethodCall): Boolean =
                call.targetOwner.name == TRADING_PORT && call.target.name.startsWith(PLACE_PREFIX)
        }

    private fun callPlaceOrder() =
        object : DescribedPredicate<JavaClass>("call TradingPort.place*") {
            override fun test(type: JavaClass): Boolean =
                type.methodCallsFromSelf.any(placingOrderOnTradingPort()::test)
        }

    companion object {
        private const val ORDER_APPLICATION = "banghak.stock.engine.application.trading.."
        private const val TRADING_PORT = "banghak.stock.core.port.TradingPort"
        private const val GUARDRAIL_USE_CASE = "banghak.stock.core.usecase.EvaluateGuardrailUseCase"
        private const val PLACE_PREFIX = "place"
    }
}
