package my.robots.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import my.robots.core.common.FileUtil
import my.robots.core.common.ascode.AsMasterTransfer
import my.robots.core.common.ascode.AsProgramBlocks
import my.robots.core.model.Robot
import my.robots.core.network.KawasakiTerminalManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Situação de um robô numa ação em grupo. */
enum class TaskState { WAITING, CONNECTING, RUNNING, DONE, WARNING, FAILED }

/** Andamento de um robô numa ação em grupo, com uma mensagem curta para a tela. */
data class RobotTask(val state: TaskState, val message: String = "") {
    val finished: Boolean get() = state == TaskState.DONE || state == TaskState.WARNING || state == TaskState.FAILED
}

/**
 * Ações em grupo da tela de Projeto. Todas rodam em paralelo, um robô por conexão, e sempre
 * conectam (e esperam o login e as checagens) antes de mandar qualquer comando. O andamento
 * de cada robô vai para [onUpdate]; um robô que falha não para os outros.
 */
class ProjectOperations(
    private val repository: RobotRepository,
    private val terminal: KawasakiTerminalManager,
    private val checks: ControllerChecks
) {
    companion object {
        private const val SAVE_TIMEOUT_MS = 15 * 60_000L
        private const val COMMAND_TIMEOUT_MS = 30_000L
        private const val LOAD_TIMEOUT_MS = 5 * 60_000L
    }

    /**
     * Backup de todos: em cada robô, "SAVE/FULL <robô>_<aaaammdd_hhmm>"; espera o arquivo
     * chegar inteiro e o prompt voltar, e registra o arquivo como backup (syncRobotFolder).
     */
    suspend fun backupAll(robots: List<Robot>, onUpdate: (Int, RobotTask) -> Unit) = coroutineScope {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        robots.map { robot ->
            async {
                val fileName = "${FileUtil.sanitizeFileName(robot.name).replace(".as", "")}_$stamp"
                if (!connect(robot, onUpdate)) return@async
                onUpdate(robot.id, RobotTask(TaskState.RUNNING, "SAVE/FULL…"))
                val result = checks.commands.saveFile(robot.id, "SAVE/FULL $fileName", fileName, SAVE_TIMEOUT_MS)
                if (!result.ok) {
                    onUpdate(robot.id, RobotTask(TaskState.FAILED, result.message))
                    return@async
                }
                val imported = repository.syncRobotFolder(robot)
                onUpdate(
                    robot.id,
                    if (imported > 0) RobotTask(TaskState.DONE, result.message)
                    else RobotTask(TaskState.WARNING, "${result.fileName} recebido, mas não entrou como backup")
                )
            }
        }.awaitAll()
    }

    /** Comando para todos: o mesmo comando em cada robô, esperando o prompt voltar. */
    suspend fun commandAll(robots: List<Robot>, command: String, onUpdate: (Int, RobotTask) -> Unit) = coroutineScope {
        robots.map { robot ->
            async {
                if (!connect(robot, onUpdate)) return@async
                onUpdate(robot.id, RobotTask(TaskState.RUNNING, command))
                val ok = checks.sendAndAwaitPrompt(robot.id, command, COMMAND_TIMEOUT_MS)
                onUpdate(
                    robot.id,
                    if (ok) RobotTask(TaskState.DONE, "Respondeu") else RobotTask(TaskState.WARNING, "Sem prompt de volta: veja o terminal")
                )
            }
        }.awaitAll()
    }

    /** Um par mestre -> escravo da transferência. */
    data class MasterSlavePair(val master: Robot, val slave: Robot)

    /**
     * Transferência mestre -> escravo. Para cada par: pega o último backup do mestre, tira os
     * programas pedidos (pelo nome exato), soma [offset] nas linhas BASE e, com [withFrames],
     * junta os frames da .TRANS usados nessas bases. Depois conecta no escravo, grava o
     * arquivo na pasta dele e manda LOAD. Avisa (WARNING) programa ou frame que não existe no
     * mestre e offset que não aparece no último backup do escravo.
     */
    suspend fun transferToSlaves(
        pairs: List<MasterSlavePair>,
        programs: List<String>,
        withFrames: Boolean,
        offset: String,
        onUpdate: (Int, RobotTask) -> Unit
    ) = coroutineScope {
        pairs.map { (master, slave) ->
            async {
                onUpdate(slave.id, RobotTask(TaskState.RUNNING, "Lendo o backup do ${master.name}…"))
                val prepared = withContext(Dispatchers.Default) { prepareTransfer(master, slave, programs, withFrames, offset) }
                if (prepared.file == null) {
                    onUpdate(slave.id, RobotTask(TaskState.FAILED, prepared.warnings.joinToString(" · ")))
                    return@async
                }
                if (!connect(slave, onUpdate)) return@async
                onUpdate(slave.id, RobotTask(TaskState.RUNNING, "Enviando ${prepared.summary}…"))
                val fileName = if (programs.size == 1) "transfer_${FileUtil.sanitizeFileName(programs[0]).replace(".as", "")}.as"
                else "transfer_batch_${System.currentTimeMillis()}.as"
                // cópia na pasta do escravo, para registro (o LOAD sai da memória)
                repository.saveFileToRobotFolder(slave.id, fileName, prepared.file)
                val result = checks.commands.loadFile(slave.id, fileName, prepared.file, LOAD_TIMEOUT_MS)
                val base = "${master.name} → ${slave.name}: ${prepared.summary}"
                onUpdate(
                    slave.id,
                    when {
                        !result.ok -> RobotTask(TaskState.FAILED, result.message)
                        prepared.warnings.isNotEmpty() -> RobotTask(TaskState.WARNING, "$base · ${prepared.warnings.joinToString(" · ")}")
                        else -> RobotTask(TaskState.DONE, base)
                    }
                )
            }
        }.awaitAll()
    }

    private class Prepared(val file: String?, val summary: String, val warnings: List<String>)

    private suspend fun prepareTransfer(
        master: Robot,
        slave: Robot,
        programs: List<String>,
        withFrames: Boolean,
        offset: String
    ): Prepared {
        val masterBackup = latestFullBackup(master.id)
            ?: return Prepared(null, "", listOf("${master.name} não tem backup com programas"))
        val content = masterBackup.content
        val warnings = mutableListOf<String>()
        val blocks = programs.mapNotNull { name ->
            AsProgramBlocks.extract(content, name) ?: run { warnings += "$name não existe no ${master.name}"; null }
        }
        if (blocks.isEmpty()) return Prepared(null, "", warnings)
        val (code, changedBases) = AsMasterTransfer.applyBaseOffset(blocks.joinToString("\n"), offset)
        var frameLines = emptyList<String>()
        if (withFrames) {
            val (lines, missing) = AsMasterTransfer.transLines(content, AsMasterTransfer.framesUsed(code))
            frameLines = lines
            if (missing.isNotEmpty()) warnings += "frame ${missing.joinToString()} não está no ${master.name}"
        }
        // o offset precisa existir no escravo; só dá para conferir se ele já tem backup
        latestFullBackup(slave.id)?.let { if (!AsMasterTransfer.definesPose(it.content, offset)) warnings += "$offset não aparece no último backup do ${slave.name}" }
        val summary = buildString {
            append(if (blocks.size == 1) "1 programa" else "${blocks.size} programas")
            if (changedBases > 0) append(", $changedBases base(s) +$offset")
            if (frameLines.isNotEmpty()) append(", ${frameLines.size} frame(s)")
        }
        return Prepared(AsMasterTransfer.buildFile(code, frameLines), summary, warnings)
    }

    /** O backup mais recente do robô que tem programas (arquivos de envio não contam). */
    private suspend fun latestFullBackup(robotId: Int) = repository.getBackupsSummary(robotId).first()
        .filter { !FileUtil.isTransferFile(it.fileName) && it.programsCount > 0 }
        .maxByOrNull { it.timestamp }
        ?.let { repository.getBackupById(it.id) }

    private suspend fun connect(robot: Robot, onUpdate: (Int, RobotTask) -> Unit): Boolean {
        onUpdate(robot.id, RobotTask(TaskState.CONNECTING, "Conectando…"))
        val ok = checks.connectAndWait(robot)
        if (!ok) onUpdate(robot.id, RobotTask(TaskState.FAILED, "Não conectou"))
        return ok
    }
}
