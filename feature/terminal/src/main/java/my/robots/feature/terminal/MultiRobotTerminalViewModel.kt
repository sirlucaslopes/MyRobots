package my.robots.feature.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import my.robots.core.model.Manufacturer
import my.robots.core.model.QuickCommand
import my.robots.core.model.Robot
import my.robots.core.network.KawasakiTerminalManager
import my.robots.core.data.RobotRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cérebro do Terminal Geral de um projeto.
 *
 * Descobre quais robôs pertencem ao projeto, conecta/desconecta todos e envia
 * o mesmo comando para os que estão conectados.
 */
class MultiRobotTerminalViewModel(
    private val repository: RobotRepository,
    private val terminalManager: KawasakiTerminalManager,
    private val projectName: String
) : ViewModel() {

    private val _robots = MutableStateFlow<List<Robot>>(emptyList())
    /**
     * Robôs que pertencem a este projeto.
     */
    val robots: StateFlow<List<Robot>> = _robots.asStateFlow()

    private val _commandHistory = MutableStateFlow<List<String>>(emptyList())
    /**
     * Histórico mostrado na tela: só o que VOCÊ enviou (linhas que começam com ">").
     */
    val commandHistory: StateFlow<List<String>> = _commandHistory.asStateFlow()

    private val _connectedRobotsIds = MutableStateFlow<Set<Int>>(emptySet())
    /**
     * Ids dos robôs que estão conectados agora.
     */
    val connectedRobotsIds: StateFlow<Set<Int>> = _connectedRobotsIds.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    /**
     * true enquanto está tentando conectar em todos.
     */
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    /**
     * Comandos rápidos da Kawasaki, usados no botão do raio.
     */
    val quickCommands: StateFlow<List<QuickCommand>> = repository.getQuickCommandsByManufacturer(Manufacturer.KAWASAKI)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Ao criar, já carrega os robôs do projeto.
    init {
        loadRobots()
    }

    /**
     * Fica observando a lista de robôs: filtra os do projeto e, para cada um,
     * acompanha se ele conecta ou desconecta (atualizando connectedRobotsIds).
     */
    private fun loadRobots() {
        viewModelScope.launch {
            repository.allRobots.collect { allRobots ->
                val projectRobots = allRobots.filter { it.project == projectName }
                _robots.value = projectRobots
                
                // Monitora conexões para cada robô do projeto
                projectRobots.forEach { robot ->
                    launch {
                        terminalManager.getConnectionStatus(robot.id).collect { isConnected ->
                            if (isConnected) {
                                _connectedRobotsIds.update { it + robot.id }
                            } else {
                                _connectedRobotsIds.update { it - robot.id }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Se algum robô estiver conectado, desconecta todos; senão, conecta todos.
     */
    fun toggleConnection() {
        if (_connectedRobotsIds.value.isNotEmpty()) {
            disconnectAll()
        } else {
            connectAll()
        }
    }

    /**
     * Pede a conexão de todos os robôs do projeto.
     */
    fun connectAll() {
        viewModelScope.launch {
            _isConnecting.value = true
            val currentRobots = _robots.value
            currentRobots.forEach { robot ->
                terminalManager.connect(robot)
            }
            _isConnecting.value = false
        }
    }

    /**
     * Desconecta todos os robôs do projeto (o histórico de cada terminal é mantido).
     */
    fun disconnectAll() {
        _robots.value.forEach { robot ->
            terminalManager.disconnect(robot.id, clearHistory = false)
        }
    }

    /**
     * Envia o comando digitado a todos os robôs CONECTADOS e anota no histórico.
     */
    fun sendCommandToAll(command: String) {
        if (command.isNotBlank()) {
            _commandHistory.update { it + "> $command" }
        }
        
        val connectedIds = _connectedRobotsIds.value
        _robots.value.filter { it.id in connectedIds }.forEach { robot ->
            terminalManager.sendCommand(robot.id, command)
        }
    }

    /**
     * Envia um comando rápido a todos os robôs conectados.
     * Para cada robô, troca [ROBOT] pelo nome dele e [DATA] pela data/hora atual.
     */
    fun sendQuickCommandToAll(cmd: QuickCommand) {
        _commandHistory.update { it + "> ${cmd.label} (${cmd.command})" }
        
        val connectedIds = _connectedRobotsIds.value
        _robots.value.filter { it.id in connectedIds }.forEach { robot ->
            var finalCommand = cmd.command.replace("[ROBOT]", robot.name, ignoreCase = true)
            if (finalCommand.contains("[DATA]", ignoreCase = true)) {
                val timestamp = SimpleDateFormat("_yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                finalCommand = finalCommand.replace("[DATA]", timestamp, ignoreCase = true)
            }
            terminalManager.sendCommand(robot.id, finalCommand)
        }
    }

    /**
     * Limpa o histórico mostrado na tela.
     */
    fun clearHistory() {
        _commandHistory.value = emptyList()
    }
}

/**
 * Ensina o Android a criar o MultiRobotTerminalViewModel com o repositório, o terminal e o nome do projeto.
 */
class MultiRobotTerminalViewModelFactory(
    private val repository: RobotRepository,
    private val terminalManager: KawasakiTerminalManager,
    private val projectName: String
) : ViewModelProvider.Factory {
    /**
     * Cria o ViewModel pedido.
     */
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MultiRobotTerminalViewModel(repository, terminalManager, projectName) as T
    }
}
