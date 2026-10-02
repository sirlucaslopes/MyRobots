package my.robots.feature.project

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import my.robots.core.common.layout.CabinState
import my.robots.core.common.layout.Cell
import my.robots.core.common.layout.LayoutOps
import my.robots.core.data.RobotRepository
import my.robots.core.model.EquipmentType
import my.robots.core.model.HeartbeatState
import my.robots.core.model.ProjectEquipment
import my.robots.core.model.ProjectLayout
import my.robots.core.model.Robot
import my.robots.core.network.KawasakiTerminalManager

/**
 * O que a tela de Projeto desenha: a grade ([cabin]), os equipamentos (na mesma ordem de
 * `cabin.bandPositions`) e os robôs do projeto.
 */
data class CabinView(
    val cabin: CabinState,
    val equipment: List<ProjectEquipment>,
    val robots: List<Robot>
) {
    val robotsById: Map<Int, Robot> = robots.associateBy { it.id }
    /** Robôs sem vaga na grade, na ordem do nome. */
    val outside: List<Robot> = robots.filter { it.id !in cabin.placed }.sortedBy { it.name }
}

/**
 * Cérebro da tela de Projeto.
 *
 * - Observa os robôs do projeto, o layout e os equipamentos, e junta tudo em [view] (o que
 *   vem do banco passa por `LayoutOps.sanitize`).
 * - Acompanha conexão e heartbeat de cada robô, com um coletor por id ([watchedIds]), igual
 *   ao `ConnectedRobotsViewModel`.
 * - Edição: [startEdit] copia o layout para [draft]; as funções de edição mexem só nessa
 *   cópia; [save] grava tudo numa transação e [cancelEdit] descarta.
 */
