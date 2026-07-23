package my.robots.ui.robot

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import my.robots.data.remote.KawasakiTerminalManager
import my.robots.data.repository.RobotRepository

class RobotDashboardViewModelFactory(
    private val repository: RobotRepository,
    private val robotId: Int,
    private val terminalManager: KawasakiTerminalManager,
    private val backupId: Int? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RobotDashboardViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RobotDashboardViewModel(repository, robotId, terminalManager, backupId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
