package my.robots.feature.clients

import my.robots.core.model.Manufacturer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import my.robots.core.data.RobotRepository
import my.robots.core.data.hierarchy.ClientNode
import my.robots.core.data.hierarchy.ClientTree
import my.robots.core.data.hierarchy.StationLink
import my.robots.core.data.hierarchy.TreeFilter
import my.robots.core.model.Client
import my.robots.core.model.HeartbeatState
import my.robots.core.model.ProductionLine
import my.robots.core.model.ProjectLayout
import my.robots.core.model.Robot
import my.robots.core.model.WorkType
import my.robots.core.network.KawasakiTerminalManager

/**
 * O que as telas Clientes, Cliente e Linha mostram.
 * - visible: a árvore sem os ocultos; withHidden: com eles (para "Mostrar ocultos");
 * - heartbeats: o pulso de cada robô (verde Conectado, amarelo Sem sinal, cinza Desligado);
 * - links: as ligações de reaproveitamento (pares mestre/escravo de estação).
 */
data class ClientsUi(
    val loaded: Boolean = false,
    val visible: List<ClientNode> = emptyList(),
    val withHidden: List<ClientNode> = emptyList(),
    val clients: List<Client> = emptyList(),
    val lines: List<ProductionLine> = emptyList(),
    val stations: List<ProjectLayout> = emptyList(),
    val robots: List<Robot> = emptyList(),
    val workTypes: List<WorkType> = emptyList(),
    val links: List<StationLink> = emptyList(),
    val heartbeats: Map<Int, HeartbeatState> = emptyMap()
) {
    fun state(robotId: Int): HeartbeatState = heartbeats[robotId] ?: HeartbeatState.DISCONNECTED
    fun line(id: Long): ProductionLine? = lines.firstOrNull { it.id == id }
    fun client(id: Long): Client? = clients.firstOrNull { it.id == id }
}

