package my.robots.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import my.robots.core.network.KawasakiTerminalManager
import my.robots.core.data.ControllerChecks
import my.robots.core.data.RobotRepository

/**
 * Ensina o Android a criar o RobotDashboardViewModel com o repositório, o robô, o terminal, as checagens do controlador e o backup.
 */
class RobotDashboardViewModelFactory(
    private val repository: RobotRepository,
    private val robotId: Int,
    private val terminalManager: KawasakiTerminalManager,
    private val checks: ControllerChecks,
    private val backupId: Int? = null
) : ViewModelProvider.Factory {
    /**
     * Cria o ViewModel pedido. Se for pedido outro tipo, dá erro.
     */
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RobotDashboardViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RobotDashboardViewModel(repository, robotId, terminalManager, checks, backupId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
