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
 * Cérebro da tela de comandos rápidos.
 *
 * Lista, cria, edita e apaga comandos da marca e envia o escolhido ao robô.
 */
class QuickCommandViewModel(
    private val repository: RobotRepository,
    private val terminalManager: KawasakiTerminalManager,
    val manufacturer: Manufacturer,
    val robotId: Int? = null
) : ViewModel() {

    /**
     * Comandos rápidos da marca do robô. Atualiza sozinha.
     */
    val commands: StateFlow<List<QuickCommand>> = repository.getQuickCommandsByManufacturer(manufacturer)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _robot = MutableStateFlow<Robot?>(null)
    /**
     * O robô que vai receber os comandos (carregado ao abrir a tela).
     */
    val robot: StateFlow<Robot?> = _robot.asStateFlow()

    // Se a tela foi aberta a partir de um robô, busca os dados dele.
    init {
        robotId?.let { id ->
            viewModelScope.launch {
                _robot.value = repository.getRobotById(id)
            }
        }
    }

    /**
     * Cria um comando rápido novo para a marca.
     */
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

    /**
     * Salva as alterações de um comando.
     */
    fun updateCommand(command: QuickCommand) {
        viewModelScope.launch {
            repository.updateQuickCommand(command)
        }
    }

    /**
     * Apaga um comando.
     */
    fun deleteCommand(command: QuickCommand) {
        viewModelScope.launch {
            repository.deleteQuickCommand(command)
        }
    }

    /**
     * Envia um comando rápido ao robô.
     * Antes de enviar, troca [ROBOT] pelo nome do robô e [DATA] por _aaaammdd_hhmm.
     */
    fun sendQuickCommand(cmd: QuickCommand) {
        val currentRobot = _robot.value ?: return
        
        // [ROBOT] vira o nome do robô
        var finalCommand = cmd.command.replace("[ROBOT]", currentRobot.name, ignoreCase = true)
        
        // [DATA] vira a data e hora no formato _aaaammdd_hhmm
        if (finalCommand.contains("[DATA]", ignoreCase = true)) {
            val timestamp = SimpleDateFormat("_yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
            finalCommand = finalCommand.replace("[DATA]", timestamp, ignoreCase = true)
        }
        
        terminalManager.sendCommand(currentRobot.id, finalCommand)
    }
}

/**
 * Ensina o Android a criar o QuickCommandViewModel com tudo o que ele precisa.
 */
class QuickCommandViewModelFactory(
    private val repository: RobotRepository,
    private val terminalManager: KawasakiTerminalManager,
    private val manufacturer: Manufacturer,
    private val robotId: Int? = null
) : ViewModelProvider.Factory {
    /**
     * Cria o ViewModel pedido. Se for pedido outro tipo, dá erro.
     */
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(QuickCommandViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return QuickCommandViewModel(repository, terminalManager, manufacturer, robotId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
