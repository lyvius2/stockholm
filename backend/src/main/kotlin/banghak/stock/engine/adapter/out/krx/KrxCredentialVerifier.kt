package banghak.stock.engine.adapter.out.krx

import banghak.stock.core.domain.account.CredentialCheck
import banghak.stock.core.domain.account.CredentialFields
import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.port.CredentialVerifier
import banghak.stock.engine.adapter.out.credential.CredentialChecks
import banghak.stock.engine.adapter.out.credential.RevealedSecrets.asString
import banghak.stock.shared.config.RuntimeProfiles
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

data class KrxDailyRequest(val basDd: String)

/** 값은 전부 문자열이고 행 모양은 서비스마다 다르므로 검증은 행 수만 봄. */
data class KrxOutBlock(
    @get:com.fasterxml.jackson.annotation.JsonProperty("OutBlock_1")
    val rows: List<Map<String, String>> = emptyList()
)

/**
 * KRX Open API 는 모든 서비스가 같은 모양임.
 * `POST svc/apis/{그룹}/{서비스}`, 헤더 `AUTH_KEY`, 본문 `{"basDd":"YYYYMMDD"}`.
 *
 * TODO(학습 테스트로 확인): JSON 본문 POST 가 되는지, GET 쿼리만 받는지(KRX_DESIGN 2장 [확인 필요])
 */
interface KrxDailyTradeClient {
    @POST("svc/apis/sto/stk_bydd_trd")
    fun kospiDaily(
        @Header("AUTH_KEY") authKey: String,
        @Body request: KrxDailyRequest,
    ): Call<KrxOutBlock>
}

/**
 * 유가증권 일별매매정보 한 날로 키를 확인함.
 * 기준일은 달력 계산 없이 지난 거래일 하나를 상수로 둠(일별 확정치는 2010년부터 제공).
 * 빈 응답은 키가 틀렸다는 증거가 아니라 기준일·전송 방식 문제일 수 있어 일시 실패로 둠.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class KrxCredentialVerifier(private val client: KrxDailyTradeClient) : CredentialVerifier {
    override val kind = CredentialKind.KRX

    @CircuitBreaker(name = "krx-verify", fallbackMethod = "unreachable")
    override fun verify(fields: Map<String, SecretValue>): CredentialCheck {
        val response =
            client
                .kospiDaily(
                    asString(fields.getValue(CredentialFields.VALUE)),
                    KrxDailyRequest(PROBE_TRADING_DAY),
                )
                .execute()
        if (!response.isSuccessful) return CredentialChecks.fromStatus(response)
        val rows = response.body()?.rows.orEmpty()
        if (rows.isEmpty())
            return CredentialCheck.Unreachable("KRX 응답에 자료가 없음(기준일 $PROBE_TRADING_DAY)")
        return CredentialCheck.Ok(
            mapOf("rowCount" to rows.size.toString(), "basDd" to PROBE_TRADING_DAY)
        )
    }

    fun unreachable(fields: Map<String, SecretValue>, cause: Throwable): CredentialCheck =
        CredentialChecks.unreachable(cause)

    companion object {
        // 2025-01-02(목)은 KRX 개장일임
        const val PROBE_TRADING_DAY = "20250102"
    }
}
