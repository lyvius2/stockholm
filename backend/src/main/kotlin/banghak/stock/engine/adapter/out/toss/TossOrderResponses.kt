package banghak.stock.engine.adapter.out.toss

import banghak.stock.core.domain.error.BrokerAccessDeniedException
import banghak.stock.core.domain.error.BrokerUnavailableException
import banghak.stock.core.domain.error.DomainException
import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.error.OrderRejectedException
import banghak.stock.core.domain.error.OrderResultUnknownException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.ratelimiter.RequestNotPermitted
import java.io.IOException
import java.math.BigDecimal
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import retrofit2.Call
import retrofit2.Response

/**
 * 토스 주문 응답과 전송 실패를 도메인 예외로 바꿈.
 * 원칙: 요청이 증권사에 닿았는지 모르면 [OrderResultUnknownException] 으로 올려 조회 전 재시도를 막음.
 * 요청이 나가지 않은 것이 확실할 때만 [BrokerUnavailableException](주문 안 됨)임.
 */
internal object TossOrderResponses {
    private val mapper = jsonMapper {
        addModule(kotlinModule())
        disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    }

    /**
     * 주문·정정·취소처럼 상태를 바꾸는 요청.
     * 보낸 뒤의 실패는 결과를 모르는 것으로 봄.
     */
    fun receiptOf(call: Call<TossEnvelope<TossOrderReceipt>>): TossOrderReceipt =
        mutationResultOf(call)

    /** 결과 본문이 있는 변경 요청(조건주문 등록·수정 포함). */
    fun <T> mutationResultOf(call: Call<TossEnvelope<T>>): T {
        val response = send(call)
        if (response.isSuccessful) {
            return response.body()?.result
                ?: throw OrderResultUnknownException("토스가 결과 없이 성공 응답을 줌")
        }
        throw mutationFailure(response.code(), errorOf(response))
    }

    /** 본문 없이 성공하는 변경 요청(조건주문 취소 204). */
    fun completionOf(call: Call<Unit>) {
        val response = send(call)
        if (!response.isSuccessful) throw mutationFailure(response.code(), errorOf(response))
    }

    private fun <T> send(call: Call<T>): Response<T> =
        try {
            call.execute()
        } catch (e: IOException) {
            throw sendFailure(e)
        }

    /**
     * 조회 요청.
     * 실패해도 주문에는 영향이 없으므로 증권사 연결 실패로 봄.
     */
    fun <T> readResultOf(call: Call<TossEnvelope<T>>): T {
        val response =
            try {
                call.execute()
            } catch (e: IOException) {
                throw BrokerUnavailableException("토스에 연결할 수 없음(${e::class.simpleName})", e)
            }
        if (response.isSuccessful) {
            return response.body()?.result ?: throw BrokerUnavailableException("토스 응답에 결과가 없음")
        }
        throw readFailure(response.code(), errorOf(response).code)
    }

    /** 성공 응답에 필수 값이 없으면 0 으로 읽지 않고 조회 실패로 올림. */
    fun <T : Any> required(value: T?, label: String): T =
        value ?: throw BrokerUnavailableException("토스 응답에 $label 값이 없음")

    /**
     * 돈이 걸린 경로의 fallback.
     * 새로 주문하거나 재시도하지 않음.
     * 서킷·호출 한도로 막힌 요청은 나가지 않았으므로 주문 안 됨.
     * 그 밖의 알 수 없는 오류는 결과 모름으로 봄.
     */
    fun mutationFallback(cause: Throwable): Nothing =
        throw when (cause) {
            is DomainException -> cause
            is CallNotPermittedException,
            is RequestNotPermitted -> BrokerUnavailableException("토스 주문 경로가 잠시 막혀 주문하지 않음")
            else -> OrderResultUnknownException("주문 결과를 알 수 없음(${cause::class.simpleName})", cause)
        }

    fun readFallback(cause: Throwable): Nothing =
        throw (cause as? DomainException
            ?: BrokerUnavailableException("토스에 연결할 수 없음(${cause::class.simpleName})"))

    // 연결 단계에서 실패하면 요청이 나가지 않은 것이 확실함.
    // 읽기 타임아웃 등은 증권사가 처리했을 수 있음
    private fun sendFailure(e: IOException): DomainException =
        if (isBeforeSend(e))
            BrokerUnavailableException("토스에 연결하지 못해 주문하지 않음(${e::class.simpleName})", e)
        else OrderResultUnknownException("주문을 보냈으나 응답을 받지 못함(${e::class.simpleName})", e)

    private fun isBeforeSend(e: IOException): Boolean =
        e is ConnectException ||
            e is UnknownHostException ||
            e is NoRouteToHostException ||
            e is SSLHandshakeException ||
            (e is SocketTimeoutException &&
                e.message.orEmpty().contains("connect", ignoreCase = true))

    private fun mutationFailure(status: Int, error: TossError): DomainException =
        when {
            status == 400 ->
                OrderRejectedException(
                    "주문 요청이 올바르지 않음(${error.code})",
                    error.tickSize,
                    error.nearestPrices,
                )
            status == 401 || status == 403 ->
                BrokerAccessDeniedException("토스가 주문을 거부함. 키·허용 IP 를 확인할 것(${error.code})")
            // 같은 멱등 키의 요청이 처리 중이면 이미 접수됐을 수 있음
            status == 409 && error.code == REQUEST_IN_PROGRESS ->
                OrderResultUnknownException("같은 주문이 처리 중임. 조회로 확인할 것")
            status in REJECTED_STATUSES -> OrderRejectedException("토스가 주문을 받지 않음(${error.code})")
            status == 429 -> BrokerUnavailableException("토스 주문 호출 한도 초과. 주문하지 않음")
            else -> OrderResultUnknownException("토스 주문 처리 중 오류 HTTP $status(${error.code})")
        }

    private fun readFailure(status: Int, code: String): DomainException =
        when (status) {
            401,
            403 -> BrokerAccessDeniedException("토스가 조회를 거부함. 키·허용 IP 를 확인할 것($code)")
            404 -> InvalidValueException("토스에 없는 주문·계좌임($code)")
            else -> BrokerUnavailableException("토스 조회 오류 HTTP $status($code)")
        }

    private fun errorOf(response: Response<*>): TossError =
        runCatching {
            response.errorBody()?.string()?.let {
                mapper.readValue<TossOrderErrorEnvelope>(it).error
            }
        }
            .getOrNull()
            ?.let { TossError(it.code, it.data?.tickSize, it.data?.nearestPrices.orEmpty()) }
            ?: TossError("", null, emptyList())

    private const val REQUEST_IN_PROGRESS = "request-in-progress"
    private val REJECTED_STATUSES = setOf(404, 409, 422)
}

internal data class TossError(
    val code: String,
    val tickSize: BigDecimal?,
    val nearestPrices: List<BigDecimal>,
)

internal data class TossOrderErrorEnvelope(val error: TossOrderErrorBody? = null)

internal data class TossOrderErrorBody(val code: String = "", val data: TossOrderErrorData? = null)

internal data class TossOrderErrorData(
    val tickSize: BigDecimal? = null,
    val nearestPrices: List<BigDecimal>? = null,
)
