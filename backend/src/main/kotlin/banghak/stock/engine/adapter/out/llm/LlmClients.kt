package banghak.stock.engine.adapter.out.llm

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

/** 모델 목록 응답. 세 제공자가 같은 꼴(`data` 배열)임. */
data class ModelList(val data: List<ModelEntry> = emptyList())

data class ModelEntry(val id: String = "")

interface OpenAiModelsClient {
    @GET("v1/models") fun models(@Header("Authorization") authorization: String): Call<ModelList>
}

interface AnthropicModelsClient {
    @GET("v1/models")
    fun models(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String,
    ): Call<ModelList>
}

interface DeepSeekModelsClient {
    @GET("models") fun models(@Header("Authorization") authorization: String): Call<ModelList>
}

data class OllamaTags(val models: List<OllamaModel> = emptyList())

data class OllamaModel(val name: String = "")

interface OllamaTagsClient {
    @GET fun tags(@Url url: String): Call<OllamaTags>
}
