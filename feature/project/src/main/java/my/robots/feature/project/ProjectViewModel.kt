package my.robots.feature.project

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
import my.robots.core.common.FileUtil
import my.robots.core.common.ascode.AsProgramBlocks
import my.robots.core.data.MasterSlaveConfig
import my.robots.core.data.MasterSlaveOptions
import my.robots.core.data.ProjectOperations
import my.robots.core.data.ProjectOperations.MasterSlavePair
import my.robots.core.data.RobotRepository
import my.robots.core.data.RobotTask
import my.robots.core.data.TaskState
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
    /** Todos os robôs na ordem da cabine (linha, coluna) e depois os de fora. */
    val inCabinOrder: List<Robot> =
        robots.filter { it.id in cabin.placed }.sortedWith(compareBy({ cabin.placed[it.id]!!.row }, { cabin.placed[it.id]!!.col })) + outside
}

/**
 * Um projeto mestre e um escravo, com os pares de robôs entre eles e a variável de offset que
 * o escravo soma na base. É o que o desenho das setas mestre -> escravo mostra.
 */
data class ProjectPairView(
    val masterName: String,
    val master: CabinView,
    val slaveName: String,
    val slave: CabinView,
    val pairs: List<MasterSlavePair>,
    val offset: String
)

/**
 * Cérebro da tela de Projeto.
 *
 * - Observa os robôs do projeto, o layout e os equipamentos, e junta tudo em [view] (o que
 *   vem do banco passa por `LayoutOps.sanitize`).
 * - Acompanha conexão e heartbeat de cada robô, com um coletor por id ([watchedIds]), igual
 *   ao `ConnectedRobotsViewModel`.
 * - Edição: [startEdit] copia o layout para [draft]; as funções de edição mexem só nessa
 *   cópia; [save] grava tudo numa transação e [cancelEdit] descarta.
 * - Ações em grupo (backup de todos, comando para todos, mestre -> escravo) pelo
 *   ProjectOperations, com o andamento de cada robô em [tasks].
 * - Pares mestre/escravo: [pairs] e [pairViews] (para o desenho com setas).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectViewModel(
    private val repository: RobotRepository,
    private val terminalManager: KawasakiTerminalManager,
    private val operations: ProjectOperations,
    private val masterSlave: MasterSlaveOptions,
    val projectName: String
) : ViewModel() {

    /** Opções da transferência de cada projeto escravo (valem de início na janela). */
    val masterSlaveConfigs: StateFlow<Map<String, MasterSlaveConfig>> = masterSlave.all

    private val robots = repository.allRobots.map { all -> all.filter { it.project == projectName } }

    /** A cabine de um projeto (robôs, grade e equipamentos), já saneada. */
    private fun cabinOf(project: String): Flow<CabinView> = combine(
        repository.allRobots.map { all -> all.filter { it.project == project } },
        repository.getProjectLayout(project),
        repository.getProjectEquipment(project)
    ) { robots, layout, equipment ->
        val positions = robots.associate { r ->
            r.id to if (r.layoutRow != null && r.layoutCol != null) Cell(r.layoutRow!!, r.layoutCol!!) else null
        }
        val cabin = LayoutOps.sanitize(layout.rowCount, layout.colCount, positions, equipment.map { it.position })
        CabinView(cabin, equipment.mapIndexed { i, e -> e.copy(position = cabin.bandPositions[i]) }, robots)
    }

    /** Layout como está gravado, já saneado. */
    val view: StateFlow<CabinView?> = cabinOf(projectName)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Layout gravado deste projeto (traz o projeto mestre e o offset, se houver). */
    val layout: StateFlow<ProjectLayout?> = repository.getProjectLayout(projectName)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Todos os robôs do app (os pares mestre/escravo ligam projetos diferentes). */
    val allRobots: StateFlow<List<Robot>> = repository.allRobots
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Pares em que este projeto entra, como mestre ou como escravo. */
    val pairs: StateFlow<List<MasterSlavePair>> = repository.allRobots.map { all ->
        val byId = all.associateBy { it.id }
        all.mapNotNull { slave ->
            val master = slave.masterRobotId?.let { byId[it] } ?: return@mapNotNull null
            if (master.project == projectName || slave.project == projectName) MasterSlavePair(master, slave) else null
        }.sortedBy { it.master.name }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Um desenho por par de projetos (mestre -> escravo) em que este projeto entra. */
    val pairViews: StateFlow<List<ProjectPairView>> = pairs
        .map { list -> list.groupBy { it.master.project to it.slave.project } }
        .distinctUntilChanged()
        .flatMapLatest { groups ->
            if (groups.isEmpty()) flowOf(emptyList())
            else combine(groups.map { (projects, ps) ->
                val (m, s) = projects
                combine(cabinOf(m), cabinOf(s), repository.getProjectLayout(s)) { mv, sv, sl ->
                    ProjectPairView(m, mv, s, sv, ps, sl.baseOffset)
                }
            }) { it.toList() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    /** Linhas do terminal do robô (para os mini terminais). */
    fun history(robotId: Int): StateFlow<List<String>> = terminalManager.getHistory(robotId)

    // ---------- Ações em grupo ----------

    private val _tasks = MutableStateFlow<Map<Int, RobotTask>>(emptyMap())
    /** Andamento da última ação em grupo, por robô. */
    val tasks: StateFlow<Map<Int, RobotTask>> = _tasks.asStateFlow()

    private val _action = MutableStateFlow<String?>(null)
    /** Nome da ação em grupo em andamento (null = nenhuma). */
    val action: StateFlow<String?> = _action.asStateFlow()

    private val _lastAction = MutableStateFlow<String?>(null)
    /** Nome da última ação em grupo (continua depois que ela termina, junto com [tasks]). */
    val lastAction: StateFlow<String?> = _lastAction.asStateFlow()

    private var actionJob: Job? = null

    /**
     * Roda uma ação em grupo: marca os robôs como "na fila" e repassa o andamento de cada um
     * para [tasks]. Só uma ação por vez.
     */
    private fun runAction(title: String, robotIds: List<Int>, block: suspend ((Int, RobotTask) -> Unit) -> Unit) {
        if (actionJob?.isActive == true || robotIds.isEmpty()) return
        _action.value = title
        _lastAction.value = title
        _tasks.value = robotIds.associateWith { RobotTask(TaskState.WAITING, "Na fila") }
        actionJob = viewModelScope.launch {
            try {
                block { id, task -> _tasks.update { it + (id to task) } }
            } finally {
                _action.value = null
            }
        }
    }

    /** Para a ação em grupo; o que estava em andamento no robô (um SAVE, por exemplo) segue lá. */
    fun cancelAction() {
        actionJob?.cancel()
        _tasks.update { all -> all.mapValues { (_, t) -> if (t.finished) t else RobotTask(TaskState.FAILED, "Cancelado") } }
    }

    fun clearTasks() {
        if (actionJob?.isActive == true) return
        _tasks.value = emptyMap()
        _lastAction.value = null
    }

    /** Backup (SAVE/FULL) de cada robô escolhido. */
    fun backupAll(robots: List<Robot>) =
        runAction("Backup de todos", robots.map { it.id }) { operations.backupAll(robots, it) }

    /** O mesmo comando em cada robô escolhido. */
    fun commandAll(robots: List<Robot>, command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        runAction("Comando: $cmd", robots.map { it.id }) { operations.commandAll(robots, cmd, it) }
    }

    /**
     * Manda [programs] de cada mestre para o seu escravo, com o offset do projeto escravo
     * somado nas bases (só se [applyOffset]; sem ele, vão como estão no mestre) e, se
     * [withFrames], os frames das bases.
     */
    fun transfer(selected: List<MasterSlavePair>, programs: List<String>, withFrames: Boolean, applyOffset: Boolean) {
        // o padrão do frame vem da configuração Mestre / Escravo de cada projeto escravo
        if (programs.isEmpty()) return
        runAction("Mestre → escravo: ${programs.joinToString()}", selected.map { it.slave.id }) { update ->
            coroutineScope {
                selected.groupBy { it.slave.project }.map { (project, group) ->
                    async {
                        val offset = repository.getProjectLayout(project).first().baseOffset
                        val pattern = masterSlave.config(project).framePattern
                        operations.transferToSlaves(group, programs, withFrames, offset, applyOffset, pattern, update)
                    }
                }.awaitAll()
            }
        }
    }

    private val _programChoices = MutableStateFlow<List<String>?>(null)
    /** Programas do último backup dos mestres (null = carregando). */
    val programChoices: StateFlow<List<String>?> = _programChoices.asStateFlow()

    /** Lista os programas do último backup de cada mestre (sem repetir, na ordem do backup). */
    fun loadProgramChoices(masters: List<Robot>) {
        _programChoices.value = null
        viewModelScope.launch {
            val names = linkedSetOf<String>()
            masters.distinctBy { it.id }.forEach { m ->
                val latest = repository.getBackupsSummary(m.id).first()
                    .filter { !FileUtil.isTransferFile(it.fileName) && it.programsCount > 0 }
                    .maxByOrNull { it.timestamp }
                latest?.let { repository.getBackupById(it.id) }?.let { names += AsProgramBlocks.list(it.content) }
            }
            _programChoices.value = names.toList()
        }
    }

    private val _analysis = MutableStateFlow<TransferAnalysis?>(null)
    /** Análise da transferência escolhida (null = ainda não analisou ou analisando). */
    val analysis: StateFlow<TransferAnalysis?> = _analysis.asStateFlow()

    private val _analyzing = MutableStateFlow(false)
    val analyzing: StateFlow<Boolean> = _analyzing.asStateFlow()

    /** Último backup do robô (com programas, ou qualquer um) e o texto dele; null = não tem. */
    private suspend fun latestContent(robot: Robot, needPrograms: Boolean): Pair<Long, String>? {
        val latest = repository.getBackupsSummary(robot.id).first()
            .filter { !FileUtil.isTransferFile(it.fileName) && (!needPrograms || it.programsCount > 0) }
            .maxByOrNull { it.timestamp } ?: return null
        val content = repository.getBackupById(latest.id)?.content ?: return null
        return latest.timestamp to content
    }

    /**
     * Confere, antes de enviar, cada programa escolhido: no último backup da origem (existe?
     * linhas, data), a mudança nas linhas BASE e os frames da .TRANS que vão junto; e no último
     * backup do destino (já existe? será substituído; o frame já existe?). Lê um robô por vez
     * (os backups são grandes) e guarda só o resumo.
     */
    fun analyzeTransfer(pairs: List<MasterSlavePair>, programs: List<String>, applyOffset: Boolean, withFrames: Boolean) {
        _analysis.value = null
        _analyzing.value = true
        viewModelScope.launch {
            try {
                val slaveProject = pairs.firstOrNull()?.slave?.project.orEmpty()
                val offset = repository.getProjectLayout(slaveProject).first().baseOffset
                val pattern = masterSlave.config(slaveProject).framePattern.takeIf { it.isNotBlank() }
                val result = pairs.map { p ->
                    // origem: o último backup com programas (de onde eles saem); destino: o último
                    // backup qualquer (um robô vazio também diz que o programa não existe lá)
                    val origin = latestContent(p.master, needPrograms = true)
                    val target = latestContent(p.slave, needPrograms = false)
                    val checks = withContext(Dispatchers.Default) {
                        programs.map { name ->
                            GroupAnalysis.programCheck(name, origin?.second, target?.second, applyOffset, withFrames, offset, pattern)
                        }
                    }
                    PairAnalysis(p, origin?.first, target?.first, checks)
                }
                _analysis.value = TransferAnalysis(programs, result, applyOffset, withFrames, offset)
            } finally {
                _analyzing.value = false
            }
        }
    }

    fun clearAnalysis() { _analysis.value = null }

    // ---------- Duplicar programa em grupo ----------

    private val _dupAnalysis = MutableStateFlow<DuplicateAnalysis?>(null)
    /** Análise da duplicação escolhida (null = ainda não analisou). */
    val dupAnalysis: StateFlow<DuplicateAnalysis?> = _dupAnalysis.asStateFlow()

    /**
     * Confere em cada robô, pelo último backup, se o programa [source] existe (linhas, data) e
     * se o nome novo já existe (será substituído).
     */
    fun analyzeDuplicate(robots: List<Robot>, source: String, newName: String, comment: String?) {
        _dupAnalysis.value = null
        _analyzing.value = true
        viewModelScope.launch {
            try {
                val checks = robots.map { r ->
                    val latest = latestContent(r, needPrograms = false)
                    val content = latest?.second
                    val (src, dst) = withContext(Dispatchers.Default) {
                        val s = content?.let { AsProgramBlocks.extract(it, source) }?.let(GroupAnalysis::programState)
                        val d = content?.let { AsProgramBlocks.extract(it, newName) }?.let(GroupAnalysis::programState)
                        s to d
                    }
                    DupCheck(r, latest?.first, src, dst)
                }
                _dupAnalysis.value = DuplicateAnalysis(source, newName, comment, checks)
            } finally {
                _analyzing.value = false
            }
        }
    }

    fun clearDupAnalysis() { _dupAnalysis.value = null }

    /** Duplica [source] como [newName] (com [comment], se houver) em cada robô escolhido. */
    fun duplicate(robots: List<Robot>, source: String, newName: String, comment: String?) {
        if (robots.isEmpty()) return
        runAction("Duplicar $source → $newName", robots.map { it.id }) { update ->
            operations.duplicateInRobots(robots, source, newName, comment, update)
        }
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
    private val operations: ProjectOperations,
    private val masterSlave: MasterSlaveOptions,
    private val projectName: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ProjectViewModel::class.java)) {
            return ProjectViewModel(repository, terminalManager, operations, masterSlave, projectName) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
