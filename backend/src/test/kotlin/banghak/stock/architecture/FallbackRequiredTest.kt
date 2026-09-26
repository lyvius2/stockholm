package banghak.stock.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 외부 HTTP 호출마다 서킷 브레이커와 fallback이 붙어야 함. 돈이 걸린 경로의 fallback은 "주문하지 않음". */
class FallbackRequiredTest {
    @Test
    @DisplayName("Retrofit 인터페이스를 부르는 메서드는 @CircuitBreaker(fallbackMethod)를 가짐")
    fun everyRetrofitCallSiteHasCircuitBreakerWithFallback() {
        methods()
            .that(callRetrofitInterface())
            .should(haveCircuitBreakerWithExistingFallback())
            .check(ProductionClasses.all)
    }

    private fun callRetrofitInterface() =
        object : DescribedPredicate<JavaMethod>("call a Retrofit interface method") {
            override fun test(method: JavaMethod): Boolean =
                method.methodCallsFromSelf.any {
                    RetrofitEndpoints.isRetrofitInterface(it.targetOwner)
                }
        }

    private fun haveCircuitBreakerWithExistingFallback() =
        object :
            ArchCondition<JavaMethod>(
                "be annotated with @CircuitBreaker whose fallbackMethod exists in the same class"
            ) {
            override fun check(item: JavaMethod, events: ConditionEvents) {
                val annotation = item.annotations.firstOrNull { it.rawType.name == CIRCUIT_BREAKER }
                if (annotation == null) {
                    events.add(
                        SimpleConditionEvent.violated(item, "${item.fullName} 에 @CircuitBreaker 없음")
                    )
                    return
                }
                val fallback =
                    annotation
                        .tryGetExplicitlyDeclaredProperty("fallbackMethod")
                        .orElse(null)
                        ?.toString()
                        .orEmpty()
                val exists = fallback.isNotBlank() && item.owner.methods.any { it.name == fallback }
                events.add(
                    SimpleConditionEvent(
                        item,
                        exists,
                        "${item.fullName} 의 fallbackMethod='$fallback'",
                    )
                )
            }
        }

    companion object {
        private const val CIRCUIT_BREAKER =
            "io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker"
    }
}
