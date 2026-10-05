package banghak.stock.shared.web

import banghak.stock.core.domain.error.AssetUnavailableException
import banghak.stock.core.domain.error.AuthenticationFailedException
import banghak.stock.core.domain.error.BrokerAccessDeniedException
import banghak.stock.core.domain.error.BrokerUnavailableException
import banghak.stock.core.domain.error.ConfirmationRequiredException
import banghak.stock.core.domain.error.ConsentRequiredException
import banghak.stock.core.domain.error.CurrencyMismatchException
import banghak.stock.core.domain.error.DomainException
import banghak.stock.core.domain.error.ForbiddenException
import banghak.stock.core.domain.error.GuardrailViolationException
import banghak.stock.core.domain.error.IllegalSetupTransitionException
import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.error.OrderRejectedException
import banghak.stock.core.domain.error.OrderResultUnknownException
import banghak.stock.core.domain.error.RegistrationCodeInvalidException
import banghak.stock.core.domain.error.SecretStoreFailureException
import banghak.stock.core.domain.error.SessionInvalidException
import banghak.stock.core.domain.error.TooManyUsersException
import banghak.stock.core.domain.error.TotpRejectedException
import banghak.stock.core.domain.error.WeakPasswordException
import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/**
 * 도메인 예외를 `{code, message}` 응답으로 바꿈.
 * 주문 경로의 예외는 화면이 그대로 보일 상세(위반 규칙·확인할 노트·호가 단위 제안)를 함께 실음.
 */
@RestControllerAdvice
class ErrorAdvice {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class ErrorBody(
        val code: String,
        val message: String,
        val violations: List<String>? = null,
        val notes: List<String>? = null,
        val tickSize: String? = null,
        val nearestPrices: List<String>? = null,
    )

    @ExceptionHandler(DomainException::class)
    fun handle(exception: DomainException): ResponseEntity<ErrorBody> =
        ResponseEntity.status(statusOf(exception)).body(bodyOf(exception))

    // 본문·쿼리를 읽지 못한 요청도 도메인 예외와 같은 모양으로 돌려줌
    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MissingServletRequestParameterException::class,
        MethodArgumentTypeMismatchException::class,
    )
    fun handleMalformed(exception: Exception): ResponseEntity<ErrorBody> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorBody(MALFORMED_REQUEST, "요청을 읽을 수 없음"))

    private fun statusOf(exception: DomainException): HttpStatus =
        when (exception) {
            is InvalidValueException,
            is WeakPasswordException,
            is CurrencyMismatchException,
            is RegistrationCodeInvalidException -> HttpStatus.BAD_REQUEST
            is TotpRejectedException,
            is AuthenticationFailedException,
            is SessionInvalidException -> HttpStatus.UNAUTHORIZED
            is ForbiddenException -> HttpStatus.FORBIDDEN
            is IllegalSetupTransitionException,
            is TooManyUsersException -> HttpStatus.CONFLICT
            is ConfirmationRequiredException,
            is ConsentRequiredException -> HttpStatus.PRECONDITION_REQUIRED
            is SecretStoreFailureException,
            is BrokerUnavailableException,
            is BrokerAccessDeniedException,
            is MarketDataUnavailableException,
            is AssetUnavailableException,
            is OrderResultUnknownException -> HttpStatus.SERVICE_UNAVAILABLE
            else -> HttpStatus.UNPROCESSABLE_CONTENT
        }

    private fun bodyOf(exception: DomainException): ErrorBody {
        val code = exception::class.simpleName.orEmpty()
        val message = exception.message.orEmpty()
        return when (exception) {
            is GuardrailViolationException ->
                ErrorBody(code, message, violations = exception.violations)
            is ConfirmationRequiredException -> ErrorBody(code, message, notes = exception.notes)
            is OrderRejectedException ->
                ErrorBody(
                    code,
                    message,
                    tickSize = exception.tickSize?.toPlainString(),
                    nearestPrices =
                        exception.nearestPrices
                            .map { it.toPlainString() }
                            .takeIf { it.isNotEmpty() },
                )
            else -> ErrorBody(code, message)
        }
    }

    companion object {
        const val MALFORMED_REQUEST = "MalformedRequest"
    }
}
