package my.robots.ui.robot

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.robots.data.model.Backup
import my.robots.data.model.Manufacturer
import my.robots.data.model.Robot
import my.robots.data.repository.RobotRepository
import java.io.File

class RobotViewModel(private val repository: RobotRepository) : ViewModel() {

    val robots: StateFlow<List<Robot>> = repository.allRobots
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        syncAllRobotsFileSystem()
    }

    private fun syncAllRobotsFileSystem() {
        viewModelScope.launch {
            val allRobotsList = repository.allRobots.first()
            allRobotsList.forEach { robot ->
                syncRobotBackups(robot)
            }
        }
    }

    private suspend fun syncRobotBackups(robot: Robot) {
        val robotNameClean = robot.name.lowercase().replace(Regex("[^a-zA-Z0-9_]"), "_")
        val root = Environment.getExternalStorageDirectory()
        val myRobotsDir = File(root, "MyRobots")
        val robotSpecificDir = File(myRobotsDir, robotNameClean)
        
        if (!robotSpecificDir.exists()) return

        val dbBackups = repository.getBackupsForRobotFull(robot.id).first()
        
        withContext(Dispatchers.IO) {
            val files = robotSpecificDir.listFiles { _, name -> 
                name.endsWith(".as", ignoreCase = true)
            } ?: emptyArray()
            
            val existingFiles = mutableSetOf<String>()
            var changed = false

            files.forEach { file -> 
                existingFiles.add(file.name.lowercase()) 
                val alreadyInDb = dbBackups.any { it.fileName.equals(file.name, ignoreCase = true) }
                
                if (!alreadyInDb) {
                    try {
                        val content = file.readText()
                        val backup = Backup(
                            robotId = robot.id,
                            backupName = "Sinc: ${file.name}",
                            fileName = file.name,
                            content = content,
                            timestamp = file.lastModified()
                        )
                        repository.insertBackup(backup, saveToFile = false)
                        changed = true
                    } catch (e: Exception) { }
                }
            }

            dbBackups.forEach { backup ->
                if (!existingFiles.contains(backup.fileName.lowercase())) {
                    repository.deleteBackup(backup)
                    changed = true
                }
            }
            
            if (changed) {
                repository.refreshBackupsMetadata(robot.id)
            }
        }
    }

    fun addRobot(
        name: String,
        ip: String,
        port: Int,
        project: String,
        manufacturer: Manufacturer,
        autoLogin: Boolean,
        loginUser: String,
        loginPassword: String
    ) {
        viewModelScope.launch {
            repository.insertRobot(
                Robot(
                    name = name,
                    ip = ip,
                    port = port,
                    project = project,
                    manufacturer = manufacturer,
                    autoLogin = autoLogin,
                    loginUser = loginUser,
                    loginPassword = loginPassword
                )
            )
        }
    }

    fun updateRobot(robot: Robot) {
        viewModelScope.launch {
            repository.updateRobot(robot)
        }
    }

    fun deleteRobot(robot: Robot) {
        viewModelScope.launch {
            repository.deleteRobot(robot)
        }
    }
}
