package my.robots.feature.robots

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
import my.robots.core.model.Backup
import my.robots.core.model.Manufacturer
import my.robots.core.model.Robot
import my.robots.core.data.RobotRepository
import java.io.File

/**
 * Cérebro da tela de lista de robôs.
 *
 * Fornece a lista de robôs, cadastra/edita/exclui e, ao abrir, sincroniza os
 * arquivos .as das pastas de /MyRobots com o banco de dados.
 */
class RobotViewModel(private val repository: RobotRepository) : ViewModel() {

    /**
     * Lista de robôs para a tela. Atualiza sozinha quando o banco muda.
     */
    val robots: StateFlow<List<Robot>> = repository.allRobots
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Ao criar o ViewModel, já sincroniza as pastas dos robôs com o banco.
    init {
        syncAllRobotsFileSystem()
    }

    /**
     * Faz a sincronização de arquivos para cada robô cadastrado.
     */
    private fun syncAllRobotsFileSystem() {
        viewModelScope.launch {
            val allRobotsList = repository.allRobots.first()
            allRobotsList.forEach { robot ->
                syncRobotBackups(robot)
            }
        }
    }

    /**
     * Deixa o banco igual à pasta do robô (/MyRobots/<robô>):
     * - arquivo .as que está na pasta mas não no banco -> vira um backup novo;
     * - backup que está no banco mas cujo arquivo sumiu -> é removido do banco.
     * Se algo mudou, recalcula as contagens de programas e variáveis.
     */
    private suspend fun syncRobotBackups(robot: Robot) {
        val robotNameClean = robot.name.lowercase().replace(Regex("[^a-zA-Z0-9_]"), "_")
        val root = Environment.getExternalStorageDirectory()
        val myRobotsDir = File(root, "MyRobots")
        val robotSpecificDir = File(myRobotsDir, robotNameClean)
        
        if (!robotSpecificDir.exists()) return

        val dbBackups = repository.getBackupsSummary(robot.id).first()
        
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
                    repository.deleteBackupById(backup.id)
                    changed = true
                }
            }
            
            if (changed) {
                repository.refreshBackupsMetadata(robot.id)
            }
        }
    }

    /**
     * Cadastra um robô novo com os dados informados.
     */
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

    /**
     * Salva as alterações de um robô.
     */
    fun updateRobot(robot: Robot) {
        viewModelScope.launch {
            repository.updateRobot(robot)
        }
    }

    /**
     * Exclui um robô.
     */
    fun deleteRobot(robot: Robot) {
        viewModelScope.launch {
            repository.deleteRobot(robot)
        }
    }
}
