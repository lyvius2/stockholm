package banghak.stock.engine.adapter.out.credential

import banghak.stock.core.domain.account.CredentialCheck
import retrofit2.Response

/**
 * HTTP 상태 → 검증 결과.
 * 401·403 만 키 거부임.
 * 429 와 5xx 는 키와 무관한 일시 실패라 `Unreachable` 로 두어 나중에 다시 시도하게 함.
 * `Unreachable` 결과는 서킷 브레이커가 실패로 셈(`UnreachableResultPredicate`).
 */
internal object CredentialChecks {
    const val RATE_LIMITED = "검증 서비스 호출 한도 초과. 키 문제가 아니므로 잠시 뒤 다시 검증"

    fun fromStatus(
        response: Response<*>,
        detail: Map<String, String> = emptyMap(),
    ): CredentialCheck =
        when (response.code()) {
            in 200..299 -> CredentialCheck.Ok(detail)
            401,
            403 -> CredentialCheck.Rejected("키가 틀렸거나 허용되지 않음")
            429 -> rateLimited()
            else -> CredentialCheck.Unreachable("HTTP ${response.code()}")
        }

    fun rateLimited(): CredentialCheck = CredentialCheck.Unreachable(RATE_LIMITED)

    /** 예외 메시지에는 URL(키가 든 쿼리)이 섞일 수 있어 종류 이름만 남김. */
    fun unreachable(cause: Throwable): CredentialCheck =
        CredentialCheck.Unreachable("연결할 수 없음 (${cause::class.simpleName})")
}
