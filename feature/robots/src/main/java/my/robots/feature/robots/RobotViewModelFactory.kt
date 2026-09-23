package my.robots.feature.robots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import my.robots.core.data.RobotRepository

/**
 * Ensina o Android a criar o RobotViewModel, que precisa receber o repositório.
 */
class RobotViewModelFactory(private val repository: RobotRepository) : ViewModelProvider.Factory {
    /**
     * Cria o ViewModel pedido. Se for pedido outro tipo, dá erro.
     */
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RobotViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RobotViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
