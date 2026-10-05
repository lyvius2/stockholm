package banghak.stock.engine.adapter.out.kftc

import com.fasterxml.jackson.annotation.JsonProperty
import retrofit2.Call
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * 금융결제원 오픈뱅킹 공동업무 API(v2.0) 중 쓰는 부분.
 * 경로·필드 이름은 공개 명세 기억에 기댐.
 *
 * TODO(학습 테스트로 확인): 테스트베드/운영 호스트, redirect URI 규칙(127.0.0.1 허용 여부), 토큰 유효기간, 응답 필드
 */
interface KftcOAuthClient {
    @FormUrlEncoded
    @POST("oauth/2.0/token")
    fun exchange(
        @Field("code") code: String,
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("redirect_uri") redirectUri: String,
        @Field("grant_type") grantType: String,
    ): Call<KftcTokenResponse>

    @FormUrlEncoded
    @POST("oauth/2.0/token")
    fun refresh(
        @Field("refresh_token") refreshToken: String,
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("scope") scope: String,
        @Field("grant_type") grantType: String,
    ): Call<KftcTokenResponse>
}

interface KftcAccountClient {
    /** 사용자 정보·등록 계좌 목록(계좌통합조회). */
    @GET("v2.0/user/me")
    fun userMe(
        @Header("Authorization") bearer: String,
        @Query("user_seq_no") userSeqNo: String,
    ): Call<KftcUserMe>

    @GET("v2.0/account/balance/fin_num")
    fun balance(
        @Header("Authorization") bearer: String,
        @Query("bank_tran_id") bankTranId: String,
        @Query("fintech_use_num") fintechUseNum: String,
        @Query("tran_dtime") tranDtime: String,
    ): Call<KftcBalance>
}

/**
 * 토큰 응답.
 * 실패는 HTTP 200 에 `rsp_code` 로 올 수 있음.
 */
data class KftcTokenResponse(
    @JsonProperty("access_token") val accessToken: String? = null,
    @JsonProperty("token_type") val tokenType: String? = null,
    @JsonProperty("expires_in") val expiresIn: Long? = null,
    @JsonProperty("refresh_token") val refreshToken: String? = null,
    val scope: String? = null,
    @JsonProperty("user_seq_no") val userSeqNo: String? = null,
    @JsonProperty("rsp_code") val rspCode: String? = null,
    @JsonProperty("rsp_message") val rspMessage: String? = null,
)

data class KftcUserMe(
    @JsonProperty("rsp_code") val rspCode: String = "",
    @JsonProperty("rsp_message") val rspMessage: String = "",
    @JsonProperty("res_cnt") val resCnt: String = "0",
    @JsonProperty("res_list") val resList: List<KftcAccount> = emptyList(),
)

/** 계좌번호는 가려진 `account_num_masked` 만 받고 원문 필드는 읽지 않음. */
data class KftcAccount(
    @JsonProperty("fintech_use_num") val fintechUseNum: String = "",
    @JsonProperty("account_alias") val accountAlias: String = "",
    @JsonProperty("bank_name") val bankName: String = "",
    @JsonProperty("account_num_masked") val accountNumMasked: String = "",
    @JsonProperty("account_type") val accountType: String = "",
    @JsonProperty("inquiry_agree_yn") val inquiryAgreeYn: String = "N",
)

data class KftcBalance(
    @JsonProperty("rsp_code") val rspCode: String = "",
    @JsonProperty("rsp_message") val rspMessage: String = "",
    @JsonProperty("balance_amt") val balanceAmt: String? = null,
    @JsonProperty("available_amt") val availableAmt: String? = null,
    @JsonProperty("product_name") val productName: String? = null,
)
