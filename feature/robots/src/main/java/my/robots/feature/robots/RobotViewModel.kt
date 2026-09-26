package my.robots.feature.robots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import my.robots.core.model.Manufacturer
import my.robots.core.model.Robot
import my.robots.core.data.RobotRepository
import my.robots.core.data.storage.StorageLocation

/**
 * Cérebro da tela de lista de robôs.
 *
 * Fornece a lista de robôs, cadastra/edita/exclui e, ao abrir, traz para o banco os
 * arquivos .as novos da pasta de cada robô.
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

    /**
     * Onde os arquivos estão sendo gravados (janela "Pasta dos arquivos").
     */
    val storageLocation: StateFlow<StorageLocation> = repository.storageLocation

    private val _storageBusy = MutableStateFlow(false)
    /** true enquanto grava/importa arquivos depois de trocar de pasta. */
    val storageBusy: StateFlow<Boolean> = _storageBusy.asStateFlow()

    private val _storageMessage = MutableStateFlow<String?>(null)
    /** Resultado da última troca de pasta, para mostrar na janela. */
    val storageMessage: StateFlow<String?> = _storageMessage.asStateFlow()

    // Ao criar o ViewModel, já sincroniza as pastas dos robôs com o banco.
    init {
        syncAllRobotsFileSystem()
    }

    /**
     * Traz para o banco os .as novos da pasta de cada robô (ex.: SAVE feito pelo terminal).
     * Nunca apaga backup do banco (ver RobotRepository.syncRobotFolder).
     */
    private fun syncAllRobotsFileSystem() {
        viewModelScope.launch {
            repository.syncAllRobotFolders()
        }
    }

    /**
     * Ao abrir a janela "Pasta dos arquivos": reconfere a pasta escolhida e limpa a mensagem.
     */
    fun refreshStorage() {
        repository.refreshStorageLocation()
        _storageMessage.value = null
    }

    /**
     * Passa a usar a pasta escolhida no seletor: grava nela os backups que faltam e importa os
     * .as que já estavam lá.
     */
    fun chooseStorageFolder(treeUri: Uri) {
        viewModelScope.launch {
            _storageBusy.value = true
            _storageMessage.value = try {
                val (written, imported) = repository.useStorageFolder(treeUri)
                "Pasta conectada. $written arquivo(s) gravado(s), $imported backup(s) importado(s)."
            } catch (e: Exception) {
                "Não foi possível usar esta pasta: ${e.message}"
            }
            _storageBusy.value = false
        }
    }

    /**
     * Volta para a pasta padrão Documentos/MyRobots e grava nela os backups que faltam.
     */
    fun useDefaultStorage() {
        viewModelScope.launch {
            _storageBusy.value = true
            _storageMessage.value = try {
                val written = repository.useDefaultStorage()
                "Usando Documentos/MyRobots. $written arquivo(s) gravado(s)."
            } catch (e: Exception) {
                "Não foi possível voltar para a pasta padrão: ${e.message}"
            }
            _storageBusy.value = false
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
