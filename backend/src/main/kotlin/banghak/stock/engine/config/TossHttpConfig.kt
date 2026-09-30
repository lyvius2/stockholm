package banghak.stock.engine.config

import banghak.stock.engine.adapter.out.toss.TossAccountClient
import banghak.stock.engine.adapter.out.toss.TossAuthClient
import banghak.stock.engine.adapter.out.toss.TossAuthInterceptor
import banghak.stock.engine.adapter.out.toss.TossChartClient
import banghak.stock.engine.adapter.out.toss.TossFeedSettings
import banghak.stock.engine.adapter.out.toss.TossIndicatorClient
import banghak.stock.engine.adapter.out.toss.TossMarketInfoClient
import banghak.stock.engine.adapter.out.toss.TossOrderClient
import banghak.stock.engine.adapter.out.toss.TossPriceClient
import banghak.stock.engine.adapter.out.toss.TossRankingClient
import banghak.stock.engine.adapter.out.toss.TossStockClient
import banghak.stock.engine.adapter.out.toss.TossTokenAuthenticator
import banghak.stock.engine.adapter.out.toss.TossTokenCache
import banghak.stock.shared.config.RetrofitFactory
import banghak.stock.shared.config.RuntimeProfiles
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Profile
import retrofit2.Retrofit

/**
 * 토스 Retrofit 인터페이스 빈.
 * 토큰 발급은 공용 클라이언트로, 나머지는 인증 인터셉터·401 갱신을 더한 전용 클라이언트로 부름.
 * 전용 클라이언트는 빈으로 두지 않음(공용 OkHttpClient 빈과 겹치지 않게).
 */
@Configuration
@Profile(RuntimeProfiles.ENGINE)
class TossHttpConfig(
    private val retrofit: RetrofitFactory,
    private val endpoints: ExternalEndpointProperties,
    private val sharedClient: OkHttpClient,
) {
    @Bean
    fun tossAuthClient(): TossAuthClient =
        retrofit.create(endpoints.tossBaseUrl.toHttpUrl()).create(TossAuthClient::class.java)

    @Bean
    fun tossPriceClient(@Lazy tokens: TossTokenCache): TossPriceClient =
        authorized(tokens).create(TossPriceClient::class.java)

    @Bean
    fun tossChartClient(@Lazy tokens: TossTokenCache): TossChartClient =
        authorized(tokens).create(TossChartClient::class.java)

    @Bean
    fun tossMarketInfoClient(@Lazy tokens: TossTokenCache): TossMarketInfoClient =
        authorized(tokens).create(TossMarketInfoClient::class.java)

    @Bean
    fun tossStockClient(@Lazy tokens: TossTokenCache): TossStockClient =
        authorized(tokens).create(TossStockClient::class.java)

    @Bean
    fun tossRankingClient(@Lazy tokens: TossTokenCache): TossRankingClient =
        authorized(tokens).create(TossRankingClient::class.java)

    @Bean
    fun tossIndicatorClient(@Lazy tokens: TossTokenCache): TossIndicatorClient =
        authorized(tokens).create(TossIndicatorClient::class.java)

    @Bean
    fun tossAccountClient(@Lazy tokens: TossTokenCache): TossAccountClient =
        authorized(tokens).create(TossAccountClient::class.java)

    /**
     * 주문 클라이언트는 OkHttp 자동 재시도를 끔.
     * 끊긴 연결에서 주문 POST 가 조용히 다시 가지 않게 함.
     */
    @Bean
    fun tossOrderClient(@Lazy tokens: TossTokenCache): TossOrderClient =
        retrofit
            .create(
                endpoints.tossBaseUrl.toHttpUrl(),
                authorizedClient(tokens).newBuilder().retryOnConnectionFailure(false).build(),
            )
            .create(TossOrderClient::class.java)

    @Bean fun tossFeedSettings(): TossFeedSettings = TossFeedSettings()

    private fun authorized(tokens: TossTokenCache): Retrofit =
        retrofit.create(endpoints.tossBaseUrl.toHttpUrl(), authorizedClient(tokens))

    private fun authorizedClient(tokens: TossTokenCache): OkHttpClient =
        sharedClient
            .newBuilder()
            .addInterceptor(TossAuthInterceptor(tokens))
            .authenticator(TossTokenAuthenticator(tokens))
            .build()
}
