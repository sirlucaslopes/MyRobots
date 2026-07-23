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

class QuickCommandViewModel(
    private val repository: RobotRepository,
    private val terminalManager: KawasakiTerminalManager,
    val manufacturer: Manufacturer,
    val robotId: Int? = null
) : ViewModel() {

    val commands: StateFlow<List<QuickCommand>> = repository.getQuickCommandsByManufacturer(manufacturer)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _robot = MutableStateFlow<Robot?>(null)
    val robot: StateFlow<Robot?> = _robot.asStateFlow()

    init {
        robotId?.let { id ->
            viewModelScope.launch {
                _robot.value = repository.getRobotById(id)
            }
        }
    }

    fun addCommand(label: String, command: String) {
        viewModelScope.launch {
            repository.insertQuickCommand(
                QuickCommand(
                    manufacturer = manufacturer,
                    label = label,
                    command = command
                )
            )
        }
    }

    fun updateCommand(command: QuickCommand) {
        viewModelScope.launch {
            repository.updateQuickCommand(command)
        }
    }

    fun deleteCommand(command: QuickCommand) {
        viewModelScope.launch {
            repository.deleteQuickCommand(command)
        }
    }

    fun sendQuickCommand(cmd: QuickCommand) {
        val currentRobot = _robot.value ?: return
        
        // Substitui a tag [ROBOT] pelo nome do robô
        var finalCommand = cmd.command.replace("[ROBOT]", currentRobot.name, ignoreCase = true)
        
        // Substitui a tag [DATA] pelo formato solicitado _aaaammdd_hhmm
        if (finalCommand.contains("[DATA]", ignoreCase = true)) {
            val timestamp = SimpleDateFormat("_yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
            finalCommand = finalCommand.replace("[DATA]", timestamp, ignoreCase = true)
        }
        
        terminalManager.sendCommand(currentRobot.id, finalCommand)
    }
}

class QuickCommandViewModelFactory(
    private val repository: RobotRepository,
    private val terminalManager: KawasakiTerminalManager,
    private val manufacturer: Manufacturer,
    private val robotId: Int? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(QuickCommandViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return QuickCommandViewModel(repository, terminalManager, manufacturer, robotId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
