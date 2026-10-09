package my.robots.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import my.robots.core.common.BackupZip
import my.robots.core.common.FileUtil
import my.robots.core.common.ascode.AsMasterTransfer
import my.robots.core.common.ascode.AsProgramBlocks
import my.robots.core.model.BackupSummary
import my.robots.core.model.Robot
import my.robots.core.network.KawasakiTerminalManager
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
        robots.map { robot ->
            async {
                val fileName = repository.newSaveName(robot)
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
     * programas pedidos (pelo nome exato), soma [offset] nas linhas BASE (só com [applyOffset];
     * sem ele, os programas vão como estão no mestre) e, com [withFrames],
     * junta os frames da .TRANS usados nessas bases. Depois conecta no escravo, grava o
     * arquivo na pasta dele e manda LOAD. Avisa (WARNING) programa ou frame que não existe no
     * mestre e offset que não aparece no último backup do escravo.
     */
    suspend fun transferToSlaves(
        pairs: List<MasterSlavePair>,
        programs: List<String>,
        withFrames: Boolean,
        offset: String,
        applyOffset: Boolean,
        framePattern: String?,
        onUpdate: (Int, RobotTask) -> Unit
    ) = coroutineScope {
        pairs.map { (master, slave) ->
            async {
                onUpdate(slave.id, RobotTask(TaskState.RUNNING, "Lendo o backup do ${master.name}…"))
                val prepared = withContext(Dispatchers.Default) { prepareTransfer(master, slave, programs, withFrames, offset, applyOffset, framePattern?.takeIf { it.isNotBlank() }) }
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

    /**
     * Duplicar programa em grupo: em cada robô, tira [source] do último backup dele (nome
     * exato), troca o nome para [newName] e, com [comment], o comentário do cabeçalho. Com
     * [frameFrom]/[frameTo], troca o frame no programa e manda junto a linha da .TRANS com o nome
     * novo (cópia do frame de origem do robô). Conecta e faz o LOAD conferido. Um nome novo (ou
     * frame) que já existe no robô é substituído (o LOAD substitui).
     */
    suspend fun duplicateInRobots(
        robots: List<Robot>,
        source: String,
        newName: String,
        comment: String?,
        frameFrom: String?,
        frameTo: String?,
        onUpdate: (Int, RobotTask) -> Unit
    ) = coroutineScope {
        robots.map { robot ->
            async {
                onUpdate(robot.id, RobotTask(TaskState.RUNNING, "Lendo o backup do ${robot.name}…"))
                val backup = latestFullBackup(robot.id)
                if (backup == null) {
                    onUpdate(robot.id, RobotTask(TaskState.FAILED, "${robot.name} não tem backup com programas"))
                    return@async
                }
                var frameNote = ""
                val file = withContext(Dispatchers.Default) {
                    AsProgramBlocks.extract(backup.content, source)?.let { block ->
                        var code = AsProgramBlocks.renameHeader(block, newName)
                        if (comment != null) code = AsProgramBlocks.setHeaderComment(code, comment)
                        var frameLines = emptyList<String>()
                        if (frameFrom != null && frameTo != null) {
                            code = AsMasterTransfer.renameFrame(code, frameFrom, frameTo).first
                            val line = AsMasterTransfer.transLines(backup.content, listOf(frameFrom)).first.firstOrNull()
                            if (line != null) {
                                frameLines = listOf(AsMasterTransfer.renameTransLine(line, frameTo))
                                frameNote = ", $frameFrom → $frameTo"
                            } else {
                                frameNote = " ($frameFrom não existe no ${robot.name}: frame não copiado)"
                            }
                        }
                        AsMasterTransfer.buildFile(code, frameLines)
                    }
                }
                if (file == null) {
                    onUpdate(robot.id, RobotTask(TaskState.FAILED, "$source não existe no ${robot.name}"))
                    return@async
                }
                if (!connect(robot, onUpdate)) return@async
                onUpdate(robot.id, RobotTask(TaskState.RUNNING, "Carregando $newName…"))
                val fileName = "dup_${FileUtil.sanitizeFileName(newName).replace(".as", "")}.as"
                repository.saveFileToRobotFolder(robot.id, fileName, file)
                val result = checks.commands.loadFile(robot.id, fileName, file)
                onUpdate(
                    robot.id,
                    when {
                        !result.ok -> RobotTask(TaskState.FAILED, result.message)
                        frameNote.contains("não copiado") -> RobotTask(TaskState.WARNING, "$source → $newName$frameNote")
                        else -> RobotTask(TaskState.DONE, "$source → $newName$frameNote")
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
        offset: String,
        applyOffset: Boolean,
        framePattern: String?
    ): Prepared {
        val masterBackup = latestFullBackup(master.id)
            ?: return Prepared(null, "", listOf("${master.name} não tem backup com programas"))
        val content = masterBackup.content
        val warnings = mutableListOf<String>()
        val blocks = programs.mapNotNull { name ->
            AsProgramBlocks.extract(content, name) ?: run { warnings += "$name não existe no ${master.name}"; null }
        }
        if (blocks.isEmpty()) return Prepared(null, "", warnings)
        val joined = blocks.joinToString("\n")
        val (code, changedBases) = if (applyOffset) AsMasterTransfer.applyBaseOffset(joined, offset, framePattern) else joined to 0
        var frameLines = emptyList<String>()
        if (withFrames) {
            val (lines, missing) = AsMasterTransfer.transLines(content, AsMasterTransfer.framesUsed(code, framePattern))
            frameLines = lines
            if (missing.isNotEmpty()) warnings += "frame ${missing.joinToString()} não está no ${master.name}"
        }
        // o offset precisa existir no escravo; só dá para conferir se ele já tem backup
        if (applyOffset) latestFullBackup(slave.id)?.let { if (!AsMasterTransfer.definesPose(it.content, offset)) warnings += "$offset não aparece no último backup do ${slave.name}" }
        val summary = buildString {
            append(if (blocks.size == 1) "1 programa" else "${blocks.size} programas")
            if (changedBases > 0) append(", $changedBases base(s) +$offset")
            if (!applyOffset) append(", sem offset")
            if (frameLines.isNotEmpty()) append(", ${frameLines.size} frame(s)")
        }
        return Prepared(AsMasterTransfer.buildFile(code, frameLines), summary, warnings)
    }

    /**
     * "Enviar backups": o último backup de cada robô (arquivos de envio, como dup_*.as, não
     * contam). Null = o robô ainda não tem backup.
     */
    suspend fun latestBackups(robots: List<Robot>): Map<Int, BackupSummary?> = robots.associate { r ->
        r.id to repository.getBackupsSummary(r.id).first()
            .filter { !FileUtil.isTransferFile(it.fileName) }
            .maxByOrNull { it.timestamp }
    }

    /**
     * Escreve em [out] um .zip com o backup escolhido de cada robô, um arquivo separado por robô
     * (nomes em [BackupZip.entryNames]). Lê um backup por vez, para não ter todos na memória
     * juntos (um SAVE/FULL tem uns 4,5 MB). Devolve quantos arquivos entraram.
     */
    suspend fun writeBackupsZip(choices: List<Pair<Robot, BackupSummary>>, out: OutputStream): Int = withContext(Dispatchers.IO) {
        val names = BackupZip.entryNames(choices.map { (r, b) -> r.name to b.fileName })
        val zip = ZipOutputStream(out)
        var count = 0
        choices.zip(names).forEach { (choice, name) ->
            val backup = repository.getBackupById(choice.second.id) ?: return@forEach
            zip.putNextEntry(ZipEntry(name))
            zip.write(backup.content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            count++
        }
        zip.finish()
        count
    }

    /** O backup mais recente do robô que tem programas (arquivos de envio não contam). */
    private suspend fun latestFullBackup(robotId: Int) = repository.getBackupsSummary(robotId).first()
        .filter { !FileUtil.isTransferFile(it.fileName) && it.programsCount > 0 }
        .maxByOrNull { it.timestamp }
        ?.let { repository.getBackupById(it.id) }

    private suspend fun connect(robot: Robot, onUpdate: (Int, RobotTask) -> Unit): Boolean {
        onUpdate(robot.id, RobotTask(TaskState.CONNECTING, "Conectando…"))
        val ok = checks.connectAndWait(robot)
        if (!ok) onUpdate(robot.id, RobotTask(TaskState.FAILED, "Não conectou: " + connectFailure(robot)))
        return ok
    }

    /** Motivo da falha de conexão, pelo que ficou no terminal do robô. */
    private fun connectFailure(robot: Robot): String {
        val history = terminal.getHistory(robot.id).value
        val error = history.lastOrNull { it.startsWith("Erro:") }?.removePrefix("Erro:")?.trim()
        return when {
            error == null && !terminal.getConnectionStatus(robot.id).value ->
                "a conexão abriu e fechou na hora (no K-ROSET: o controlador da porta ${robot.port} está desligado?)"
            error == null -> "o robô não respondeu ao login"
            error.contains("refused", true) || error.contains("ECONNREFUSED", true) -> "recusado em ${robot.ip}:${robot.port}"
            error.contains("timed out", true) || error.contains("after", true) -> "sem resposta de ${robot.ip} (fora da rede?)"
            error.contains("unreachable", true) -> "${robot.ip} fora de alcance"
            else -> error.take(60)
        }
    }
}
