package banghak.stock

import banghak.stock.shared.config.RuntimeProfiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.test.context.ActiveProfiles

/** 데몬이 127.0.0.1 에 뜨고 헬스 엔드포인트가 응답함. Electron 셸의 기동 대기가 이 응답을 봄. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles(RuntimeProfiles.ENGINE)
class LocalApiSmokeTest(@Autowired private val environment: Environment) {
    @Test
    @DisplayName("헬스 엔드포인트가 UP을 돌려줌")
    fun healthIsUp() {
        val port = environment.getRequiredProperty("local.server.port")
        val request =
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/actuator/health"))
                .GET()
                .build()

        val response =
            HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).contains("\"status\":\"UP\"")
    }
}
