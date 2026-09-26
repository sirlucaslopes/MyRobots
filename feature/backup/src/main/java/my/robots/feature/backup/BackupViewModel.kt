package my.robots.feature.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.robots.core.model.Backup
import my.robots.core.model.BackupSummary
import my.robots.core.model.Robot
import my.robots.core.data.RobotRepository
import my.robots.core.common.FileUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cérebro da tela de histórico de backups de UM robô.
 *
 * Cuida da busca, da ordenação, da sincronização com a pasta /MyRobots e das
 * ações de criar, importar, duplicar e excluir backups.
 */
class BackupViewModel(
    private val repository: RobotRepository,
    private val robotId: Int
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    /**
     * Texto digitado na busca.
     */
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isDescending = MutableStateFlow(true)
    /**
     * true = backups do mais novo para o mais antigo; false = do mais antigo para o mais novo.
     */
    val isDescending: StateFlow<Boolean> = _isDescending.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    /**
     * true enquanto uma importação está em andamento (mostra a barrinha de progresso).
     */
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * Lista de backups mostrada na tela (só o resumo, leve).
     *
     * Se combina com a busca (espera 300 ms depois de parar de digitar) e com a
     * ordenação. Muda sozinha quando o banco muda.
     */
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

    // Ao abrir a tela: sincroniza com a pasta e recalcula as contagens dos backups.
    init {
        syncBackupsWithFileSystem()
        viewModelScope.launch {
            repository.refreshBackupsMetadata(robotId)
        }
    }

    /**
     * Traz para o banco os .as novos da pasta do robô (ex.: um SAVE feito pelo terminal ou um
     * arquivo copiado pelo PC para a pasta escolhida) e recalcula as contagens.
     * Nunca apaga backup do banco (ver RobotRepository.syncRobotFolder).
     */
    fun syncBackupsWithFileSystem() {
        viewModelScope.launch {
            val robot = repository.getRobotById(robotId) ?: return@launch
            if (repository.syncRobotFolder(robot) > 0) {
                repository.refreshBackupsMetadata(robotId)
            }
        }
    }

    /**
     * Guarda o texto digitado na busca.
     */
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * Inverte a ordem da lista (novo primeiro <-> antigo primeiro).
     */
    fun toggleSortOrder() {
        _isDescending.value = !_isDescending.value
    }

    /**
     * Cria uma cópia de um backup com o nome informado (o arquivo da cópia começa com "copy_" + data).
     */
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

    /**
     * Cria um backup a partir de um arquivo escolhido no celular (nome já "limpo" e texto do arquivo).
     */
    fun importBackup(fileName: String, content: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val sanitizedFileName = FileUtil.sanitizeFileName(fileName)
            val backup = Backup(
                robotId = robotId,
                backupName = "Imported: $sanitizedFileName",
                fileName = sanitizedFileName,
                content = content,
                timestamp = System.currentTimeMillis()
            )
            repository.insertBackup(backup)
            _isLoading.value = false
        }
    }

    /**
     * Exclui um backup: remove do banco E apaga o arquivo da pasta do robô.
     */
    fun deleteBackup(backupId: Int, fileName: String) {
        viewModelScope.launch {
            repository.deleteBackupAndFile(backupId, fileName)
        }
    }

    /**
     * Busca os dados do robô dono desta lista.
     */
    suspend fun getRobot(): Robot? {
        return repository.getRobotById(robotId)
    }

    /**
     * Busca um backup COM o texto completo (para exportar ou compartilhar).
     */
    suspend fun getFullBackup(id: Int): Backup? = repository.getBackupById(id)
}
