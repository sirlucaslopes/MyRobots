package my.robots.data.remote

import com.squareup.moshi.JsonClass
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface RobotApiService {
    @GET("status")
    suspend fun getStatus(): RobotStatusResponse

    @GET("backup")
    suspend fun downloadConfig(): Response<ResponseBody>

    @POST("restore")
    suspend fun uploadConfig(@Body file: RequestBody): Response<Unit>
}

@JsonClass(generateAdapter = true)
data class RobotStatusResponse(
    val status: String,
    val availableMemory: Long,
    val programsCount: Int,
    val variablesCount: Int, // .trans
    val framesCount: Int,    // fr_[n]
    val message: String,
    val program: String? = null,
    val line: Int? = null
)
