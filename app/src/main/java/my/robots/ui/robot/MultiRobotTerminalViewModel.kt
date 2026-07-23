package my.robots.ui.robot

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import my.robots.data.model.Manufacturer
import my.robots.data.model.QuickCommand
import my.robots.data.model.Robot
import my.robots.data.remote.KawasakiTerminalManager
import my.robots.data.repository.RobotRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MultiRobotTerminalViewModel(
    private val repository: RobotRepository,
    private val terminalManager: KawasakiTerminalManager,
    private val projectName: String
) : ViewModel() {

    private val _robots = MutableStateFlow<List<Robot>>(emptyList())
    val robots: StateFlow<List<Robot>> = _robots.asStateFlow()

    private val _commandHistory = MutableStateFlow<List<String>>(emptyList())
    val commandHistory: StateFlow<List<String>> = _commandHistory.asStateFlow()

    private val _connectedRobotsIds = MutableStateFlow<Set<Int>>(emptySet())
    val connectedRobotsIds: StateFlow<Set<Int>> = _connectedRobotsIds.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    // Carrega comandos rápidos da Kawasaki (Geral)
    val quickCommands: StateFlow<List<QuickCommand>> = repository.getQuickCommandsByManufacturer(Manufacturer.KAWASAKI)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadRobots()
    }

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

    fun toggleConnection() {
        if (_connectedRobotsIds.value.isNotEmpty()) {
            disconnectAll()
        } else {
            connectAll()
        }
    }

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

    fun disconnectAll() {
        _robots.value.forEach { robot ->
            terminalManager.disconnect(robot.id, clearHistory = false)
        }
    }

    fun sendCommandToAll(command: String) {
        if (command.isNotBlank()) {
            _commandHistory.update { it + "> $command" }
        }
        
        val connectedIds = _connectedRobotsIds.value
        _robots.value.filter { it.id in connectedIds }.forEach { robot ->
            terminalManager.sendCommand(robot.id, command)
        }
    }

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

    fun clearHistory() {
        _commandHistory.value = emptyList()
    }
}

class MultiRobotTerminalViewModelFactory(
    private val repository: RobotRepository,
    private val terminalManager: KawasakiTerminalManager,
    private val projectName: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MultiRobotTerminalViewModel(repository, terminalManager, projectName) as T
    }
}
