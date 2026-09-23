package my.robots.feature.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import my.robots.core.data.RobotRepository

/**
 * Ensina o Android a criar o BackupViewModel, que precisa do repositório e do id do robô.
 */
class BackupViewModelFactory(
    private val repository: RobotRepository,
    private val robotId: Int
) : ViewModelProvider.Factory {
    /**
     * Cria o ViewModel pedido. Se for pedido outro tipo, dá erro.
     */
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BackupViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return BackupViewModel(repository, robotId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
