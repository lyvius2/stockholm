package banghak.stock.architecture

import banghak.stock.architecture.fixtures.CallerWithGoodFallback
import banghak.stock.architecture.fixtures.CallerWithMismatchedFallback
import banghak.stock.architecture.fixtures.CallerWithoutFallback
import banghak.stock.architecture.fixtures.OrdersByGenericHttp
import banghak.stock.architecture.fixtures.OrdersByPost
import banghak.stock.architecture.fixtures.QuotesOnly
import com.tngtech.archunit.core.importer.ClassFileImporter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 경계 규칙 자체가 우회 사례를 놓치지 않는지 표본 클래스로 확인함. */
class ArchitectureRulesSelfTest {
    private val fixtures = ClassFileImporter().importPackages("banghak.stock.architecture.fixtures")

    @Test
    @DisplayName("주문 경로는 @POST 와 @HTTP(path=) 두 형태 모두 알아봄")
    fun recognizesOrderPathInBothAnnotationForms() {
        val byPost = fixtures.get(OrdersByPost::class.java).getMethod("place")
        val byHttp = fixtures.get(OrdersByGenericHttp::class.java).getMethod("cancel")
        val quotes = fixtures.get(QuotesOnly::class.java).getMethod("quotes")
        assertThat(RetrofitEndpoints.pathOf(byPost)).contains("orders")
        assertThat(RetrofitEndpoints.pathOf(byHttp)).contains("orders")
        assertThat(RetrofitEndpoints.pathOf(quotes)).doesNotContain("orders")
    }

    @Test
    @DisplayName("fallback 규칙은 시그니처가 맞는 것만 통과시킴")
    fun fallbackRuleAcceptsOnlyCompatibleSignatures() {
        val rule = FallbackRequiredTest().rule()
        assertThat(rule.evaluate(fixtures).failureReport.details.joinToString("\n"))
            .contains(CallerWithMismatchedFallback::class.java.simpleName)
            .contains(CallerWithoutFallback::class.java.simpleName)
            .doesNotContain(CallerWithGoodFallback::class.java.simpleName)
    }
}
