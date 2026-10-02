package my.robots.core.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import my.robots.core.common.ascode.AsControllerReplies
import my.robots.core.common.ascode.AsFreeMemory
import my.robots.core.common.ascode.ControllerMemory
import my.robots.core.model.Robot
import my.robots.core.network.KawasakiTerminalManager
import java.time.Duration
import java.time.LocalDateTime

/**
 * Relógio do controlador diferente do celular, esperando a resposta do usuário.
 * - offsetSeconds: robô menos celular (positivo = robô adiantado).
 * - fixFailed: true quando o app já tentou corrigir e o controlador não aceitou.
 */
data class ClockIssue(
    val robotId: Int,
    val robotName: String,
    val robotTime: LocalDateTime,
    val phoneTime: LocalDateTime,
    val offsetSeconds: Long,
    val fixFailed: Boolean = false
)

/**
 * O controlador conectado tem outra série que a cadastrada: pode ser o robô errado (IP trocado)
 * ou uma troca de controlador.
 */
data class SerialMismatch(
    val robotId: Int,
    val robotName: String,
    val ip: String,
    val registered: String,
    val found: String
)

/**
 * Checagens feitas logo depois do login em qualquer robô (painel, tela de Projeto, Terminal
 * Geral), uma vez por conexão:
 *
 * 1. **ID**: lê o número de série. Robô sem série cadastrada passa a ter essa; série diferente
 *    da cadastrada vira um [SerialMismatch] (a tela pergunta o que fazer).
 * 2. **Relógio**: lê data e hora (`PRINT $DATE(3)," ",$TIME`). Diferença maior que
 *    [CLOCK_TOLERANCE_SECONDS] vira um [ClockIssue], e a tela pergunta se deve corrigir.
 * 3. **FREE**: memória de programas, guardada no aparelho (SharedPreferences
 *    "controller_memory") para aparecer mesmo sem conexão.
 *
 * Os comandos passam pelo terminal do robô e aparecem no histórico. Enquanto as checagens
 * rodam, o robô fica em [busy]; quem vai mandar comandos (envio de programa) espera com
 * [awaitReady] para não misturar as respostas.
 */
