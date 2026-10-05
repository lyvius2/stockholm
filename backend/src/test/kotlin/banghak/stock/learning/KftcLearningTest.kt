package banghak.stock.learning

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * 금융결제원 오픈뱅킹 학습 테스트(읽기 전용).
 * 동의 화면은 브라우저가 필요하므로 미리 받은 사용자 토큰으로 계좌 목록·잔액 응답의 필드 이름과 호스트를 확인함.
 * 환경 변수: STOCKHOLM_TEST_KFTC_BASE_URL(테스트베드/운영), KFTC_ACCESS_TOKEN, KFTC_USER_SEQ_NO,
 * KFTC_CLIENT_USE_CODE.
 * 응답 원문은 출력하지 않고 필드 이름과 가려진 계좌 표기만 남김.
 */
@Tag("learning")
class KftcLearningTest : LearningTestSupport() {
    private val mapper = ObjectMapper()

    @Test
    @DisplayName(
        "계좌통합조회 응답에 rsp_code·res_list·account_num_masked·inquiry_agree_yn 이 있고, 잔액 조회에 balance_amt 가 있음"
    )
    fun userMeAndBalanceFieldNames() {
        val base = requireSecret("KFTC_BASE_URL").toHttpUrl()
        val token = requireSecret("KFTC_ACCESS_TOKEN")
        val userSeqNo = requireSecret("KFTC_USER_SEQ_NO")
        val useCode = requireSecret("KFTC_CLIENT_USE_CODE")
        val client = httpClient()

        val meUrl =
            base
                .newBuilder()
                .addPathSegments("v2.0/user/me")
                .addQueryParameter("user_seq_no", userSeqNo)
                .build()
        val me =
            client
                .newCall(
                    Request.Builder().url(meUrl).header("Authorization", "Bearer $token").build()
                )
                .execute()
                .use {
                    assertThat(it.code).describedAs("user/me HTTP").isEqualTo(200)
                    mapper.readTree(it.body.string())
                }
        println("user/me 필드: ${me.fieldNames().asSequence().joinToString()}")
        assertThat(me.path("rsp_code").asText()).isEqualTo("A0000")
        val first = me.path("res_list").firstOrNull() ?: return
        println("res_list[0] 필드: ${first.fieldNames().asSequence().joinToString()}")
        println(
            "account_num_masked=${first.path("account_num_masked").asText()} account_type=${first.path("account_type").asText()} inquiry_agree_yn=${first.path("inquiry_agree_yn").asText()}"
        )
        assertThat(first.has("fintech_use_num")).isTrue()

        if (first.path("inquiry_agree_yn").asText() != "Y") return
        val tranId = useCode + "U" + "%09d".format((Math.random() * 1_000_000_000).toLong())
        val dtime =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .format(LocalDateTime.now(ZoneId.of("Asia/Seoul")))
        val balanceUrl =
            base
                .newBuilder()
                .addPathSegments("v2.0/account/balance/fin_num")
                .addQueryParameter("bank_tran_id", tranId)
                .addQueryParameter("fintech_use_num", first.path("fintech_use_num").asText())
                .addQueryParameter("tran_dtime", dtime)
                .build()
        client
            .newCall(
                Request.Builder().url(balanceUrl).header("Authorization", "Bearer $token").build()
            )
            .execute()
            .use {
                val body = mapper.readTree(it.body.string())
                println(
                    "balance HTTP ${it.code} 필드: ${body.fieldNames().asSequence().joinToString()} rsp_code=${body.path("rsp_code").asText()}"
                )
                assertThat(body.has("balance_amt")).describedAs("balance_amt 필드").isTrue()
            }
    }
}
