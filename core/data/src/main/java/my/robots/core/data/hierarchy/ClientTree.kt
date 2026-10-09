package my.robots.core.data.hierarchy

import my.robots.core.model.Client
import my.robots.core.model.HeartbeatState
import my.robots.core.model.Manufacturer
import my.robots.core.model.ProductionLine
import my.robots.core.model.ProjectLayout
import my.robots.core.model.Robot

/** Uma estação (o projeto de até a v1.2): o layout dela e os robôs. */
data class StationNode(val layout: ProjectLayout, val robots: List<Robot>) {
    val name: String get() = layout.projectName
}

/** Uma linha de produção e as estações dela, na ordem do processo. */
data class LineNode(val line: ProductionLine, val stations: List<StationNode>) {
    val robots: List<Robot> get() = stations.flatMap { it.robots }
}

/** Um cliente e as linhas dele. */
data class ClientNode(val client: Client, val lines: List<LineNode>) {
    val robots: List<Robot> get() = lines.flatMap { it.robots }
}

/**
 * Ligação de reaproveitamento: a estação [slave] reaproveita os programas da [master] (o par
 * mestre/escravo de até a v1.2). [crossLine] = as duas estão em linhas diferentes.
 */
data class StationLink(val master: String, val slave: String, val crossLine: Boolean)

/** Para onde a tela inicial pula sozinha: um cliente só, ou um cliente com uma linha só. */
sealed interface Shortcut {
    data class OpenClient(val clientId: Long) : Shortcut
    data class OpenLine(val lineId: Long) : Shortcut
}

/** Filtro de status: estação com algum robô conectado, ou com algum desligado. */
enum class StatusFilter(val label: String) { CONNECTED("Com robô conectado"), DISCONNECTED("Com robô desligado") }

/**
 * Filtros da tela inicial. Vazio = sem filtro naquele item. [count] é o número no ícone.
 */
data class TreeFilter(
    val workTypes: Set<String> = emptySet(),
    val lineIds: Set<Long> = emptySet(),
    val status: StatusFilter? = null,
    val manufacturers: Set<Manufacturer> = emptySet()
) {
    val count: Int get() = workTypes.size + lineIds.size + (if (status != null) 1 else 0) + manufacturers.size
    val isEmpty: Boolean get() = count == 0
}

/**
 * Regras da tela inicial (Clientes → Linha → Estação), sem Android, para testar na JVM.
 */
object ClientTree {

    /**
     * Monta a árvore. Sem [showHidden], clientes, linhas e estações ocultos ficam de fora.
     * Ordem: clientes pelo último usado (e nome); linhas e estações pela ordem gravada (e nome).
     * Estação sem linha conhecida não aparece (o app a põe numa linha ao abrir).
     */
    fun build(
        clients: List<Client>,
        lines: List<ProductionLine>,
        stations: List<ProjectLayout>,
        robots: List<Robot>,
        showHidden: Boolean = false
    ): List<ClientNode> {
        val robotsByProject = robots.groupBy { it.project }
        val stationsByLine = stations
            .filter { showHidden || !it.hidden }
            .groupBy { it.lineId }
        val linesByClient = lines.filter { showHidden || !it.hidden }.groupBy { it.clientId }
        return clients
            .filter { showHidden || !it.hidden }
            .sortedWith(byLastUsed())
            .map { c ->
                ClientNode(
                    c,
                    linesByClient[c.id].orEmpty()
                        .sortedWith(compareBy<ProductionLine> { it.sortOrder }.thenBy { it.name.lowercase() })
                        .map { l ->
                            LineNode(
                                l,
                                stationsByLine[l.id].orEmpty()
                                    .sortedWith(compareBy<ProjectLayout> { it.sortOrder }.thenBy { it.projectName.lowercase() })
                                    .map { s -> StationNode(s, robotsByProject[s.projectName].orEmpty().sortedBy { it.name.lowercase() }) }
                            )
                        }
                )
            }
    }

    /** Último usado primeiro; empate (nunca usado) pelo nome. */
    fun byLastUsed(): Comparator<Client> = compareByDescending<Client> { it.lastUsedAt }.thenBy { it.name.lowercase() }