class ControllerChecks(
    context: Context,
    private val terminal: KawasakiTerminalManager,
    private val repository: RobotRepository,
    private val scope: CoroutineScope
) {
    companion object {
        const val CLOCK_TOLERANCE_SECONDS = 120L
        private const val LOGIN_TIMEOUT_MS = 60_000L
    }

    private val prefs = context.applicationContext.getSharedPreferences("controller_memory", Context.MODE_PRIVATE)
    private val memories = mutableMapOf<Int, MutableStateFlow<ControllerMemory?>>()
    private val locks = mutableMapOf<Int, Mutex>()
    private val watched = mutableSetOf<Int>()

    private val _busy = MutableStateFlow<Set<Int>>(emptySet())
    /** Robôs com checagem em andamento. */
    val busy: StateFlow<Set<Int>> = _busy.asStateFlow()

    private val _clockIssues = MutableStateFlow<List<ClockIssue>>(emptyList())
    /** Relógios a corrigir, esperando a resposta do usuário. */
    val clockIssues: StateFlow<List<ClockIssue>> = _clockIssues.asStateFlow()

    private val _serialMismatches = MutableStateFlow<List<SerialMismatch>>(emptyList())
    /** Séries diferentes da cadastrada, esperando a resposta do usuário. */
    val serialMismatches: StateFlow<List<SerialMismatch>> = _serialMismatches.asStateFlow()

    /** Começa a vigiar as conexões de todos os robôs (chamar uma vez, no MyRobotsApp). */
    fun start() {
        scope.launch {
            repository.allRobots.collect { robots -> robots.forEach { watch(it.id) } }
        }
    }

    private fun watch(robotId: Int) {
        if (!watched.add(robotId)) return
        scope.launch {
            var wasConnected = false
            terminal.getConnectionStatus(robotId).collect { connected ->
                if (connected && !wasConnected) scope.launch { runChecks(robotId) }
                wasConnected = connected
            }
        }
    }

    private fun lock(robotId: Int) = synchronized(locks) { locks.getOrPut(robotId) { Mutex() } }

    /** Última leitura de memória do robô (null se nunca foi lida). */
    fun lastMemory(robotId: Int): StateFlow<ControllerMemory?> =
        synchronized(memories) { memories.getOrPut(robotId) { MutableStateFlow(loadMemory(robotId)) } }.asStateFlow()

    /**
     * Espera o login e roda as três checagens. Se o login não terminar em
     * [LOGIN_TIMEOUT_MS] (robô sem login automático, por exemplo), desiste em silêncio.
     */
    suspend fun runChecks(robotId: Int) {
        if (!awaitPrompt(robotId, LOGIN_TIMEOUT_MS)) return
        lock(robotId).withLock {
            _busy.update { it + robotId }
            try {
                checkSerial(robotId)
                checkClock(robotId)
                readMemoryLocked(robotId)
            } finally {
                _busy.update { it - robotId }
            }
        }
    }

    /** Lê a memória agora (botão "Ler agora"). Só com o robô conectado e logado. */
    suspend fun readMemory(robotId: Int): ControllerMemory? {
        if (!awaitPrompt(robotId, 5_000)) return null
        return lock(robotId).withLock { readMemoryLocked(robotId) }
    }

    /**
     * Conecta o robô (se ainda não estiver) e espera o login e as checagens terminarem.
     * Devolve false se não conectar ou não logar em [timeoutMs].
     */
    suspend fun connectAndWait(robot: Robot, timeoutMs: Long = 20_000): Boolean {
        if (!terminal.getConnectionStatus(robot.id).value) terminal.connect(robot)
        if (!awaitPrompt(robot.id, timeoutMs)) return false
        return awaitReady(robot.id, timeoutMs)
    }

    /** Espera as checagens do robô terminarem (até [timeoutMs]). */
    suspend fun awaitReady(robotId: Int, timeoutMs: Long = 20_000): Boolean {
        var waited = 0L
        // dá tempo para a checagem da conexão nova começar
        delay(300)
        while (robotId in _busy.value || lock(robotId).isLocked) {
            if (waited >= timeoutMs) return false
            delay(200)
            waited += 200
        }
        return terminal.getConnectionStatus(robotId).value
    }

    // ---------- Respostas do usuário ----------

    /** Acerta o relógio do robô com a hora do celular e confere se o controlador aceitou. */
    fun fixClock(issue: ClockIssue) {
        _clockIssues.update { list -> list.filterNot { it.robotId == issue.robotId } }
        scope.launch {
            lock(issue.robotId).withLock {
                if (!terminal.getConnectionStatus(issue.robotId).value) return@withLock
                val history = terminal.getHistory(issue.robotId)
                terminal.sendCommand(issue.robotId, AsControllerReplies.setClockCommand(LocalDateTime.now()))
                delay(1500)
                // o controlador pode perguntar "Change?": Enter vazio sai da pergunta
                if (history.value.takeLast(3).any { it.contains("Change?", ignoreCase = true) }) {
                    terminal.sendCommand(issue.robotId, "", isManualFinalize = true)
                    delay(500)
                }
                checkClock(issue.robotId, afterFix = true)
            }
        }
    }

    fun dismissClock(issue: ClockIssue) {
        _clockIssues.update { list -> list.filterNot { it.robotId == issue.robotId } }
    }

    /** Aceita a série encontrada como a nova série do robô (troca de controlador). */
    fun acceptSerial(mismatch: SerialMismatch) {
        _serialMismatches.update { list -> list.filterNot { it.robotId == mismatch.robotId } }
        scope.launch { repository.setRobotSerialNumber(mismatch.robotId, mismatch.found) }
    }

    fun dismissSerial(mismatch: SerialMismatch) {
        _serialMismatches.update { list -> list.filterNot { it.robotId == mismatch.robotId } }
    }

    // ---------- Checagens ----------

    private suspend fun checkSerial(robotId: Int) {
        val match = sendAndAwait(robotId, "ID", AsControllerReplies.SERIAL) ?: return
        val serial = match.groupValues[1]
        val robot = repository.getRobotById(robotId) ?: return
        when (robot.serialNumber) {
            null -> repository.setRobotSerialNumber(robotId, serial)
            serial -> Unit
            else -> _serialMismatches.update { list ->
                list.filterNot { it.robotId == robotId } +
                    SerialMismatch(robotId, robot.name, robot.ip, robot.serialNumber!!, serial)
            }
        }
    }

    private suspend fun checkClock(robotId: Int, afterFix: Boolean = false) {
        val match = sendAndAwait(robotId, AsControllerReplies.READ_CLOCK_COMMAND, AsControllerReplies.CLOCK) ?: return
        val robotTime = AsControllerReplies.parseClock(match.value) ?: return
        val phoneTime = LocalDateTime.now().withNano(0)
        val offset = Duration.between(phoneTime, robotTime).seconds
        if (kotlin.math.abs(offset) <= CLOCK_TOLERANCE_SECONDS) return
        val name = repository.getRobotById(robotId)?.name ?: "Robô"
        _clockIssues.update { list ->
            list.filterNot { it.robotId == robotId } + ClockIssue(robotId, name, robotTime, phoneTime, offset, fixFailed = afterFix)
        }
    }

    private suspend fun readMemoryLocked(robotId: Int): ControllerMemory? {
        sendAndAwait(robotId, "FREE", Regex("""Available memory""", RegexOption.IGNORE_CASE)) ?: return null
        val memory = AsFreeMemory.parseLast(terminal.getHistory(robotId).value.joinToString("\n")) ?: return null
        saveMemory(robotId, memory)
        return memory
    }

    /**
     * Manda o comando e espera aparecer no terminal uma ocorrência NOVA de [answer] (conta as
     * ocorrências antes de mandar). Depois espera o prompt, para o próximo comando não
     * atropelar a resposta. Devolve a ocorrência, ou null se não vier em [timeoutMs].
     */
    private suspend fun sendAndAwait(robotId: Int, command: String, answer: Regex, timeoutMs: Long = 5_000): MatchResult? {
        if (!terminal.getConnectionStatus(robotId).value) return null
        val history = terminal.getHistory(robotId)
        val before = answer.findAll(history.value.joinToString("\n")).count()
        terminal.sendCommand(robotId, command)
        var waited = 0L
        while (waited < timeoutMs) {
            delay(200)
            waited += 200
            val matches = answer.findAll(history.value.joinToString("\n")).toList()
            if (matches.size > before) {
                awaitPrompt(robotId, 2_000)
                return matches.last()
            }
        }
        return null
    }

    /** Espera a última linha do terminal ser o prompt ">" (login feito, comando terminado). */
    private suspend fun awaitPrompt(robotId: Int, timeoutMs: Long): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            if (!terminal.getConnectionStatus(robotId).value) return false
            val last = terminal.getHistory(robotId).value.lastOrNull { it.isNotBlank() }?.trim()
            if (last == ">") return true
            delay(250)
            waited += 250
        }
        return false
    }

    private fun loadMemory(robotId: Int): ControllerMemory? {
        val total = prefs.getLong("total_$robotId", -1)
        if (total < 0) return null
        return ControllerMemory(total, prefs.getLong("free_$robotId", 0), prefs.getLong("at_$robotId", 0))
    }

    private fun saveMemory(robotId: Int, memory: ControllerMemory) {
        prefs.edit()
            .putLong("total_$robotId", memory.totalKb)
            .putLong("free_$robotId", memory.freeKb)
            .putLong("at_$robotId", memory.readAt)
            .apply()
        synchronized(memories) { memories.getOrPut(robotId) { MutableStateFlow(null) } }.value = memory
    }
}
