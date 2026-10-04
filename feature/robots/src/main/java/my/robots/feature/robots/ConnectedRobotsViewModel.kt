package my.robots.feature.robots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import my.robots.core.data.RobotRepository
import my.robots.core.model.HeartbeatState
import my.robots.core.model.Robot
import my.robots.core.network.KawasakiTerminalManager

/**
 * Conexão dos robôs na lista inicial.
 *
 * Acompanha, para cada robô, se está conectado e o heartbeat (ALIVE/STALE/DISCONNECTED), e
 * permite conectar/desconectar um robô só ou o projeto inteiro de uma vez.
 */
class ConnectedRobotsViewModel(
    private val repository: RobotRepository,
    private val terminalManager: KawasakiTerminalManager
) : ViewModel() {

    private val _connectedIds = MutableStateFlow<Set<Int>>(emptySet())
    /**
     * Ids dos robôs conectados agora (de qualquer projeto).
     */
    val connectedIds: StateFlow<Set<Int>> = _connectedIds.asStateFlow()

    private val _heartbeats = MutableStateFlow<Map<Int, HeartbeatState>>(emptyMap())
    /**
     * Heartbeat atual de cada robô observado.
     */
    val heartbeats: StateFlow<Map<Int, HeartbeatState>> = _heartbeats.asStateFlow()

    // Ids que já têm um observador de conexão/heartbeat rodando (evita duplicar o mesmo coletor).
    private val watchedIds = mutableSetOf<Int>()

    init {
        viewModelScope.launch {
            repository.allRobots.collect { robots ->
                robots.forEach { robot -> watchRobot(robot.id) }
            }
        }
    }

    /**
     * Começa a acompanhar a conexão e o heartbeat de um robô, se ainda não estiver acompanhando.
     */
    private fun watchRobot(robotId: Int) {
        if (!watchedIds.add(robotId)) return

        viewModelScope.launch {
            terminalManager.getConnectionStatus(robotId).collect { isConnected ->
                _connectedIds.update { if (isConnected) it + robotId else it - robotId }
            }
        }
        viewModelScope.launch {
            terminalManager.getHeartbeat(robotId).collect { heartbeat ->
                _heartbeats.update { it + (robotId to heartbeat) }
            }
        }
    }

    private val _attempts = MutableStateFlow<Map<Int, String>>(emptyMap())
    /**
     * Tentativas de conexão em andamento ou que falharam, por robô: "Conectando…" ou o motivo
     * da falha (some sozinho depois de alguns segundos). Sem isso, um robô fora de alcance
     * não dava nenhum sinal ao tocar em Conectar.
     */
    val attempts: StateFlow<Map<Int, String>> = _attempts.asStateFlow()

    /**
     * Conecta um robô e acompanha a tentativa: se o socket não abrir em alguns segundos, mostra
     * o motivo (a última linha "Erro: ..." do terminal dele).
     */
    fun connect(robot: Robot) {
        if (robot.id in _connectedIds.value) return
        _attempts.update { it + (robot.id to "Conectando…") }
        terminalManager.connect(robot)
        viewModelScope.launch {
            var waited = 0L
            while (waited < CONNECT_WAIT_MS && !terminalManager.getConnectionStatus(robot.id).value) {
                delay(250)
                waited += 250
            }
            if (terminalManager.getConnectionStatus(robot.id).value) {
                _attempts.update { it - robot.id }
            } else {
                val error = terminalManager.getHistory(robot.id).value.lastOrNull { it.startsWith("Erro:") }
                    ?.removePrefix("Erro:")?.trim()
                _attempts.update { it + (robot.id to "Não conectou" + (error?.let { e -> ": ${shortError(e)}" } ?: " (sem resposta)")) }
                delay(FAILURE_SHOWN_MS)
                _attempts.update { m -> if (m[robot.id]?.startsWith("Não conectou") == true) m - robot.id else m }
            }
        }
    }

    /** Erro de rede em poucas palavras. */
    private fun shortError(e: String): String = when {
        e.contains("ECONNREFUSED", true) || e.contains("refused", true) -> "recusado pelo robô (porta fechada)"
        e.contains("timed out", true) || e.contains("ETIMEDOUT", true) || e.contains("after", true) ->
            "sem resposta do IP (fora da rede?)"
        e.contains("EHOSTUNREACH", true) || e.contains("unreachable", true) -> "IP fora de alcance"
        e.contains("ENETUNREACH", true) -> "sem rede"
        else -> "erro de rede"
    }

    companion object {
        private const val CONNECT_WAIT_MS = 7_000L
        private const val FAILURE_SHOWN_MS = 8_000L
    }

    /**
     * Desconecta um robô (mantém o histórico do terminal dele).
     */
    fun disconnect(robot: Robot) {
        terminalManager.disconnect(robot.id, clearHistory = false)
    }

    /**
     * Conecta os robôs do projeto (a lista vem da tela) que ainda não estão conectados.
     */
    fun connectProject(robots: List<Robot>) {
        val connected = _connectedIds.value
        robots.filter { it.id !in connected }.forEach { connect(it) }
    }

    /**
     * Desconecta os robôs do projeto que estão conectados.
     */
    fun disconnectProject(robots: List<Robot>) {
        val connected = _connectedIds.value
        robots.filter { it.id in connected }.forEach { terminalManager.disconnect(it.id, clearHistory = false) }
    }
}

/**
 * Ensina o Android a criar o ConnectedRobotsViewModel com o repositório e o terminal.
 */
class ConnectedRobotsViewModelFactory(
    private val repository: RobotRepository,
    private val terminalManager: KawasakiTerminalManager
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ConnectedRobotsViewModel(repository, terminalManager) as T
    }
}
