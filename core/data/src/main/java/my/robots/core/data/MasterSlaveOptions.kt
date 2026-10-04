package my.robots.core.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Opções da transferência mestre -> escravo de um projeto escravo (as que valem de início na
 * janela de transferência):
 * - applyOffset: somar a variável de offset na base do programa de destino;
 * - sendFrame: enviar junto as linhas da .TRANS com o frame da base;
 * - framePattern: o nome do frame da base, com "pgnum" no lugar do número do programa
 *   ("fr_[pgnum]": o pg100 usa fr_[100]). Só as bases que seguem o padrão recebem o offset e só
 *   esses frames vão junto; vazio = todas as bases de frame.
 * O projeto mestre, a variável de offset e os pares de robôs ficam no banco (ProjectLayout e
 * Robot.masterRobotId).
 */
data class MasterSlaveConfig(
    val applyOffset: Boolean = true,
    val sendFrame: Boolean = true,
    val framePattern: String = DEFAULT_FRAME_PATTERN
) {
    companion object {
        const val DEFAULT_FRAME_PATTERN = "fr_[pgnum]"
    }
}

/**
 * Guarda as [MasterSlaveConfig] de cada projeto escravo no aparelho (SharedPreferences
 * "master_slave"). Projeto sem nada gravado usa o padrão.
 */
class MasterSlaveOptions(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("master_slave", Context.MODE_PRIVATE)
    private val _all = MutableStateFlow(load())

    /** Opções gravadas, por projeto escravo. */
    val all: StateFlow<Map<String, MasterSlaveConfig>> = _all.asStateFlow()

    fun config(slaveProject: String): MasterSlaveConfig = _all.value[slaveProject] ?: MasterSlaveConfig()

    fun save(slaveProject: String, config: MasterSlaveConfig) {
        val map = _all.value + (slaveProject to config.copy(framePattern = config.framePattern.trim()))
        persist(map)
    }

    fun remove(slaveProject: String) = persist(_all.value - slaveProject)

    private fun persist(map: Map<String, MasterSlaveConfig>) {
        // uma linha por projeto: projeto<TAB>offset<TAB>frame<TAB>padrão
        val text = map.entries.joinToString("\n") { (p, c) ->
            listOf(p.replace('\t', ' '), c.applyOffset.toString(), c.sendFrame.toString(), c.framePattern.replace('\t', ' '))
                .joinToString("\t")
        }
        prefs.edit().putString("configs", text).apply()
        _all.value = map
    }

    private fun load(): Map<String, MasterSlaveConfig> =
        prefs.getString("configs", null).orEmpty().split("\n").filter { it.isNotBlank() }.associate { line ->
            val p = line.split("\t")
            p[0] to MasterSlaveConfig(
                applyOffset = p.getOrNull(1)?.toBooleanStrictOrNull() ?: true,
                sendFrame = p.getOrNull(2)?.toBooleanStrictOrNull() ?: true,
                framePattern = p.getOrNull(3) ?: MasterSlaveConfig.DEFAULT_FRAME_PATTERN
            )
        }
}
