package banghak.stock.engine.adapter.out.fred

import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.SecretKey
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.error.SecretMissingException
import banghak.stock.core.domain.market.MacroObservation
import banghak.stock.core.domain.market.MacroSeries
import banghak.stock.engine.config.ExternalEndpointProperties
import banghak.stock.engine.config.ExternalHttpConfig
import banghak.stock.engine.config.KftcProperties
import banghak.stock.shared.config.HttpProperties
import banghak.stock.shared.config.OkHttpConfig
import banghak.stock.shared.config.RetrofitFactory
import banghak.stock.support.fakes.MemorySecretStore
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import java.math.BigDecimal
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * FRED 관측값을 WireMock 으로 검증함.
 * 실제 FRED 는 부르지 않음.
 */
class FredMacroIndicatorAdapterTest {
    private val server = WireMockServer(wireMockConfig().dynamicPort())
    private val secrets = MemorySecretStore()
    private lateinit var adapter: FredMacroIndicatorAdapter

    @BeforeEach
    fun setUp() {
        server.start()
        val config =
            ExternalHttpConfig(
                RetrofitFactory(OkHttpConfig().okHttpClient(HttpProperties())),
                ExternalEndpointProperties(fredBaseUrl = "http://127.0.0.1:${server.port()}/"),
                KftcProperties(),
            )
        adapter = FredMacroIndicatorAdapter(config.fredObservationsClient(), secrets)
    }

    @AfterEach fun tearDown() = server.stop()

    @Test
    @DisplayName("읽은 키는 호출이 끝나면(실패해도) 지움")
    fun wipesSecretAfterUse() {
        val held = SecretValue.of("fred-key")
        val wiping =
            FredMacroIndicatorAdapter(
                ExternalHttpConfig(
                        RetrofitFactory(OkHttpConfig().okHttpClient(HttpProperties())),
                        ExternalEndpointProperties(
                            fredBaseUrl = "http://127.0.0.1:${server.port()}/"
                        ),
                        KftcProperties(),
                    )
                    .fredObservationsClient(),
                object : banghak.stock.engine.adapter.out.keychain.SecretReader {
                    override fun read(key: SecretKey) = held
                },
            )
        server.stubFor(
            get(urlPathEqualTo("/fred/series/observations")).willReturn(aResponse().withStatus(500))
        )

        assertThatThrownBy { wiping.recentObservations(MacroSeries.SP500, 1) }
            .isInstanceOf(MarketDataUnavailableException::class.java)
        assertThat(held.reveal().all { it == Char.MIN_VALUE }).isTrue()
    }

    @Test
    @DisplayName("최근 관측값을 최신순으로 주고 휴장일(\".\")은 뺌")
    fun readsObservationsAndSkipsMissingDays() {
        secrets.put(
            SecretKey.shared(CredentialKind.FRED.secretName("VALUE")),
            SecretValue.of("fred-key"),
        )
        server.stubFor(
            get(urlPathEqualTo("/fred/series/observations"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"observations":[{"date":"2026-10-02","value":"."},{"date":"2026-10-01","value":"66364.20"},{"date":"2026-09-30","value":"66000.00"},{"date":"2026-09-29","value":"65900.00"}]}"""
                        )
                )
        )

        val observations = adapter.recentObservations(MacroSeries.NIKKEI225, 2)

        assertThat(observations)
            .containsExactly(
                MacroObservation(LocalDate.of(2026, 10, 1), BigDecimal("66364.20")),
                MacroObservation(LocalDate.of(2026, 9, 30), BigDecimal("66000.00")),
            )
        server.verify(
            getRequestedFor(urlPathEqualTo("/fred/series/observations"))
                .withQueryParam("series_id", equalTo("NIKKEI225"))
                .withQueryParam("api_key", equalTo("fred-key"))
                .withQueryParam("limit", equalTo("4"))
        )
    }

    @Test
    @DisplayName("키가 없으면 출처 미설정(SecretMissing), FRED 가 거부하면 시세 없음이고 메시지에 키를 싣지 않음")
    fun missingKeyAndErrorsAreUnavailable() {
        assertThatThrownBy { adapter.recentObservations(MacroSeries.SP500, 1) }
            .isInstanceOf(SecretMissingException::class.java)

        secrets.put(
            SecretKey.shared(CredentialKind.FRED.secretName("VALUE")),
            SecretValue.of("fred-key"),
        )
        server.stubFor(
            get(urlPathEqualTo("/fred/series/observations")).willReturn(aResponse().withStatus(400))
        )

        assertThatThrownBy { adapter.recentObservations(MacroSeries.SP500, 1) }
            .isInstanceOf(MarketDataUnavailableException::class.java)
            .satisfies({ assertThat(it.message).doesNotContain("fred-key") })
    }
}
