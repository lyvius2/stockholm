package banghak.stock.engine.adapter.out.dart

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

/** DART 응답은 HTTP 200 에 `status` 코드로 성패를 알림. `000` 정상. */
data class DartStatus(val status: String = "", val message: String = "")

interface DartCompanyClient {
    @GET("api/company.json")
    fun company(
        @Query("crtfc_key") apiKey: String,
        @Query("corp_code") corpCode: String,
    ): Call<DartStatus>
}
