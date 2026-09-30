package banghak.stock.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 모든 주문은 가드레일을 거침.
 * `TradingPort`·`ConditionalOrderPort`의 주문 메서드는 주문 usecase 구현 한 곳에서만 부름.
 */
class GuardrailNotBypassableTest {
    @Test
    @DisplayName("주문 포트의 주문 메서드는 engine.application.trading 에서만 호출함")
    fun onlyTradingApplicationPlacesOrders() {
        noClasses()
            .that()
            .resideOutsideOfPackage(ORDER_APPLICATION)
            .should()
            .callMethodWhere(placingOrderOnOrderPort())
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
    @DisplayName("진입 어댑터는 주문 포트를 직접 참조하지 않음")
    fun inboundAdaptersNeverTouchOrderPorts() {
        noClasses()
            .that()
            .resideInAPackage("..adapter.in..")
            .should()
            .dependOnClassesThat()
            .haveNameMatching(ORDER_PORT_NAMES)
            .check(ProductionClasses.all)
    }

    @Test
    @DisplayName("주문 포트 구현체는 toss 어댑터와 engine 조립 설정 밖에서 참조하지 않음")
    fun orderPortImplementationsAreNotReferencedDirectly() {
        noClasses()
            .that()
            .resideOutsideOfPackages(
                "banghak.stock.engine.adapter.out.toss..",
                "banghak.stock.engine.config..",
            )
            .should()
            .dependOnClassesThat(implementOrderPort())
            .check(ProductionClasses.all)
    }

    private fun implementOrderPort() =
        object : DescribedPredicate<JavaClass>("implement an order port") {
            override fun test(type: JavaClass): Boolean =
                type.allRawInterfaces.any { it.name in ORDER_PORTS }
        }

    private fun placingOrderOnOrderPort() =
        object : DescribedPredicate<JavaMethodCall>("place an order on an order port") {
            override fun test(call: JavaMethodCall): Boolean =
                call.targetOwner.name in ORDER_PORTS && call.target.name.startsWith(PLACE_PREFIX)
        }

    private fun callPlaceOrder() =
        object : DescribedPredicate<JavaClass>("call an order port place*") {
            override fun test(type: JavaClass): Boolean =
                type.methodCallsFromSelf.any(placingOrderOnOrderPort()::test)
        }

    companion object {
        private const val ORDER_APPLICATION = "banghak.stock.engine.application.trading.."
        private val ORDER_PORTS =
            setOf(
                "banghak.stock.core.port.TradingPort",
                "banghak.stock.core.port.ConditionalOrderPort",
            )
        private val ORDER_PORT_NAMES = ORDER_PORTS.joinToString("|") { Regex.escape(it) }
        private const val GUARDRAIL_USE_CASE = "banghak.stock.core.usecase.EvaluateGuardrailUseCase"
        private const val PLACE_PREFIX = "place"
    }
}
