package my.robots.feature.robots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import my.robots.core.data.RobotRepository
import my.robots.core.model.Robot
import my.robots.core.network.HeartbeatState
import my.robots.core.network.KawasakiTerminalManager

/**
 * Cérebro do popup "Robôs Conectados".
 *
 * Mostra todos os robôs agrupados por projeto (como na lista inicial) e acompanha,
 * para cada um, se está conectado e o heartbeat (ALIVE/STALE/DISCONNECTED). Permite
 * conectar/desconectar um robô só ou o projeto inteiro de uma vez.
 */
class ConnectedRobotsViewModel(
    private val repository: RobotRepository,
    private val terminalManager: KawasakiTerminalManager
) : ViewModel() {

    /**
     * Robôs agrupados por projeto, na ordem em que aparecem no banco.
     */
    val robotsByProject: StateFlow<Map<String, List<Robot>>> = repository.allRobots
        .map { robots -> robots.groupBy { it.project } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

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

    /**
     * Conecta um robô.
     */
    fun connect(robot: Robot) {
        terminalManager.connect(robot)
    }

    /**
     * Desconecta um robô (mantém o histórico do terminal dele).
     */
    fun disconnect(robot: Robot) {
        terminalManager.disconnect(robot.id, clearHistory = false)
    }

    /**
     * Conecta todos os robôs do projeto que ainda não estão conectados.
     */
    fun connectProject(project: String) {
        val robots = robotsByProject.value[project] ?: return
        val connected = _connectedIds.value
        robots.filter { it.id !in connected }.forEach { terminalManager.connect(it) }
    }

    /**
     * Desconecta todos os robôs do projeto que estão conectados.
     */
    fun disconnectProject(project: String) {
        val robots = robotsByProject.value[project] ?: return
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