class ProjectViewModel(
    private val repository: RobotRepository,
    private val terminalManager: KawasakiTerminalManager,
    val projectName: String
) : ViewModel() {

    private val robots = repository.allRobots.map { all -> all.filter { it.project == projectName } }

    /** Layout como está gravado, já saneado. */
    val view: StateFlow<CabinView?> = combine(
        robots,
        repository.getProjectLayout(projectName),
        repository.getProjectEquipment(projectName)
    ) { robots, layout, equipment ->
        val positions = robots.associate { r ->
            r.id to if (r.layoutRow != null && r.layoutCol != null) Cell(r.layoutRow!!, r.layoutCol!!) else null
        }
        val cabin = LayoutOps.sanitize(layout.rowCount, layout.colCount, positions, equipment.map { it.position })
        CabinView(cabin, equipment.mapIndexed { i, e -> e.copy(position = cabin.bandPositions[i]) }, robots)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _draft = MutableStateFlow<CabinView?>(null)
    /** Cópia em edição (null = modo visualização). */
    val draft: StateFlow<CabinView?> = _draft.asStateFlow()

    private val _selected = MutableStateFlow<Int?>(null)
    /** Robô selecionado no modo de edição. */
    val selected: StateFlow<Int?> = _selected.asStateFlow()

    private val _connectedIds = MutableStateFlow<Set<Int>>(emptySet())
    val connectedIds: StateFlow<Set<Int>> = _connectedIds.asStateFlow()

    private val _heartbeats = MutableStateFlow<Map<Int, HeartbeatState>>(emptyMap())
    val heartbeats: StateFlow<Map<Int, HeartbeatState>> = _heartbeats.asStateFlow()

    // Ids que já têm um observador de conexão/heartbeat rodando (evita duplicar o coletor).
    private val watchedIds = mutableSetOf<Int>()

    init {
        viewModelScope.launch {
            robots.collect { list -> list.forEach { watchRobot(it.id) } }
        }
    }

    private fun watchRobot(robotId: Int) {
        if (!watchedIds.add(robotId)) return
        viewModelScope.launch {
            terminalManager.getConnectionStatus(robotId).collect { on ->
                _connectedIds.update { if (on) it + robotId else it - robotId }
            }
        }
        viewModelScope.launch {
            terminalManager.getHeartbeat(robotId).collect { hb -> _heartbeats.update { it + (robotId to hb) } }
        }
    }

    // ---------- Conexão ----------

    /** Conecta o robô, ou desconecta se ele já estiver conectado. */
    fun toggleConnection(robot: Robot) {
        if (robot.id in _connectedIds.value) terminalManager.disconnect(robot.id, clearHistory = false)
        else terminalManager.connect(robot)
    }

    fun connectAll() {
        val list = view.value?.robots ?: return
        list.filter { it.id !in _connectedIds.value }.forEach { terminalManager.connect(it) }
    }

    fun disconnectAll() {
        val list = view.value?.robots ?: return
        list.filter { it.id in _connectedIds.value }.forEach { terminalManager.disconnect(it.id, clearHistory = false) }
    }

    // ---------- Edição ----------

    fun startEdit() {
        _draft.value = view.value
        _selected.value = null
    }

    fun cancelEdit() {
        _draft.value = null
        _selected.value = null
    }

    /** Grava a cópia em edição (grade, posições e equipamentos) e volta à visualização. */
    fun save() {
        val d = _draft.value ?: return
        viewModelScope.launch {
            val positions = d.robots.associate { r ->
                val cell = d.cabin.placed[r.id]
                r.id to (cell?.row to cell?.col)
            }
            val equipment = d.equipment.mapIndexed { i, e -> e.copy(position = d.cabin.bandPositions[i], sortOrder = i) }
            repository.saveProjectLayout(ProjectLayout(projectName, d.cabin.rows, d.cabin.cols), positions, equipment)
            cancelEdit()
        }
    }

    /** Aplica uma operação do LayoutOps na cópia e mantém as faixas dos equipamentos em dia. */
    private fun edit(op: (CabinState) -> CabinState) {
        _draft.update { d ->
            d ?: return@update null
            val cabin = op(d.cabin)
            d.copy(cabin = cabin, equipment = d.equipment.mapIndexed { i, e -> e.copy(position = cabin.bandPositions[i]) })
        }
    }

    fun addRow() = edit(LayoutOps::addRow)
    fun addCol() = edit(LayoutOps::addCol)
    fun removeRow(row: Int) = edit { LayoutOps.removeRow(it, row) }
    fun removeCol(col: Int) = edit { LayoutOps.removeCol(it, col) }

    /** Tocar num robô: seleciona; com outro já selecionado, os dois trocam de lugar. */
    fun tapRobot(robotId: Int) {
        val sel = _selected.value
        val d = _draft.value ?: return
        val target = d.cabin.placed[robotId]
        when {
            sel == null || sel == robotId -> _selected.value = if (sel == robotId) null else robotId
            target != null -> { edit { LayoutOps.moveTo(it, sel, target) }; _selected.value = null }
            else -> _selected.value = robotId   // tocou num robô de fora: troca a seleção
        }
    }

    /** Tocar numa vaga vazia: move o robô selecionado para ela. */
    fun tapCell(cell: Cell) {
        val sel = _selected.value ?: return
        edit { LayoutOps.moveTo(it, sel, cell) }
        _selected.value = null
    }

    /** Tira o robô selecionado da grade. */
    fun takeSelectedOut() {
        val sel = _selected.value ?: return
        edit { LayoutOps.removeFromLayout(it, sel) }
        _selected.value = null
    }

    fun placeAll() {
        val ids = _draft.value?.robots?.sortedBy { it.name }?.map { it.id } ?: return
        edit { LayoutOps.placeAll(it, ids) }
    }

    // ---------- Equipamentos ----------

    /** Adiciona um equipamento abaixo da última linha. "Outro" precisa de nome. */
    fun addEquipment(type: EquipmentType, name: String) {
        _draft.update { d ->
            d ?: return@update null
            val position = d.cabin.rows
            val item = ProjectEquipment(
                projectName = projectName, type = type, name = name.trim(), position = position,
                flowDirection = if (type == EquipmentType.CONVEYOR) 1 else 0
            )
            d.copy(cabin = d.cabin.copy(bandPositions = d.cabin.bandPositions + position), equipment = d.equipment + item)
        }
    }

    /** Sobe (-1) ou desce (+1) o equipamento [index] uma faixa. */
    fun moveEquipment(index: Int, delta: Int) {
        _draft.update { d ->
            d ?: return@update null
            val pos = (d.cabin.bandPositions[index] + delta).coerceIn(0, d.cabin.rows)
            val bands = d.cabin.bandPositions.toMutableList().also { it[index] = pos }
            d.copy(
                cabin = d.cabin.copy(bandPositions = bands),
                equipment = d.equipment.mapIndexed { i, e -> if (i == index) e.copy(position = pos) else e }
            )
        }
    }

    /** Alterna o sentido: → (1), ← (-1), sem sentido (0). */
    fun toggleDirection(index: Int) {
        _draft.update { d ->
            d ?: return@update null
            d.copy(equipment = d.equipment.mapIndexed { i, e ->
                if (i != index) e else e.copy(flowDirection = when (e.flowDirection) { 1 -> -1; -1 -> 0; else -> 1 })
            })
        }
    }

    fun deleteEquipment(index: Int) {
        _draft.update { d ->
            d ?: return@update null
            d.copy(
                cabin = d.cabin.copy(bandPositions = d.cabin.bandPositions.filterIndexed { i, _ -> i != index }),
                equipment = d.equipment.filterIndexed { i, _ -> i != index }
            )
        }
    }

    // ---------- Renomear ----------

    /** Texto do erro para o nome novo, ou null se estiver ok. */
    fun validateNewName(name: String): String? = when {
        name.isBlank() -> "O nome não pode ser vazio"
        name.trim() == projectName -> "É o nome atual"
        else -> null
    }

    /** Renomeia o projeto e avisa o nome novo para a tela trocar de rota. */
    fun rename(newName: String, onDone: (String) -> Unit) {
        val name = newName.trim()
        if (validateNewName(name) != null) return
        viewModelScope.launch {
            repository.renameProject(projectName, name)
            onDone(name)
        }
    }
}

/**
 * Ensina o Android a criar o ProjectViewModel com o repositório, o terminal e o projeto.
 */
class ProjectViewModelFactory(
    private val repository: RobotRepository,
    private val terminalManager: KawasakiTerminalManager,
    private val projectName: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ProjectViewModel::class.java)) {
            return ProjectViewModel(repository, terminalManager, projectName) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
