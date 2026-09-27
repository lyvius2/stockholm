package banghak.stock.engine.config

import banghak.stock.engine.adapter.out.dart.DartCompanyClient
import banghak.stock.engine.adapter.out.fred.FredObservationsClient
import banghak.stock.engine.adapter.out.llm.AnthropicModelsClient
import banghak.stock.engine.adapter.out.llm.DeepSeekModelsClient
import banghak.stock.engine.adapter.out.llm.OllamaTagsClient
import banghak.stock.engine.adapter.out.llm.OpenAiModelsClient
import banghak.stock.shared.config.RetrofitFactory
import banghak.stock.shared.config.RuntimeProfiles
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/** 엔드포인트 그룹마다 Retrofit 인터페이스 하나 + 빈 하나. 공통 OkHttpClient 를 나눠 씀. */
@Configuration
@Profile(RuntimeProfiles.ENGINE)
@EnableConfigurationProperties(ExternalEndpointProperties::class)
class ExternalHttpConfig(
    private val retrofit: RetrofitFactory,
    private val endpoints: ExternalEndpointProperties,
) {
    @Bean fun openAiModelsClient(): OpenAiModelsClient = create(endpoints.openaiBaseUrl)

    @Bean fun anthropicModelsClient(): AnthropicModelsClient = create(endpoints.anthropicBaseUrl)

    @Bean fun deepSeekModelsClient(): DeepSeekModelsClient = create(endpoints.deepseekBaseUrl)

    /** Ollama 주소는 사용자가 넣는 값이라 호출마다 @Url 로 받음. 기본 주소는 자리만 채움. */
    @Bean fun ollamaTagsClient(): OllamaTagsClient = create("http://127.0.0.1:11434/")

    @Bean fun dartCompanyClient(): DartCompanyClient = create(endpoints.dartBaseUrl)

    @Bean fun fredObservationsClient(): FredObservationsClient = create(endpoints.fredBaseUrl)

    private inline fun <reified T> create(baseUrl: String): T =
        retrofit.create(baseUrl.toHttpUrl()).create(T::class.java)
}
