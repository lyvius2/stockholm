package banghak.stock.engine.adapter.out.llm

import banghak.stock.core.domain.account.CredentialCheck
import retrofit2.Response

/** HTTP 상태 → 검증 결과. 4xx 는 키 문제라 서킷을 열지 않고, 예외(연결 실패)만 fallback 으로 감. */
internal object CredentialChecks {
    fun fromStatus(
        response: Response<*>,
        detail: Map<String, String> = emptyMap(),
    ): CredentialCheck =
        when (response.code()) {
            in 200..299 -> CredentialCheck.Ok(detail)
            401,
            403 -> CredentialCheck.Rejected("키가 틀렸거나 허용되지 않음")
            429 -> CredentialCheck.Rejected("호출 한도 초과, 잠시 뒤 다시")
            else -> CredentialCheck.Unreachable("HTTP ${response.code()}")
        }

    /** 예외 메시지에는 URL(키가 든 쿼리)이 섞일 수 있어 종류 이름만 남김. */
    fun unreachable(cause: Throwable): CredentialCheck =
        CredentialCheck.Unreachable("연결할 수 없음 (${cause::class.simpleName})")
}
