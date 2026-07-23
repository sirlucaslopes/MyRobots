package my.robots.ui.robot

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import my.robots.data.repository.RobotRepository

class RobotViewModelFactory(private val repository: RobotRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RobotViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RobotViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
