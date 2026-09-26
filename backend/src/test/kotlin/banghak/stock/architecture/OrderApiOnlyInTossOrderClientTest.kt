package banghak.stock.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 주문 API 호출은 토스 어댑터의 `TossOrderClient` 한 곳에만 있음(절대 규칙 6). */
class OrderApiOnlyInTossOrderClientTest {
    @Test
    @DisplayName("주문 엔드포인트를 선언한 Retrofit 메서드는 TossOrderClient에만 있음")
    fun orderEndpointsAreDeclaredOnlyInTossOrderClient() {
        methods()
            .that(declareOrderEndpoint())
            .should()
            .beDeclaredInClassesThat()
            .haveFullyQualifiedName(TOSS_ORDER_CLIENT)
            .check(ProductionClasses.all)
    }

    @Test
    @DisplayName("TossOrderClient는 toss 어댑터 패키지와 engine 조립 설정에서만 참조함")
    fun tossOrderClientIsUsedOnlyByTossAdapter() {
        noClasses()
            .that()
            .resideOutsideOfPackages(
                "banghak.stock.engine.adapter.out.toss..",
                "banghak.stock.engine.config..",
            )
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName(TOSS_ORDER_CLIENT)
            .check(ProductionClasses.all)
    }

    @Test
    @DisplayName("다른 증권사 어댑터에는 주문 엔드포인트가 없음")
    fun noOtherBrokerAdapterDeclaresOrderEndpoints() {
        classes()
            .that()
            .resideInAPackage("banghak.stock.engine.adapter.out..")
            .and()
            .resideOutsideOfPackage("banghak.stock.engine.adapter.out.toss..")
            .should(notDeclareOrderEndpoints())
            .check(ProductionClasses.all)
    }

    private fun declareOrderEndpoint() =
        object :
            DescribedPredicate<JavaMethod>(
                "declare a Retrofit endpoint whose path contains '$ORDER_PATH'"
            ) {
            override fun test(method: JavaMethod): Boolean =
                RetrofitEndpoints.pathOf(method)?.contains(ORDER_PATH) == true
        }

    private fun notDeclareOrderEndpoints() =
        object : ArchCondition<JavaClass>("not declare any order endpoint") {
            override fun check(item: JavaClass, events: ConditionEvents) {
                item.methods
                    .filter { RetrofitEndpoints.pathOf(it)?.contains(ORDER_PATH) == true }
                    .forEach {
                        events.add(
                            SimpleConditionEvent.violated(it, "${it.fullName} 이 주문 엔드포인트를 선언함")
                        )
                    }
            }
        }

    companion object {
        private const val TOSS_ORDER_CLIENT =
            "banghak.stock.engine.adapter.out.toss.TossOrderClient"
        private const val ORDER_PATH = "orders"
    }
}
