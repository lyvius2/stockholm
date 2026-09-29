package banghak.stock.engine.adapter.out.toss

import retrofit2.Call
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag

/**
 * 토큰 발급.
 * 인증 인터셉터가 없는 공용 클라이언트로 부름(재귀 방지).
 */
interface TossAuthClient {
    @FormUrlEncoded
    @POST("oauth2/token")
    fun token(
        @Field("grant_type") grantType: String,
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String,
    ): Call<TossTokenResponse>
}

/**
 * MARKET_DATA 그룹(초당 15).
 * `@Tag` 로 누구의 키로 부를지 알림.
 */
interface TossPriceClient {
    @GET("api/v1/prices")
    fun prices(
        @Tag caller: TossCaller,
        @Query("symbols") symbols: String,
    ): Call<TossEnvelope<List<TossPrice>>>

    @GET("api/v1/orderbook")
    fun orderbook(
        @Tag caller: TossCaller,
        @Query("symbol") symbol: String,
    ): Call<TossEnvelope<TossOrderbook>>
}

/** MARKET_DATA_CHART 그룹(초당 20). */
interface TossChartClient {
    @GET("api/v1/candles")
    fun candles(
        @Tag caller: TossCaller,
        @Query("symbol") symbol: String,
        @Query("interval") interval: String,
        @Query("count") count: Int,
        @Query("before") before: String?,
    ): Call<TossEnvelope<TossCandles>>
}

/** MARKET_INFO 그룹(초당 3). */
interface TossMarketInfoClient {
    @GET("api/v1/exchange-rate")
    fun exchangeRate(
        @Tag caller: TossCaller,
        @Query("baseCurrency") baseCurrency: String,
        @Query("quoteCurrency") quoteCurrency: String,
    ): Call<TossEnvelope<TossExchangeRate>>

    @GET("api/v1/market-calendar/KR")
    fun koreanCalendar(
        @Tag caller: TossCaller,
        @Query("date") date: String,
    ): Call<TossEnvelope<TossKrCalendar>>

    @GET("api/v1/market-calendar/US")
    fun usCalendar(
        @Tag caller: TossCaller,
        @Query("date") date: String,
    ): Call<TossEnvelope<TossUsCalendar>>
}

/** STOCK 그룹(초당 5, 종목 정보·매수 유의사항)과 STOCK_ALL 그룹(초당 1, 시장별 전체 종목). */
interface TossStockClient {
    @GET("api/v1/stocks")
    fun stocks(
        @Tag caller: TossCaller,
        @Query("symbols") symbols: String,
    ): Call<TossEnvelope<List<TossStockInfo>>>

    @GET("api/v1/stocks/all")
    fun listedStocks(
        @Tag caller: TossCaller,
        @Query("market") market: String,
    ): Call<TossEnvelope<List<TossListedStock>>>

    @GET("api/v1/stocks/{symbol}/warnings")
    fun warnings(
        @Tag caller: TossCaller,
        @Path("symbol") symbol: String,
    ): Call<TossEnvelope<List<TossStockWarning>>>
}
