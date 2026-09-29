package banghak.stock.shared.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.springframework.stereotype.Component
import retrofit2.Retrofit
import retrofit2.converter.jackson.JacksonConverterFactory

/**
 * 엔드포인트 그룹별 Retrofit 인터페이스를 빈으로 만들 때 쓰는 공장.
 * Retrofit의 Jackson 컨버터는 Jackson 2 계열이라 Spring의 Jackson 3 매퍼와 별도로 둠.
 */
@Component
class RetrofitFactory(private val client: OkHttpClient) {
    private val mapper: ObjectMapper = jsonMapper {
        addModule(kotlinModule())
        disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    }

    fun create(baseUrl: HttpUrl): Retrofit = create(baseUrl, client)

    /**
     * 인증 인터셉터 등을 더한 전용 클라이언트로 만들 때 씀.
     * 공용 클라이언트에서 [OkHttpClient.newBuilder] 로 파생할 것.
     */
    fun create(baseUrl: HttpUrl, client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(JacksonConverterFactory.create(mapper))
            .build()
}
