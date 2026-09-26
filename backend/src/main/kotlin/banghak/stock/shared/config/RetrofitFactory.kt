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
 * 엔드포인트 그룹별 Retrofit 인터페이스를 빈으로 만들 때 쓰는 공장. Retrofit의 Jackson 컨버터는 Jackson 2 계열이라 Spring의 Jackson 3
 * 매퍼와 별도로 둠.
 */
@Component
class RetrofitFactory(private val client: OkHttpClient) {
    private val mapper: ObjectMapper = jsonMapper {
        addModule(kotlinModule())
        disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    }

    fun create(baseUrl: HttpUrl): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(JacksonConverterFactory.create(mapper))
            .build()
}