    /**
     * Atalho da tela inicial: com um cliente visível só, abrir direto nele; se ele tiver uma
     * linha só, abrir direto nas estações dela. Com mais de um cliente, nenhum atalho.
     */
    fun shortcut(visible: List<ClientNode>): Shortcut? {
        val c = visible.singleOrNull() ?: return null
        val l = c.lines.singleOrNull()
        return if (l != null) Shortcut.OpenLine(l.line.id) else Shortcut.OpenClient(c.client.id)
    }

    /**
     * Aplica o filtro: uma estação passa se bate o tipo de trabalho, a marca (algum robô) e o
     * status (algum robô conectado / desligado). Uma linha passa se está na lista de linhas (ou
     * a lista é vazia) e tem pelo menos uma estação que passa; um cliente, se tem pelo menos
     * uma linha que passa. Dentro de cada um ficam só os que passam.
     */
    fun filter(tree: List<ClientNode>, f: TreeFilter, state: (Int) -> HeartbeatState): List<ClientNode> {
        if (f.isEmpty) return tree
        fun stationOk(s: StationNode): Boolean {
            if (f.workTypes.isNotEmpty() && s.layout.workType !in f.workTypes) return false
            if (f.manufacturers.isNotEmpty() && s.robots.none { it.manufacturer in f.manufacturers }) return false
            return when (f.status) {
                null -> true
                StatusFilter.CONNECTED -> s.robots.any { state(it.id) != HeartbeatState.DISCONNECTED }
                StatusFilter.DISCONNECTED -> s.robots.any { state(it.id) == HeartbeatState.DISCONNECTED }
            }
        }
        return tree.mapNotNull { c ->
            val lines = c.lines
                .filter { f.lineIds.isEmpty() || it.line.id in f.lineIds }
                .map { it.copy(stations = it.stations.filter(::stationOk)) }
                .filter { it.stations.isNotEmpty() }
            if (lines.isEmpty()) null else c.copy(lines = lines)
        }
    }

    /** Ligações de reaproveitamento entre as estações (os pares mestre/escravo de projeto). */
    fun links(stations: List<ProjectLayout>): List<StationLink> {
        val lineOf = stations.associate { it.projectName to it.lineId }
        return stations.mapNotNull { s ->
            val m = s.masterProject ?: return@mapNotNull null
            StationLink(m, s.projectName, crossLine = isCrossLine(lineOf[m], lineOf[s.projectName]))
        }
    }

    /** Duas estações em linhas diferentes (linha desconhecida conta como diferente só se a outra é conhecida). */
    fun isCrossLine(a: Long?, b: Long?): Boolean = a != null && b != null && a != b

    /** As estações [a] e [b] estão em linhas diferentes? */
    fun isCrossLine(a: String, b: String, stations: List<ProjectLayout>): Boolean {
        val lineOf = stations.associate { it.projectName to it.lineId }
        return isCrossLine(lineOf[a], lineOf[b])
    }

    /** Tipo de trabalho da linha: o mais comum entre as estações (empate: o primeiro na ordem). */
    fun lineWorkType(line: LineNode): String? =
        line.stations.mapNotNull { it.layout.workType }
            .groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key

    /** O pior estado entre os robôs: Desligado > Sem sinal > Conectado. Sem robôs = Desligado. */
    fun worstState(robots: List<Robot>, state: (Int) -> HeartbeatState): HeartbeatState {
        val states = robots.map { state(it.id) }
        return when {
            states.isEmpty() || HeartbeatState.DISCONNECTED in states -> HeartbeatState.DISCONNECTED
            HeartbeatState.STALE in states -> HeartbeatState.STALE
            else -> HeartbeatState.ALIVE
        }
    }

    /** Quantos robôs estão conectados (qualquer estado menos Desligado). */
    fun connectedCount(robots: List<Robot>, state: (Int) -> HeartbeatState): Int =
        robots.count { state(it.id) != HeartbeatState.DISCONNECTED }

    /** Nova ordem de uma linha depois de mover [name] uma posição ([delta] = -1 sobe, +1 desce). */
    fun moved(names: List<String>, name: String, delta: Int): List<String> {
        val i = names.indexOf(name)
        val j = i + delta
        if (i < 0 || j !in names.indices) return names
        return names.toMutableList().apply { add(j, removeAt(i)) }
    }
}
