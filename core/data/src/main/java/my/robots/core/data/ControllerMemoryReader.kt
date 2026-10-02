package my.robots.core.data

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import my.robots.core.common.ascode.AsFreeMemory
import my.robots.core.common.ascode.ControllerMemory
import my.robots.core.network.KawasakiTerminalManager

/**
 * Lê a memória de programas do controlador com o comando FREE e guarda a última leitura
 * de cada robô no aparelho (SharedPreferences "controller_memory"), para a tela mostrar
 * o valor mesmo sem conexão. O backup não traz essa informação.
 *
 * O comando passa pelo terminal do robô, então aparece no histórico como qualquer outro.
 */
class ControllerMemoryReader(
    context: Context,
    private val terminal: KawasakiTerminalManager
) {
    private val prefs = context.applicationContext.getSharedPreferences("controller_memory", Context.MODE_PRIVATE)
    private val readings = mutableMapOf<Int, MutableStateFlow<ControllerMemory?>>()

    /** Última leitura guardada do robô (null se nunca foi lida). */
    fun lastReading(robotId: Int): StateFlow<ControllerMemory?> =
        readings.getOrPut(robotId) { MutableStateFlow(load(robotId)) }.asStateFlow()

    /**
     * Espera o terminal ficar pronto (a última linha é o prompt ">", ou seja, o login
     * terminou) e lê a memória. Desiste depois de [timeoutMs] sem o prompt.
     */
    suspend fun readWhenReady(robotId: Int, timeoutMs: Long = 15_000): ControllerMemory? {
        var waited = 0L
        while (waited < timeoutMs) {
            if (!terminal.getConnectionStatus(robotId).value) return null
            val last = terminal.getHistory(robotId).value.lastOrNull { it.isNotBlank() }?.trim()
            if (last == ">") return read(robotId)
            delay(300)
            waited += 300
        }
        return null
    }

    /**
     * Manda FREE e espera a resposta (até [timeoutMs]). Devolve a leitura nova, ou null se
     * o robô não estiver conectado ou não responder a tempo.
     */
    suspend fun read(robotId: Int, timeoutMs: Long = 5_000): ControllerMemory? {
        if (!terminal.getConnectionStatus(robotId).value) return null
        val history = terminal.getHistory(robotId)
        val before = AsFreeMemory.countAnswers(history.value.joinToString("\n"))

        terminal.sendCommand(robotId, "FREE")

        var waited = 0L
        while (waited < timeoutMs) {
            delay(200)
            waited += 200
            val text = history.value.joinToString("\n")
            if (AsFreeMemory.countAnswers(text) > before) {
                val memory = AsFreeMemory.parseLast(text) ?: continue
                save(robotId, memory)
                return memory
            }
        }
        return null
    }

    private fun load(robotId: Int): ControllerMemory? {
        val total = prefs.getLong("total_$robotId", -1)
        if (total < 0) return null
        return ControllerMemory(total, prefs.getLong("free_$robotId", 0), prefs.getLong("at_$robotId", 0))
    }

    private fun save(robotId: Int, memory: ControllerMemory) {
        prefs.edit()
            .putLong("total_$robotId", memory.totalKb)
            .putLong("free_$robotId", memory.freeKb)
            .putLong("at_$robotId", memory.readAt)
            .apply()
        readings.getOrPut(robotId) { MutableStateFlow(null) }.value = memory
    }
}
