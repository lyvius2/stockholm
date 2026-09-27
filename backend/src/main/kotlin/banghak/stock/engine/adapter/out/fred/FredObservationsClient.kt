package banghak.stock.engine.adapter.out.fred

import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

interface FredObservationsClient {
    @GET("fred/series/observations")
    fun observations(
        @Query("series_id") seriesId: String,
        @Query("api_key") apiKey: String,
        @Query("file_type") fileType: String,
        @Query("sort_order") sortOrder: String,
        @Query("limit") limit: Int,
    ): Call<ResponseBody>
}
