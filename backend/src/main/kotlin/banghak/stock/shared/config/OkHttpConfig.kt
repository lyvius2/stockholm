package banghak.stock.shared.config

import okhttp3.OkHttpClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 모든 외부 API가 공유하는 `OkHttpClient` 하나. 로깅 인터셉터를 두지 않음. 응답 원문·헤더·토큰이 로그에 남는 경로를 처음부터 만들지 않기 위함. */
@Configuration
class OkHttpConfig {
    @Bean
    fun okHttpClient(http: HttpProperties): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(http.connectTimeout)
            .readTimeout(http.readTimeout)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder().header("User-Agent", http.userAgent).build()
                )
            }
            .build()
}
