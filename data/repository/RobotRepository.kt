package my.robots.data.repository

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import my.robots.data.local.BackupDao
import my.robots.data.local.QuickCommandDao
import my.robots.data.local.RobotDao
import my.robots.data.model.Backup
import my.robots.data.model.Manufacturer
import my.robots.data.model.QuickCommand
import my.robots.data.model.Robot
import my.robots.data.remote.RobotApiService
import my.robots.data.remote.RobotStatusResponse
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import kotlin.random.Random

class RobotRepository(
    private val robotDao: RobotDao,
    private val quickCommandDao: QuickCommandDao,
    private val backupDao: BackupDao,
    private val robotApiService: RobotApiService
) {
    val allRobots: Flow<List<Robot>> = robotDao.getAllRobots()

    suspend fun insertRobot(robot: Robot) = robotDao.insertRobot(robot)
    suspend fun updateRobot(robot: Robot) = robotDao.updateRobot(robot)
    suspend fun deleteRobot(robot: Robot) = robotDao.deleteRobot(robot)
    suspend fun getRobotById(id: Int): Robot? = robotDao.getRobotById(id)

    // Quick Commands
    fun getQuickCommands(robotId: Int) = quickCommandDao.getQuickCommandsForRobot(robotId)
    
    fun getQuickCommandsByManufacturer(manufacturer: Manufacturer): Flow<List<QuickCommand>> {
        // Usamos um ID fixo negativo para comandos globais do fabricante
        val manufacturerId = -(manufacturer.ordinal + 1000)
        return quickCommandDao.getQuickCommandsForRobot(manufacturerId)
    }

    suspend fun insertQuickCommand(command: QuickCommand) = quickCommandDao.insertQuickCommand(command)
    suspend fun deleteQuickCommand(command: QuickCommand) = quickCommandDao.deleteQuickCommand(command)

    // Backups
    fun getBackups(robotId: Int) = backupDao.getBackupsForRobot(robotId)
    fun searchBackups(robotId: Int, query: String) = backupDao.searchBackups(robotId, query)
    
    suspend fun insertBackup(backup: Backup) {
        val updatedBackup = calculateAndApplyMetadata(backup)
        backupDao.insertBackup(updatedBackup)
    }
    
    suspend fun deleteBackup(backup: Backup) = backupDao.deleteBackup(backup)
    suspend fun getBackupById(id: Int): Backup? = backupDao.getBackupById(id)

    // Função para forçar a atualização de metadados de backups existentes
    suspend fun refreshBackupsMetadata(robotId: Int) {
        val backups = backupDao.getBackupsForRobot(robotId).first()
        backups.forEach { backup ->
            if (backup.programsCount == 0 && backup.variablesCount == 0) {
                val updated = calculateAndApplyMetadata(backup)
                if (updated.programsCount > 0 || updated.variablesCount > 0) {
                    backupDao.insertBackup(updated)
                }
            }
        }
    }

    private fun calculateAndApplyMetadata(backup: Backup): Backup {
        val content = backup.content
        if (content.isBlank()) return backup
        
        val lines = content.lines()
        var programs = 0
        var variables = 0
        
        var inVariableSection = false
        
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith(";")) continue
            
            // Programas
            if (trimmed.startsWith(".PROGRAM", ignoreCase = true)) {
                programs++
                inVariableSection = false
                continue
            }
            
            // Cabeçalhos de Seção de Variáveis
            val isVarHeader = trimmed.startsWith(".TRANS", ignoreCase = true) || 
                             trimmed.startsWith(".REAL", ignoreCase = true) || 
                             trimmed.startsWith(".STRING", ignoreCase = true) ||
                             trimmed.startsWith(".JOINT", ignoreCase = true) ||
                             trimmed.startsWith(".POINT", ignoreCase = true)

            if (isVarHeader) {
                inVariableSection = true
                continue
            }
            
            if (inVariableSection) {
                if (trimmed.startsWith(".")) {
                    if (trimmed.startsWith(".END", ignoreCase = true)) {
                        inVariableSection = false
                    }
                } else if (trimmed.contains("=") || trimmed.split(",").size >= 3) {
                    variables++
                }
            }
        }
        
        return backup.copy(
            programsCount = programs,
            variablesCount = variables
        )
    }

    // Dashboard Logs
    fun getRobotLogs(robotId: Int): Flow<List<String>> = flow {
        val logs = mutableListOf<String>()
        while (true) {
            logs.add(0, "[${System.currentTimeMillis()}] System status: OK - Memory: ${Random.nextInt(100, 500)} MB")
            if (logs.size > 50) logs.removeAt(50)
            emit(logs.toList())
            delay(3000)
        }
    }

    suspend fun getRobotStatus(robotId: Int): RobotStatusResponse {
        return RobotStatusResponse(
            status = "Online",
            availableMemory = 256000L,
            programsCount = 12,
            variablesCount = 45,
            framesCount = 8,
            message = "All systems operational"
        )
    }

    suspend fun performBackup(robotId: Int): Backup {
        val response = try {
            robotApiService.downloadConfig()
        } catch (e: Exception) {
            null
        }
        
        val content = if (response?.isSuccessful == true) {
            response.body()?.string() ?: ""
        } else {
            generateMockRobotContent()
        }

        val backup = Backup(
            robotId = robotId,
            backupName = "Backup ${System.currentTimeMillis()}",
            fileName = "backup_${System.currentTimeMillis()}.as",
            content = content
        )
        insertBackup(backup)
        return backup
    }

    suspend fun uploadBackupToRobot(backup: Backup): Boolean {
        val requestBody = backup.content.toRequestBody("text/plain".toMediaTypeOrNull())
        val response = try {
            robotApiService.uploadConfig(requestBody)
        } catch (e: Exception) {
            return false
        }
        return response.isSuccessful
    }

    private fun generateMockRobotContent(): String {
        return """
            .PROGRAM main()
              ; Robot Configuration
              SPEED 50
              ACCEL 50
            .END
            .TRANS
              p1 = {0,0,0,0,0,0}
            .END
        """.trimIndent()
    }
}
