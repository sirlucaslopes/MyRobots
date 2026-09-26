package my.robots.feature.robots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import my.robots.core.model.Manufacturer
import my.robots.core.model.Robot
import my.robots.core.data.RobotRepository

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
