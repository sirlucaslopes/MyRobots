package my.robots.core.network

import com.squareup.moshi.JsonClass
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Chamadas HTTP (Retrofit) para um serviço de status/backup do robô.
 *
 * Atenção: hoje o app aponta para um endereço de teste (localhost), então
 * essas chamadas não chegam a um robô real. O caminho que funciona de verdade
 * é o terminal (KawasakiTerminalManager).
 */
interface RobotApiService {
    /**
     * Pergunta ao serviço o status do robô (memória, quantidade de programas...).
     */
    @GET("status")
    suspend fun getStatus(): RobotStatusResponse

    /**
     * Baixa a configuração/backup do robô como texto.
     */
    @GET("backup")
    suspend fun downloadConfig(): Response<ResponseBody>

    /**
     * Envia um backup (texto) para o robô restaurar.
     */
    @POST("restore")
    suspend fun uploadConfig(@Body file: RequestBody): Response<Unit>
}

/**
 * Resposta de status do robô.
 *
 * - status: texto como "Online".
 * - availableMemory: memória disponível/usada.
 * - programsCount / variablesCount / framesCount: quantidades.
 * - message: mensagem para mostrar na tela.
 * - program / line: programa e linha em execução (podem vir vazios).
 */
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
