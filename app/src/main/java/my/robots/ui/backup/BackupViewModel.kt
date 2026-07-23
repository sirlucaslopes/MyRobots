package my.robots.ui.backup

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.robots.data.model.Backup
import my.robots.data.model.BackupSummary
import my.robots.data.model.Robot
import my.robots.data.repository.RobotRepository
import my.robots.utils.FileUtil
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupViewModel(
    private val repository: RobotRepository,
    private val robotId: Int
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isDescending = MutableStateFlow(true)
    val isDescending: StateFlow<Boolean> = _isDescending.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val backups: StateFlow<List<BackupSummary>> = combine(
        _searchQuery.debounce(300),
        _isDescending
    ) { query, isDesc ->
        Pair(query, isDesc)
    }.flatMapLatest { (query, isDesc) ->
        val flow = if (query.isBlank()) {
            repository.getBackupsSummary(robotId)
        } else {
            repository.searchBackupsSummary(robotId, query)
        }
        
        flow.map { list ->
            if (isDesc) {
                list.sortedByDescending { it.timestamp }
            } else {
                list.sortedBy { it.timestamp }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        syncBackupsWithFileSystem()
        viewModelScope.launch {
            repository.refreshBackupsMetadata(robotId)
        }
    }

    fun syncBackupsWithFileSystem() {
        viewModelScope.launch {
            val robot = repository.getRobotById(robotId) ?: return@launch
            val robotNameClean = robot.name.lowercase().replace(Regex("[^a-zA-Z0-9_]"), "_")
            val root = Environment.getExternalStorageDirectory()
            val myRobotsDir = File(root, "MyRobots")
            
            if (!myRobotsDir.exists()) {
                val dbBackups = repository.getBackupsForRobotFull(robotId).first()
                dbBackups.forEach { repository.deleteBackup(it) }
                return@launch
            }

            val foldersToSearch = mutableListOf(myRobotsDir)
            val robotSpecificDir = File(myRobotsDir, robotNameClean)
            if (robotSpecificDir.exists() && robotSpecificDir.isDirectory) {
                foldersToSearch.add(robotSpecificDir)
            }

            val dbBackups = repository.getBackupsForRobotFull(robotId).first()
            
            withContext(Dispatchers.IO) {
                val existingFiles = mutableSetOf<String>()
                var changed = false

                foldersToSearch.forEach { folder ->
                    val files = folder.listFiles { _, name -> 
                        name.endsWith(".as", ignoreCase = true) && 
                        (folder != myRobotsDir || name.startsWith(robotNameClean, ignoreCase = true))
                    } ?: emptyArray()
                    
                    files.forEach { file -> 
                        existingFiles.add(file.name.lowercase()) 
                        
                        val alreadyInDb = dbBackups.any { it.fileName.equals(file.name, ignoreCase = true) }
                        if (!alreadyInDb) {
                            try {
                                val content = file.readText()
                                val backup = Backup(
                                    robotId = robotId,
                                    backupName = "Sinc: ${file.name}",
                                    fileName = file.name,
                                    content = content,
                                    timestamp = file.lastModified()
                                )
                                repository.insertBackup(backup)
                                changed = true
                            } catch (e: Exception) { }
                        }
                    }
                }

                dbBackups.forEach { backup ->
                    if (!existingFiles.contains(backup.fileName.lowercase())) {
                        repository.deleteBackup(backup)
                        changed = true
                    }
                }
                
                if (changed) {
                    repository.refreshBackupsMetadata(robotId)
                }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleSortOrder() {
        _isDescending.value = !_isDescending.value
    }

    fun createBackup(type: BackupType = BackupType.FULL) {
        viewModelScope.launch {
            val robot = repository.getRobotById(robotId)
            val robotName = robot?.name?.lowercase()?.replace(Regex("[^a-zA-Z0-9_]"), "_") ?: "robot"
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
            val baseFileName = "${robotName}_${type.label.lowercase()}_$timestamp.as"
            val sanitizedFileName = FileUtil.sanitizeFileName(baseFileName)
            
            val backup = repository.performBackup(robotId)
            
            val enrichedBackup = backup.copy(
                backupName = "${type.label} - ${SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date())}",
                fileName = sanitizedFileName,
                timestamp = System.currentTimeMillis()
            )
            repository.insertBackup(enrichedBackup)
        }
    }

    fun duplicateBackup(backupId: Int, newName: String) {
        viewModelScope.launch {
            val original = repository.getBackupById(backupId) ?: return@launch
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
            val rawFileName = "copy_$timestamp" + "_" + original.fileName
            val sanitizedFileName = FileUtil.sanitizeFileName(rawFileName)
            
            val duplicated = original.copy(
                id = 0,
                backupName = newName,
                fileName = sanitizedFileName,
                timestamp = System.currentTimeMillis()
            )
            repository.insertBackup(duplicated)
        }
    }

    fun importBackup(fileName: String, content: String) {
        viewModelScope.launch {
            val sanitizedFileName = FileUtil.sanitizeFileName(fileName)
            val backup = Backup(
                robotId = robotId,
                backupName = "Imported: $sanitizedFileName",
                fileName = sanitizedFileName,
                content = content,
                timestamp = System.currentTimeMillis()
            )
            repository.insertBackup(backup)
        }
    }

    fun deleteBackup(backupId: Int, fileName: String) {
        viewModelScope.launch {
            repository.deleteBackupById(backupId)
            
            val robot = repository.getRobotById(robotId) ?: return@launch
            val robotDirName = robot.name.lowercase().replace(Regex("[^a-zA-Z0-9_]"), "_")
            val root = Environment.getExternalStorageDirectory()
            val myRobotsRootDir = File(root, "MyRobots")
            
            val robotFile = File(File(myRobotsRootDir, robotDirName), fileName)
            if (robotFile.exists()) robotFile.delete()
            
            val rootFile = File(myRobotsRootDir, fileName)
            if (rootFile.exists()) rootFile.delete()
        }
    }

    suspend fun getRobot(): Robot? {
        return repository.getRobotById(robotId)
    }

    suspend fun getFullBackup(id: Int): Backup? = repository.getBackupById(id)
}