/**
 * Cérebro das telas Clientes → Cliente → Linha. Os dados vêm do RobotRepository (banco v8) e o
 * pulso de cada robô do KawasakiTerminalManager (o mesmo LED do resto do app). As regras
 * (ordem, atalhos, filtro, linhas diferentes) ficam em ClientTree (:core:data), testadas na JVM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClientsViewModel(
    private val repository: RobotRepository,
    private val terminal: KawasakiTerminalManager
) : ViewModel() {

    private val heartbeats = repository.allRobots
        .map { robots -> robots.map { it.id } }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyMap())
            else combine(ids.map { id -> terminal.getHeartbeat(id).map { id to it } }) { it.toMap() }
        }

    private val data = combine(
        repository.clients, repository.lines, repository.stations, repository.allRobots, repository.workTypes
    ) { clients, lines, stations, robots, types -> Data(clients, lines, stations, robots, types) }

    private data class Data(
        val clients: List<Client>,
        val lines: List<ProductionLine>,
        val stations: List<ProjectLayout>,
        val robots: List<Robot>,
        val types: List<WorkType>
    )

    val ui: StateFlow<ClientsUi> = combine(data, heartbeats) { d, hb ->
        ClientsUi(
            loaded = true,
            visible = ClientTree.build(d.clients, d.lines, d.stations, d.robots, showHidden = false),
            withHidden = ClientTree.build(d.clients, d.lines, d.stations, d.robots, showHidden = true),
            clients = d.clients,
            lines = d.lines,
            stations = d.stations,
            robots = d.robots,
            workTypes = d.types,
            links = ClientTree.links(d.stations),
            heartbeats = hb
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ClientsUi())

    private val _filter = MutableStateFlow(TreeFilter())
    /** Filtros da tela inicial (ficam enquanto o app está aberto). */
    val filter: StateFlow<TreeFilter> = _filter.asStateFlow()
    fun setFilter(f: TreeFilter) { _filter.value = f }

    private val _showHidden = MutableStateFlow(false)
    val showHidden: StateFlow<Boolean> = _showHidden.asStateFlow()
    fun toggleShowHidden() { _showHidden.value = !_showHidden.value }

    init {
        // um projeto novo (robô cadastrado na lista antiga) já aparece numa linha
        viewModelScope.launch { repository.ensureStations() }
    }

    private fun run(block: suspend () -> Unit) { viewModelScope.launch { block() } }

    fun createClient(name: String) = run { if (name.isNotBlank()) repository.createClient(name) }
    fun createLine(clientId: Long, name: String) = run { if (name.isNotBlank()) repository.createLine(clientId, name) }
    fun renameClient(id: Long, name: String) = run { if (name.isNotBlank()) repository.renameClient(id, name) }
    fun renameLine(id: Long, name: String) = run { if (name.isNotBlank()) repository.renameLine(id, name) }
    fun setClientHidden(id: Long, hidden: Boolean) = run { repository.setClientHidden(id, hidden) }
    fun setLineHidden(id: Long, hidden: Boolean) = run { repository.setLineHidden(id, hidden) }
    fun setStationHidden(name: String, hidden: Boolean) = run { repository.setStationHidden(name, hidden) }
    fun setWorkType(station: String, type: String?) = run { repository.setStationWorkType(station, type) }
    fun addWorkTypeAndSet(station: String, type: String) = run {
        if (type.isNotBlank()) {
            repository.addWorkType(type)
            repository.setStationWorkType(station, type.trim())
        }
    }
    fun moveStation(station: String, toLineId: Long) = run { repository.moveStation(station, toLineId) }

    /** Renomeia a estação (o projeto: robôs, layout, equipamentos e quem o tem como mestre). */
    fun renameStation(oldName: String, newName: String) = run {
        if (newName.isNotBlank() && newName.trim() != oldName) repository.renameProject(oldName, newName.trim())
    }

    /** Sobe ([delta] = -1) ou desce (+1) a estação na ordem do processo da linha. */
    fun moveInLine(lineId: Long, station: String, delta: Int) = run {
        val names = ui.value.stations
            .filter { it.lineId == lineId }
            .sortedWith(compareBy<ProjectLayout> { it.sortOrder }.thenBy { it.projectName.lowercase() })
            .map { it.projectName }
        val moved = ClientTree.moved(names, station, delta)
        if (moved != names) repository.reorderLine(moved)
    }

    private val _connecting = MutableStateFlow<Set<Int>>(emptySet())
    /** Robôs com a conexão pedida há pouco e ainda sem resposta ("Conectando…"). */
    val connecting: StateFlow<Set<Int>> = _connecting.asStateFlow()

    /** Conecta os robôs desligados da lista (uma conexão cada, como no resto do app). */
    fun connect(robots: List<Robot>) {
        val off = robots.filter { ui.value.state(it.id) == HeartbeatState.DISCONNECTED }
        if (off.isEmpty()) return
        _connecting.value = _connecting.value + off.map { it.id }
        off.forEach { terminal.connect(it) }
        // o pedido some quando o robô responde ou depois de 10 s (não conectou: volta a "Conectar")
        viewModelScope.launch {
            kotlinx.coroutines.delay(10_000)
            _connecting.value = _connecting.value - off.map { it.id }.toSet()
        }
    }

    /** Desconecta os robôs conectados da lista. */
    fun disconnect(robots: List<Robot>) {
        robots.filter { ui.value.state(it.id) != HeartbeatState.DISCONNECTED }
            .forEach { terminal.disconnect(it.id, clearHistory = false) }
        _connecting.value = _connecting.value - robots.map { it.id }.toSet()
    }

    /**
     * Cadastra um robô (a mesma janela da lista de robôs). O projeto é a estação: um nome que já
     * existe põe o robô nela (fora do layout, para posicionar na tela de Projeto); um nome novo
     * cria a estação na linha usada por último (a que está aberta).
     */
    fun addRobot(
        name: String, ip: String, port: Int, project: String, manufacturer: Manufacturer,
        autoLogin: Boolean, loginUser: String, loginPassword: String
    ) = run {
        repository.insertRobot(
            Robot(
                name = name, ip = ip, port = port, project = project, manufacturer = manufacturer,
                autoLogin = autoLogin, loginUser = loginUser, loginPassword = loginPassword
            )
        )
    }

    /** Nomes das estações, para a sugestão de "Projeto / estação" no cadastro do robô. */
    fun stationNames(): List<String> = ui.value.stations.map { it.projectName }.sortedBy { it.lowercase() }

    fun touchClient(id: Long) = run { repository.touchClient(id) }
    fun touchLine(line: ProductionLine) = run { repository.touchLine(line) }
}

class ClientsViewModelFactory(
    private val repository: RobotRepository,
    private val terminal: KawasakiTerminalManager
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ClientsViewModel(repository, terminal) as T
}
