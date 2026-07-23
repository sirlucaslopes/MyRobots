package my.robots.data.repository

import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import my.robots.data.local.BackupDao
import my.robots.data.local.QuickCommandDao
import my.robots.data.local.RobotDao
import my.robots.data.model.*
import my.robots.data.remote.RobotApiService
import my.robots.data.remote.RobotStatusResponse
import my.robots.utils.FileUtil
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class RobotRepository(
    private val robotDao: RobotDao,
    private val quickCommandDao: QuickCommandDao,
    private val backupDao: BackupDao,
    private val robotApiService: RobotApiService
) {
    val allRobots: Flow<List<Robot>> = robotDao.getAllRobots()

    suspend fun insertRobot(robot: Robot) {
        val id = robotDao.insertRobot(robot).toInt()
        createRobotFolder(robot.copy(id = id))
        seedQuickCommandsForRobot(id, robot.manufacturer)
    }

    private suspend fun seedQuickCommandsForRobot(robotId: Int, manufacturer: Manufacturer) {
        val commands = RobotCommandLibrary.getCommandsForManufacturer(manufacturer)
        commands.forEach { cmd ->
            quickCommandDao.insertQuickCommand(
                QuickCommand(
                    robotId = robotId,
                    manufacturer = manufacturer,
                    label = cmd.label,
                    command = cmd.command
                )
            )
        }
    }

    suspend fun updateRobot(robot: Robot) {
        robotDao.updateRobot(robot)
        createRobotFolder(robot)
    }

    fun createRobotFolder(robot: Robot) {
        try {
            val root = Environment.getExternalStorageDirectory()
            val myRobotsDir = File(root, "MyRobots")
            val robotDirName = robot.name.lowercase().replace(Regex("[^a-zA-Z0-9_]"), "_")
            val robotDir = File(myRobotsDir, robotDirName)
            if (!robotDir.exists()) {
                robotDir.mkdirs()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun deleteRobot(robot: Robot) = robotDao.deleteRobot(robot)
    suspend fun getRobotById(id: Int): Robot? = robotDao.getRobotById(id)

    // Quick Commands
    fun getQuickCommands(robotId: Int) = quickCommandDao.getQuickCommandsForRobot(robotId)
    
    fun getQuickCommandsByManufacturer(manufacturer: Manufacturer): Flow<List<QuickCommand>> {
        return quickCommandDao.getAllQuickCommands().map { all ->
            all.filter { it.manufacturer == manufacturer }
        }
    }

    suspend fun insertQuickCommand(command: QuickCommand) = quickCommandDao.insertQuickCommand(command)
    suspend fun updateQuickCommand(command: QuickCommand) = quickCommandDao.insertQuickCommand(command)
    suspend fun deleteQuickCommand(command: QuickCommand) = quickCommandDao.deleteQuickCommand(command)

    // Backups
    fun getBackupsSummary(robotId: Int) = backupDao.getBackupsSummaryForRobot(robotId)
    fun searchBackupsSummary(robotId: Int, query: String) = backupDao.searchBackupsSummary(robotId, query)
    fun getBackupsForRobotFull(robotId: Int) = backupDao.getBackupsForRobot(robotId)
    
    suspend fun insertBackup(backup: Backup, saveToFile: Boolean = true) {
        val sanitizedBackup = backup.copy(fileName = FileUtil.sanitizeFileName(backup.fileName))
        val updatedBackup = calculateAndApplyMetadata(sanitizedBackup)
        backupDao.insertBackup(updatedBackup)
        if (saveToFile) {
            saveBackupToFile(updatedBackup)
        }
    }
    
    suspend fun deleteBackup(backup: Backup) = backupDao.deleteBackup(backup)
    suspend fun deleteBackupById(id: Int) = backupDao.deleteBackupById(id)
    suspend fun getBackupById(id: Int): Backup? = backupDao.getBackupById(id)

    suspend fun saveBackupToFile(backup: Backup) {
        saveFileToRobotFolder(backup.robotId, backup.fileName, backup.content)
    }

    suspend fun saveFileToRobotFolder(robotId: Int, fileName: String, content: String) {
        val robot = getRobotById(robotId) ?: return
        val robotDirName = robot.name.lowercase().replace(Regex("[^a-zA-Z0-9_]"), "_")
        val root = Environment.getExternalStorageDirectory()
        val myRobotsRootDir = File(root, "MyRobots")
        val robotDir = File(myRobotsRootDir, robotDirName)
        
        if (!myRobotsRootDir.exists()) myRobotsRootDir.mkdirs()
        if (!robotDir.exists()) robotDir.mkdirs()
        
        withContext(Dispatchers.IO) {
            try {
                val sanitizedFileName = FileUtil.sanitizeFileName(fileName)
                val robotFile = File(robotDir, sanitizedFileName)
                robotFile.writeText(content)
            } catch (e: Exception) { }
        }
    }

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
            
            if (trimmed.startsWith(".PROGRAM", ignoreCase = true)) {
                programs++
                inVariableSection = false
                continue
            }
            
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
            variablesCount = variables,
            memoryUsage = content.length.toLong()
        )
    }

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

        val robot = getRobotById(robotId)
        val robotNameClean = robot?.name?.lowercase()?.replace(Regex("[^a-zA-Z0-9_]"), "_") ?: "robot"
        val timestamp = SimpleDateFormat("_yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        val rawFileName = "${robotNameClean}${timestamp}.as"
        val sanitizedFileName = FileUtil.sanitizeFileName(rawFileName)

        val backup = Backup(
            robotId = robotId,
            backupName = "Backup ${SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date())}",
            fileName = sanitizedFileName,
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
