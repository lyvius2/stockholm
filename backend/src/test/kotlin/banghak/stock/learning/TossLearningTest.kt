package banghak.stock.learning

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * 토스증권 Open API 학습 테스트(읽기 전용).
 * 토큰 발급 → 계좌 → 보유·매수 가능 금액 → 종목·시세·랭킹·달력·환율·지표 → WebSocket 순서로 실제 응답의 필드 이름을
 * 규격(docs/external/toss/openapi.json v1.2.17)과 대조함.
 * 주문·정정·취소는 절대 부르지 않음(`/orders` 경로 차단).
 * 응답 원문은 build/learning/toss/ 에 계좌번호를 가려 저장하고, 개인 정보가 없는 응답만 WireMock 픽스처로 남김.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TossLearningTest : LearningTestSupport() {
    private val base = "https://openapi.tossinvest.com"
    private val mapper = ObjectMapper()
    private lateinit var client: OkHttpClient
    private lateinit var accessToken: String
    private var accountSeq: Long = -1

    @BeforeAll
    fun issueToken() {
        val clientId = requireSecret("TOSS_CLIENT_ID")
        val clientSecret = requireSecret("TOSS_CLIENT_SECRET")
        client = httpClient()
        val body =
            FormBody.Builder()
                .add("grant_type", "client_credentials")
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .build()
        client
            .newCall(Request.Builder().url("$base/oauth2/token").post(body).build())
            .execute()
            .use { response ->
                val json = mapper.readTree(response.body.string())
                assertThat(response.code)
                    .describedAs("토큰 발급 HTTP ${response.code} error=${json.path("error").asText()}")
                    .isEqualTo(200)
                assertThat(json.path("token_type").asText()).isEqualTo("Bearer")
                assertThat(json.path("expires_in").asLong()).isPositive()
                accessToken = json.path("access_token").asText()
                saveRaw("oauth2-token", (json as ObjectNode).put("access_token", "***"))
                savePublicFixture("oauth2-token", json)
            }
    }

    @Test
    @Order(1)
    @DisplayName("계좌 목록: accountNo·accountSeq·accountType, 종합매매(BROKERAGE) 계좌가 있음")
    fun accounts() {
        val json = get("/api/v1/accounts", "accounts")
        val accounts = json.path("result")
        assertThat(accounts.isArray).isTrue()
        val brokerage = accounts.first { it.path("accountType").asText() == "BROKERAGE" }
        assertThat(brokerage.path("accountSeq").isNumber).isTrue()
        assertThat(brokerage.path("accountNo").asText()).isNotBlank()
        accountSeq = brokerage.path("accountSeq").asLong()
    }

    @Test
    @Order(2)
    @DisplayName("보유 주식: 요약(총매입·평가·손익·일간)과 종목별 평가금액이 통화별로 오고 원화 환산 필드는 krw 임")
    fun holdings() {
        val json = get("/api/v1/holdings", "holdings", account = true)
        val result = json.path("result")
        for (field in
            listOf(
                "totalPurchaseAmount",
                "marketValue",
                "profitLoss",
                "dailyProfitLoss",
                "items",
            )) assertThat(result.has(field)).describedAs(field).isTrue()
        assertThat(result.path("marketValue").path("amount").has("krw")).isTrue()
        for (item in result.path("items")) {
            for (field in
                listOf(
                    "symbol",
                    "marketCountry",
                    "currency",
                    "quantity",
                    "lastPrice",
                    "averagePurchasePrice",
                    "marketValue",
                    "profitLoss",
                )) assertThat(item.has(field)).describedAs(field).isTrue()
            assertThat(item.path("marketValue").has("amount")).isTrue()
        }
        println(
            "학습: 보유 종목 수 = ${result.path("items").size()}, 평가금액 krw 존재 = ${result.path("marketValue").path("amount").has("krw")}"
        )
    }

    @Test
    @Order(3)
    @DisplayName("매수 가능 금액: 통화별 cashBuyingPower 만 옴(D+1/D+2 없음)")
    fun buyingPower() {
        for (currency in listOf("KRW", "USD")) {
            val json =
                get(
                    "/api/v1/buying-power?currency=$currency",
                    "buying-power-$currency",
                    account = true,
                )
            assertThat(json.path("result").path("currency").asText()).isEqualTo(currency)
            assertThat(json.path("result").path("cashBuyingPower").isTextual).isTrue()
            assertThat(json.path("result").fieldNames().asSequence().toList())
                .containsExactlyInAnyOrder("currency", "cashBuyingPower")
        }
        val fees = get("/api/v1/commissions", "commissions", account = true)
        assertThat(fees.path("result").first().has("commissionRate")).isTrue()
    }

    @Test
    @Order(4)
    @DisplayName("종목 정보·경고: 삼성전자·엔비디아의 ISIN·market·securityType·status, 경고 목록 형식")
    fun stocksAndWarnings() {
        val json = get("/api/v1/stocks?symbols=005930,NVDA", "stocks", public = true)
        val stocks = json.path("result")
        assertThat(stocks.size()).isEqualTo(2)
        val samsung = stocks.first { it.path("symbol").asText() == "005930" }
        assertThat(samsung.path("isinCode").asText()).isEqualTo("KR7005930003")
        assertThat(samsung.path("market").asText()).isEqualTo("KOSPI")
        assertThat(samsung.path("koreanMarketDetail").has("nxtSupported")).isTrue()
        val warnings = get("/api/v1/stocks/005930/warnings", "warnings-005930", public = true)
        assertThat(warnings.path("result").isArray).isTrue()
        val all =
            get(
                "/api/v1/stocks/all?market=KOSDAQ&securityType=STOCK",
                "stocks-all-kosdaq",
                public = false,
            )
        println("학습: KOSDAQ STOCK 종목 수 = ${all.path("result").size()}")
    }

    @Test
    @Order(5)
    @DisplayName("현재가 다건·캔들(1분·일)·시장 지표: timestamp 형식과 nextBefore 페이지네이션")
    fun pricesAndCandles() {
        val prices = get("/api/v1/prices?symbols=005930,000660,NVDA", "prices", public = true)
        assertThat(prices.path("result").size()).isEqualTo(3)
        val daily =
            get(
                "/api/v1/candles?symbol=005930&interval=1d&count=5",
                "candles-005930-1d",
                public = true,
            )
        val candles = daily.path("result").path("candles")
        assertThat(candles.size()).isBetween(1, 5)
        assertThat(candles.first().fieldNames().asSequence().toList())
            .containsExactlyInAnyOrder(
                "timestamp",
                "openPrice",
                "highPrice",
                "lowPrice",
                "closePrice",
                "volume",
                "currency",
            )
        println(
            "학습: 일봉 timestamp 예 = ${candles.first().path("timestamp").asText()}, nextBefore = ${daily.path("result").path("nextBefore")}"
        )
        val minute =
            get(
                "/api/v1/candles?symbol=005930&interval=1m&count=3",
                "candles-005930-1m",
                public = true,
            )
        println(
            "학습: 1분봉 timestamp 예 = ${minute.path("result").path("candles").firstOrNull()?.path("timestamp")}"
        )
        val indicators =
            get(
                "/api/v1/market-indicators/prices?symbols=KOSPI,KOSDAQ",
                "market-indicators",
                public = true,
            )
        assertThat(indicators.path("result").size()).isEqualTo(2)
    }

    @Test
    @Order(6)
    @DisplayName("랭킹: TOP_GAINERS 는 1d 만, 거래대금은 realtime 가능, rankedAt 과 price.changeRate 가 옴")
    fun rankings() {
        val gainers =
            get(
                "/api/v1/rankings?type=TOP_GAINERS&marketCountry=KR&duration=1d&count=5",
                "rankings-kr-gainers-1d",
                public = true,
            )
        assertThat(gainers.path("result").path("rankings").first().path("price").has("changeRate"))
            .isTrue()
        val amount =
            get(
                "/api/v1/rankings?type=MARKET_TRADING_AMOUNT&marketCountry=US&duration=realtime&count=5",
                "rankings-us-amount-realtime",
                public = true,
            )
        assertThat(amount.path("result").has("rankedAt")).isTrue()
        val realtimeGainers =
            client
                .newCall(
                    authed(
                            "/api/v1/rankings?type=TOP_GAINERS&marketCountry=KR&duration=realtime&count=5"
                        )
                        .build()
                )
                .execute()
        realtimeGainers.use { println("학습: TOP_GAINERS realtime 응답 코드 = ${it.code}(규격상 미지원 예상)") }
    }

    @Test
    @Order(7)
    @DisplayName("장 달력·환율: KR 통합 세션과 US 네 세션이 KST 로 오고, 환율은 validFrom·validUntil 을 가짐")
    fun calendarAndFx() {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val kr = get("/api/v1/market-calendar/KR?date=$today", "market-calendar-kr", public = true)
        assertThat(kr.path("result").has("today")).isTrue()
        assertThat(kr.path("result").has("nextBusinessDay")).isTrue()
        val us = get("/api/v1/market-calendar/US", "market-calendar-us", public = true)
        assertThat(us.path("result").path("nextBusinessDay").has("regularMarket")).isTrue()
        val fx =
            get(
                "/api/v1/exchange-rate?baseCurrency=USD&quoteCurrency=KRW",
                "exchange-rate-usdkrw",
                public = true,
            )
        assertThat(fx.path("result").path("rate").isTextual).isTrue()
        assertThat(fx.path("result").has("validUntil")).isTrue()
        println(
            "학습: 오늘 KR 통합 = ${kr.path("result").path("today").path("integrated")}, USD/KRW = ${fx.path("result").path("rate").asText()}"
        )
    }

    @Test
    @Order(8)
    @DisplayName(
        "WebSocket: Bearer 헤더로 접속해 trade:kr·orderbook:kr·personal:order 구독 선언이 subscriptions ack 로 확정됨"
    )
    fun webSocketSubscribe() {
        val ack = CountDownLatch(1)
        val received = mutableListOf<String>()
        val listener =
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    synchronized(received) { received += text }
                    if (text.contains("\"subscriptions\"")) ack.countDown()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    synchronized(received) {
                        received += "FAILURE ${t::class.simpleName} ${response?.code}"
                    }
                    ack.countDown()
                }
            }
        val socket =
            client.newWebSocket(
                Request.Builder()
                    .url("wss://openapi-ws.tossinvest.com/ws/v1")
                    .header("Authorization", "Bearer $accessToken")
                    .build(),
                listener,
            )
        val declare =
            """[{"id":"req-1"},{"type":"trade:kr","codes":["005930"]},{"type":"orderbook:kr","codes":["005930"]},{"type":"personal:order","codes":["$accountSeq"]}]"""
        socket.send(declare)
        assertThat(ack.await(10, TimeUnit.SECONDS)).describedAs("ack 대기").isTrue()
        socket.send("PING")
        Thread.sleep(3000)
        socket.close(1000, "learning done")
        val messages = synchronized(received) { received.toList() }
        // personal:order 메시지에는 주문 정보가 실리므로 원문은 남기지 않고 종류·토픽만 기록함
        val summaries = messages.map(::summarizeFrame)
        println("학습: WebSocket 수신 ${messages.size}건 → " + summaries.joinToString(" | "))
        val ackText = messages.first { it.contains("\"subscriptions\"") }
        assertThat(ackText).contains("trade:kr:005930").contains("personal:order:$accountSeq")
        Files.writeString(rawDir().resolve("websocket-frames.txt"), summaries.joinToString("\n"))
    }

    private fun summarizeFrame(text: String): String {
        if (text.startsWith("FAILURE")) return text
        val json =
            runCatching { mapper.readTree(text) }.getOrNull() ?: return "text:${text.take(20)}"
        val type = json.path("type").asText()
        return when (type) {
            "subscriptions" ->
                "subscriptions subscribed=${json.path("subscribed")} rejected=${json.path("rejected").size()}"
            "message" -> "message topic=${json.path("topic").asText()}"
            "error" -> "error code=${json.path("error").path("code").asText()}"
            else -> type
        }
    }

    private fun get(
        path: String,
        name: String,
        account: Boolean = false,
        public: Boolean = false,
    ): JsonNode {
        val request =
            authed(path)
                .apply { if (account) header("X-Tossinvest-Account", accountSeq.toString()) }
                .build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            val json = mapper.readTree(text)
            // 실패해도 본문은 남기지 않음(계좌·주문 정보가 들어 있을 수 있음).
            // 오류 코드만 봄
            assertThat(response.code)
                .describedAs(
                    "$path → HTTP ${response.code} code=${json.path("error").path("code").asText()}"
                )
                .isEqualTo(200)
            println(
                "학습: $path limit=${response.header("X-RateLimit-Limit")} remaining=${response.header("X-RateLimit-Remaining")} requestId=${response.header("X-Request-Id")?.take(8)}…"
            )
            saveRaw(name, json)
            if (public) savePublicFixture(name, json)
            Thread.sleep(400)
            return json
        }
    }

    private fun authed(path: String): Request.Builder =
        Request.Builder().url(base + path).header("Authorization", "Bearer $accessToken")

    // 계좌번호는 끝 4자리만 남김.
    // 이 파일은 build/ 아래라 커밋되지 않지만 규칙대로 가림
    private fun saveRaw(name: String, json: JsonNode) {
        Files.writeString(
            rawDir().resolve("$name.json"),
            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(redact(json)),
        )
    }

    // 커밋된 픽스처가 실행마다 바뀌지 않게 파일이 없을 때만 씀.
    // 갱신하려면 지우고 다시 돌림
    private fun savePublicFixture(name: String, json: JsonNode) {
        val dir = Path.of("src/test/resources/wiremock/toss")
        Files.createDirectories(dir)
        val target = dir.resolve("$name.json")
        if (Files.exists(target)) return
        Files.writeString(target, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json))
    }

    private fun redact(json: JsonNode): JsonNode {
        if (json is ObjectNode) {
            for (field in json.fieldNames().asSequence().toList()) {
                val value = json.get(field)
                if (field.equals("accountNo", ignoreCase = true) && value.isTextual)
                    json.put(field, "****" + value.asText().takeLast(4))
                else redact(value)
            }
        } else if (json.isArray) json.forEach { redact(it) }
        return json
    }

    private fun rawDir(): Path = Files.createDirectories(Path.of("build/learning/toss"))
}
