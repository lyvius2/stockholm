package banghak.stock.support

import banghak.stock.shared.config.RetrofitFactory
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import retrofit2.Retrofit

/** 키 검증기 테스트용 WireMock 한 대와 그 주소로 만든 Retrofit. */
class VerifierStub {
    val server = WireMockServer(wireMockConfig().dynamicPort())

    fun start(): VerifierStub {
        server.start()
        return this
    }

    fun stop() = server.stop()

    fun reset() = server.resetAll()

    fun retrofit(): Retrofit =
        RetrofitFactory(OkHttpClient()).create("http://127.0.0.1:${server.port()}/".toHttpUrl())

    inline fun <reified T> client(): T = retrofit().create(T::class.java)

    fun respond(mapping: MappingBuilder, status: Int, body: String = "{}") {
        server.stubFor(
            mapping.willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)
            )
        )
    }
}
