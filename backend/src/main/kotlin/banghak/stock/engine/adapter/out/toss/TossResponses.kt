package banghak.stock.engine.adapter.out.toss

import banghak.stock.core.domain.error.BrokerAccessDeniedException
import banghak.stock.core.domain.error.DomainException
import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.error.MarketDataUnavailableException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import retrofit2.Response

/**
 * 토스 응답을 결과 또는 도메인 예외로 바꿈.
 * 오류 본문은 코드만 읽고 로그·예외 메시지에 원문을 싣지 않음.
 */
internal object TossResponses {
    private val mapper = jsonMapper {
        addModule(kotlinModule())
        disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    }

    fun <T> marketResultOf(response: Response<TossEnvelope<T>>): T {
        if (response.isSuccessful) {
            return response.body()?.result ?: throw MarketDataUnavailableException("토스 응답에 결과가 없음")
        }
        throw marketFailure(response.code(), errorCode(response))
    }

    /**
     * 조회 fallback.
     * 이미 도메인 예외면 그대로, 연결 실패 등은 시세 없음으로 바꿈.
     */
    fun marketFallback(cause: Throwable): Nothing =
        throw (cause as? DomainException
            ?: MarketDataUnavailableException("토스 시세에 연결할 수 없음(${cause::class.simpleName})"))

    private fun marketFailure(status: Int, code: String): DomainException =
        when (status) {
            // 401 은 Authenticator 가 토큰을 한 번 갱신한 뒤에도 거부된 경우임.
            // 일시 장애가 아니라 키·권한 문제로 봄
            401 -> BrokerAccessDeniedException("토스 인증 실패. 토큰을 새로 받아도 거부됨. 키를 확인할 것($code)")
            403 -> BrokerAccessDeniedException("토스가 접속을 거부함. 허용 IP 등록을 확인할 것($code)")
            404 -> InvalidValueException("토스에 없는 대상임($code)")
            429 -> MarketDataUnavailableException("토스 호출 한도 초과($code)")
            else -> MarketDataUnavailableException("토스 오류 HTTP $status($code)")
        }

    private fun errorCode(response: Response<*>): String = runCatching {
        response.errorBody()?.string()?.let { mapper.readValue<TossErrorEnvelope>(it).error?.code }
    }
        .getOrNull()
        .orEmpty()
}
