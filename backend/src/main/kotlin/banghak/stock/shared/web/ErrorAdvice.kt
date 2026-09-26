package banghak.stock.shared.web

import banghak.stock.core.domain.error.CurrencyMismatchException
import banghak.stock.core.domain.error.DomainException
import banghak.stock.core.domain.error.IllegalSetupTransitionException
import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.error.SecretStoreFailureException
import banghak.stock.core.domain.error.TooManyUsersException
import banghak.stock.core.domain.error.TotpRejectedException
import banghak.stock.core.domain.error.WeakPasswordException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ErrorAdvice {
    data class ErrorBody(val code: String, val message: String)

    @ExceptionHandler(DomainException::class)
    fun handle(exception: DomainException): ResponseEntity<ErrorBody> {
        val status =
            when (exception) {
                is InvalidValueException,
                is WeakPasswordException,
                is CurrencyMismatchException -> HttpStatus.BAD_REQUEST
                is TotpRejectedException -> HttpStatus.UNAUTHORIZED
                is IllegalSetupTransitionException,
                is TooManyUsersException -> HttpStatus.CONFLICT
                is SecretStoreFailureException -> HttpStatus.SERVICE_UNAVAILABLE
                else -> HttpStatus.UNPROCESSABLE_CONTENT
            }
        return ResponseEntity.status(status)
            .body(ErrorBody(exception::class.simpleName.orEmpty(), exception.message.orEmpty()))
    }
}
