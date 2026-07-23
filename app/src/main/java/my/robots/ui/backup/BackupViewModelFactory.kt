package my.robots.ui.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import my.robots.data.repository.RobotRepository

class BackupViewModelFactory(
    private val repository: RobotRepository,
    private val robotId: Int
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BackupViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return BackupViewModel(repository, robotId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
