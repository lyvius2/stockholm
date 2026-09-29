package banghak.stock.engine.adapter.out.toss

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag

/**
 * 토스 주문 엔드포인트.
 * 주문 경로(`orders`)를 선언한 곳은 이 인터페이스 하나뿐이어야 함(경계 테스트).
 * 이 클라이언트는 OkHttp 자동 재시도를 끈 전용 클라이언트로 만듦.
 * 끊긴 연결에서 POST 가 조용히 다시 가지 않게 함.
 */
interface TossOrderClient {
    @POST("api/v1/orders")
    fun placeOrder(
        @Tag caller: TossCaller,
        @Header(ACCOUNT_HEADER) accountSeq: Long,
        @Body request: TossOrderRequest,
    ): Call<TossEnvelope<TossOrderReceipt>>

    @POST("api/v1/orders/{orderId}/modify")
    fun modifyOrder(
        @Tag caller: TossCaller,
        @Header(ACCOUNT_HEADER) accountSeq: Long,
        @Path("orderId") orderId: String,
        @Body request: TossModifyRequest,
    ): Call<TossEnvelope<TossOrderReceipt>>

    @POST("api/v1/orders/{orderId}/cancel")
    fun cancelOrder(
        @Tag caller: TossCaller,
        @Header(ACCOUNT_HEADER) accountSeq: Long,
        @Path("orderId") orderId: String,
        @Body empty: Map<String, String>,
    ): Call<TossEnvelope<TossOrderReceipt>>

    @GET("api/v1/orders/{orderId}")
    fun order(
        @Tag caller: TossCaller,
        @Header(ACCOUNT_HEADER) accountSeq: Long,
        @Path("orderId") orderId: String,
    ): Call<TossEnvelope<TossOrder>>

    @GET("api/v1/orders")
    fun orders(
        @Tag caller: TossCaller,
        @Header(ACCOUNT_HEADER) accountSeq: Long,
        @Query("status") status: String,
        @Query("from") from: String?,
        @Query("to") to: String?,
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int?,
    ): Call<TossEnvelope<TossOrders>>
}

/** 계좌·자산·주문 정보 엔드포인트(주문 경로 아님). */
interface TossAccountClient {
    @GET("api/v1/accounts")
    fun accounts(@Tag caller: TossCaller): Call<TossEnvelope<List<TossAccount>>>

    @GET("api/v1/holdings")
    fun holdings(
        @Tag caller: TossCaller,
        @Header(ACCOUNT_HEADER) accountSeq: Long,
    ): Call<TossEnvelope<TossHoldings>>

    @GET("api/v1/buying-power")
    fun buyingPower(
        @Tag caller: TossCaller,
        @Header(ACCOUNT_HEADER) accountSeq: Long,
        @Query("currency") currency: String,
    ): Call<TossEnvelope<TossBuyingPower>>
}

const val ACCOUNT_HEADER = "X-Tossinvest-Account"
