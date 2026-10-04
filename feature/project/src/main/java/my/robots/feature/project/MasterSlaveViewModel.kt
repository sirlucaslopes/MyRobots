package my.robots.feature.project

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import my.robots.core.data.MasterSlaveConfig
import my.robots.core.data.MasterSlaveOptions
import my.robots.core.data.RobotRepository
import my.robots.core.model.ProjectLayout
import my.robots.core.model.Robot

/**
 * Uma configuração mestre -> escravo: o projeto escravo (destino), o mestre (origem), a
 * variável de offset, o robô de origem de cada robô de destino e as opções da transferência.
 */
data class SlaveSetup(
    val slaveProject: String,
    val masterProject: String,
    val offset: String,
    val pairs: Map<Int, Int?>,
    val config: MasterSlaveConfig
)

/**
 * Cérebro da tela "Mestre / Escravo": todas as configurações de uma vez. O projeto mestre, o
 * offset e os pares ficam no banco (RobotRepository.saveMasterConfig); as opções da
 * transferência, no MasterSlaveOptions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MasterSlaveViewModel(
    private val repository: RobotRepository,
    private val options: MasterSlaveOptions
) : ViewModel() {

    val robots: StateFlow<List<Robot>> = repository.allRobots
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Projetos com robôs, em ordem alfabética. */
    val projects: StateFlow<List<String>> = repository.allRobots
        .map { all -> all.map { it.project }.filter { it.isNotBlank() }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val layouts = repository.allRobots
        .map { all -> all.map { it.project }.filter { it.isNotBlank() }.distinct().sorted() }
        .distinctUntilChanged()
        .flatMapLatest { names ->
            if (names.isEmpty()) flowOf(emptyList())
            else combine(names.map { repository.getProjectLayout(it) }) { it.toList() }
        }

    /** As configurações existentes (projetos escravos com mestre). */
    val setups: StateFlow<List<SlaveSetup>> = combine(layouts, repository.allRobots, options.all) { layouts, robots, configs ->
        layouts.filter { it.masterProject != null }.map { l ->
            SlaveSetup(
                slaveProject = l.projectName,
                masterProject = l.masterProject!!,
                offset = l.baseOffset,
                pairs = robots.filter { it.project == l.projectName }.associate { it.id to it.masterRobotId },
                config = configs[l.projectName] ?: MasterSlaveConfig()
            )
        }.sortedBy { it.masterProject + it.slaveProject }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Grava a configuração (banco + opções). */
    fun save(setup: SlaveSetup) {
        viewModelScope.launch {
            repository.saveMasterConfig(
                setup.slaveProject,
                setup.masterProject,
                setup.offset.trim().ifBlank { ProjectLayout.DEFAULT_BASE_OFFSET },
                setup.pairs
            )
            options.save(setup.slaveProject, setup.config)
        }
    }

    /** Desfaz a configuração: o projeto deixa de ter mestre e os robôs perdem o par. */
    fun remove(setup: SlaveSetup) {
        viewModelScope.launch {
            repository.saveMasterConfig(setup.slaveProject, null, setup.offset, setup.pairs.mapValues { null })
            options.remove(setup.slaveProject)
        }
    }

    /** Pares pela mesma vaga na cabine (R10 na vaga 2,1 do mestre -> o robô na vaga 2,1 do escravo). */
    fun pairByPosition(slaveProject: String, masterProject: String): Map<Int, Int?> {
        val all = robots.value
        val masters = all.filter { it.project == masterProject }
        return all.filter { it.project == slaveProject }.associate { r ->
            r.id to masters.firstOrNull { it.layoutRow != null && it.layoutRow == r.layoutRow && it.layoutCol == r.layoutCol }?.id
        }
    }

    /** Cria uma configuração nova, já pareada pela posição e com as opções padrão. */
    fun create(slaveProject: String, masterProject: String) {
        save(SlaveSetup(slaveProject, masterProject, ProjectLayout.DEFAULT_BASE_OFFSET, pairByPosition(slaveProject, masterProject), MasterSlaveConfig()))
    }
}

class MasterSlaveViewModelFactory(
    private val repository: RobotRepository,
    private val options: MasterSlaveOptions
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = MasterSlaveViewModel(repository, options) as T
}
