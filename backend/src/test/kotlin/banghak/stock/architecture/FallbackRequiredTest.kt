package banghak.stock.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
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
        rule().check(ProductionClasses.all)
    }

    fun rule(): ArchRule = methods().that(callRetrofitInterface()).should(haveCompatibleFallback())

    private fun callRetrofitInterface() =
        object : DescribedPredicate<JavaMethod>("call a Retrofit interface method") {
            override fun test(method: JavaMethod): Boolean =
                method.methodCallsFromSelf.any {
                    RetrofitEndpoints.isRetrofitInterface(it.targetOwner)
                }
        }

    private fun haveCompatibleFallback() =
        object :
            ArchCondition<JavaMethod>(
                "be annotated with @CircuitBreaker whose fallbackMethod exists with a compatible signature"
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
                val compatible =
                    fallback.isNotBlank() &&
                        item.owner.methods.any {
                            it.name == fallback && isCompatibleFallback(item, it)
                        }
                events.add(
                    SimpleConditionEvent(
                        item,
                        compatible,
                        "${item.fullName} 의 fallbackMethod='$fallback' 가 없거나 시그니처가 맞지 않음",
                    )
                )
            }
        }

    /** Resilience4j 규칙: 반환 타입이 같고, 인자는 원본과 같거나 끝에 예외 하나가 더 붙음. */
    private fun isCompatibleFallback(original: JavaMethod, fallback: JavaMethod): Boolean {
        if (fallback.rawReturnType != original.rawReturnType) return false
        val originalParams = original.rawParameterTypes.map { it.name }
        val fallbackParams = fallback.rawParameterTypes.map { it.name }
        val sameParams = fallbackParams == originalParams
        val withThrowable =
            fallbackParams.size == originalParams.size + 1 &&
                fallbackParams.dropLast(1) == originalParams &&
                fallback.rawParameterTypes.last().isAssignableTo(Throwable::class.java)
        return sameParams || withThrowable
    }

    companion object {
        private const val CIRCUIT_BREAKER =
            "io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker"
    }
}
