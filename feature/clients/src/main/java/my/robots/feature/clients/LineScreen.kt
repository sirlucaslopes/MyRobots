package my.robots.feature.clients

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.ui.text.style.TextOverflow
import my.robots.core.designsystem.ActionTone
import my.robots.core.designsystem.BarAction
import my.robots.core.designsystem.label
import my.robots.core.model.HeartbeatState
import my.robots.core.model.Robot
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import my.robots.core.data.hierarchy.ClientTree
import my.robots.core.data.hierarchy.StationLink
import my.robots.core.data.hierarchy.StationNode
import my.robots.core.designsystem.AppTopBar
import my.robots.core.model.ProductionLine
import my.robots.core.model.WorkType

/**
 * Uma linha: as estações na ordem do processo, cada uma com a cabine (um quadrado por robô:
 * tocar abre o painel do robô) e o botão Conectar. Entre duas estações aparece "próxima estação"
 * ou "Primer CAT → Top Coat CAT" com Enviar programas. Os envios para outra linha ficam numa
 * seção com borda tracejada amarela. Conectar todos / Desconectar na barra. O ⋮ de cada estação
 * muda o tipo de trabalho, a linha, a ordem, oculta e renomeia. Tocar no cartão abre a tela de
 * Projeto.
 */
@Composable
fun LineScreen(
    viewModel: ClientsViewModel,
    lineId: Long,
    onBack: () -> Unit,
    onOpenStation: (String) -> Unit,
    onOpenRobot: (Robot) -> Unit,
    onTransfer: (master: String, slave: String) -> Unit
) {
    val ui by viewModel.ui.collectAsState()
    val connecting by viewModel.connecting.collectAsState()
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var renamingLine by remember { mutableStateOf(false) }
    var renamingStation by remember { mutableStateOf<String?>(null) }
    var typeFor by remember { mutableStateOf<StationNode?>(null) }
    var newTypeFor by remember { mutableStateOf<String?>(null) }
    var moveFor by remember { mutableStateOf<StationNode?>(null) }

    val line = ui.line(lineId)
    LaunchedEffect(line?.id) { line?.let { viewModel.touchLine(it) } }

    val node = ui.withHidden.flatMap { it.lines }.firstOrNull { it.line.id == lineId }
    val stations = node?.stations.orEmpty().filter { !it.layout.hidden }
    val hidden = node?.stations.orEmpty().filter { it.layout.hidden }
    val names = node?.stations.orEmpty().map { it.name }.toSet()
    val robots = stations.flatMap { it.robots }
    val client = line?.let { ui.client(it.clientId) }
    // ligações dentro da linha que não são entre vizinhas, e as com outra linha
    val adjacent = stations.zipWithNext().map { (a, b) -> setOf(a.name, b.name) }
    val innerLinks = ui.links.filter { !it.crossLine && it.master in names && it.slave in names && setOf(it.master, it.slave) !in adjacent }
    val crossLinks = ui.links.filter { it.crossLine && (it.master in names || it.slave in names) }
    val lineOf = ui.stations.associate { it.projectName to it.lineId }

    Scaffold(
        topBar = {
            AppTopBar(
                title = line?.name ?: "Linha",
                subtitle = listOfNotNull(
                    client?.name,
                    "${stations.size} ${if (stations.size == 1) "estação" else "estações"}",
                    connectedText(robots, ui)
                ).joinToString(" · "),
                onBack = onBack,
                actions = listOf(
                    BarAction(
                        Icons.Rounded.Link, "Conectar todos",
                        tone = ActionTone.Primary,
                        enabled = robots.any { ui.state(it.id) == HeartbeatState.DISCONNECTED },
                        onClick = { viewModel.connect(robots) }
                    ),
                    BarAction(
                        Icons.Rounded.LinkOff, "Desconectar",
                        enabled = robots.any { ui.state(it.id) != HeartbeatState.DISCONNECTED },
                        onClick = { viewModel.disconnect(robots) }
                    )
                ),
                menu = { close ->
                    DropdownMenuItem(text = { Text("Renomear linha") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { close(); renamingLine = true })
                    line?.let { l ->
                        DropdownMenuItem(
                            text = { Text(if (l.hidden) "Mostrar linha" else "Ocultar linha") },
                            leadingIcon = { Icon(if (l.hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, null) },
                            onClick = { close(); viewModel.setLineHidden(l.id, !l.hidden) }
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (ui.loaded && stations.isEmpty()) {
                item {
                    Text(
                        "Nenhuma estação nesta linha. Mova uma estação para cá pelo ⋮ dela, em outra linha.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
            stations.forEachIndexed { i, s ->
                item(key = "s_${s.name}") {
                    StationCard(
                        station = s,
                        ui = ui,
                        connecting = connecting,
                        first = i == 0,
                        last = i == stations.lastIndex,
                        onClick = { onOpenStation(s.name) },
                        onOpenRobot = onOpenRobot,
                        onConnect = { viewModel.connect(s.robots) },
                        onDisconnect = { viewModel.disconnect(s.robots) },
                        onType = { typeFor = s },
                        onMove = { moveFor = s },
                        onUp = { viewModel.moveInLine(lineId, s.name, -1) },
                        onDown = { viewModel.moveInLine(lineId, s.name, 1) },
                        onToggleHidden = { viewModel.setStationHidden(s.name, !s.layout.hidden) },
                        onRename = { renamingStation = s.name }
                    )
                }
                if (i < stations.lastIndex) {
                    val next = stations[i + 1]
                    val link = ui.links.firstOrNull { !it.crossLine && setOf(it.master, it.slave) == setOf(s.name, next.name) }
                    item(key = "c_${s.name}") { Connector(link, onTransfer) }
                }
            }
            if (innerLinks.isNotEmpty()) {
                item(key = "inner") {
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Outros envios de programas nesta linha", style = MaterialTheme.typography.labelLarge, color = LinkBlue)
                        innerLinks.forEach { LinkRow(it, null, LinkBlue, onTransfer) }
                    }
                }
            }
            if (crossLinks.isNotEmpty()) {
                item(key = "cross") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .dashedBorder()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Enviar programas para outra linha", style = MaterialTheme.typography.labelLarge, color = CrossLineYellow)
                        crossLinks.forEach { l ->
                            val other = if (l.master in names) l.slave else l.master
                            val otherLine = lineOf[other]?.let { ui.line(it) }
                            val otherClient = otherLine?.let { ui.client(it.clientId) }
                            LinkRow(l, listOfNotNull(otherClient?.name, otherLine?.name).joinToString(" · ").ifBlank { null }, CrossLineYellow, onTransfer)
                        }
                    }
                }
            }
            if (hidden.isNotEmpty()) {
                item(key = "toggle") {
                    TextButton(onClick = { showHidden = !showHidden }, modifier = Modifier.fillMaxWidth()) {
                        Icon(if (showHidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (showHidden) "Esconder ocultas" else "Mostrar ocultas (${hidden.size})")
                    }
                }
                if (showHidden) {
                    hidden.forEach { s ->
                        item(key = "h_${s.name}") {
                            StationCard(
                                station = s, ui = ui, connecting = connecting, first = true, last = true,
                                onClick = { onOpenStation(s.name) },
                                onOpenRobot = onOpenRobot,
                                onConnect = { viewModel.connect(s.robots) },
                                onDisconnect = { viewModel.disconnect(s.robots) },
                                onType = { typeFor = s }, onMove = { moveFor = s }, onUp = {}, onDown = {},
                                onToggleHidden = { viewModel.setStationHidden(s.name, false) },
                                onRename = { renamingStation = s.name }
                            )
                        }
                    }
                }
            }
        }
    }

    if (renamingLine && line != null) {
        NameDialog("Renomear linha", "Nome da linha", initial = line.name,
            onConfirm = { viewModel.renameLine(line.id, it); renamingLine = false }, onDismiss = { renamingLine = false })
    }
    renamingStation?.let { name ->
        NameDialog("Renomear estação", "Nome da estação", initial = name,
            onConfirm = { viewModel.renameStation(name, it); renamingStation = null }, onDismiss = { renamingStation = null })
    }
    typeFor?.let { s ->
        val none = WorkType("(sem tipo)", -1)
        ChoiceDialog(
            title = "Tipo de trabalho · ${s.name}",
            options = listOf(none) + ui.workTypes,
            text = { it.name },
            selected = { it.name == (s.layout.workType ?: none.name) },
            extra = {
                HorizontalDivider()
                TextButton(onClick = { typeFor = null; newTypeFor = s.name }) { Text("Novo tipo…") }
            },
            onPick = { viewModel.setWorkType(s.name, if (it === none) null else it.name); typeFor = null },
            onDismiss = { typeFor = null }
        )
    }
    newTypeFor?.let { name ->
        NameDialog("Novo tipo de trabalho", "Nome do tipo", confirm = "Criar",
            onConfirm = { viewModel.addWorkTypeAndSet(name, it); newTypeFor = null }, onDismiss = { newTypeFor = null })
    }
    moveFor?.let { s ->
        val options: List<ProductionLine> = ui.lines.filter { it.id != lineId }
            .sortedWith(compareBy<ProductionLine>({ ui.client(it.clientId)?.name?.lowercase() }, { it.sortOrder }, { it.name.lowercase() }))
        ChoiceDialog(
            title = "Mover ${s.name} para",
            options = options,
            text = { l -> listOfNotNull(ui.client(l.clientId)?.name, l.name).joinToString(" · ") + if (l.hidden) " (oculta)" else "" },
            extra = if (options.isEmpty()) ({
                Text("Não há outra linha. Crie uma pelo + da tela inicial ou na tela do cliente.", style = MaterialTheme.typography.bodySmall)
            }) else null,
            onPick = { viewModel.moveStation(s.name, it.id); moveFor = null },
            onDismiss = { moveFor = null }
        )
    }
}

/**
 * Cartão de uma estação: LED do pior estado, nome, "N robôs · X conectados", tipo, ⋮ e a cabine
 * com um quadrado por robô (nome e cor do status). **Tocar num robô abre o painel dele**; tocar
 * no resto do cartão (ou em "Abrir estação") abre a tela de Projeto. "Conectar" liga os robôs
 * desligados da estação; com todos conectados, vira "Desconectar".
 */
@Composable
private fun StationCard(
    station: StationNode,
    ui: ClientsUi,
    connecting: Set<Int>,
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit,
    onOpenRobot: (Robot) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onType: () -> Unit,
    onMove: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onToggleHidden: () -> Unit,
    onRename: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val hidden = station.layout.hidden
    val robots = station.robots
    val connected = ClientTree.connectedCount(robots, ui::state)
    val pending = robots.any { it.id in connecting && ui.state(it.id) == HeartbeatState.DISCONNECTED }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().alpha(if (hidden) 0.55f else 1f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Led(ClientTree.worstState(robots, ui::state))
                        Text(station.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        station.layout.workType?.let { Tag(it) }
                        if (hidden) Tag("oculta")
                    }
                    Text(
                        "${robots.size} ${if (robots.size == 1) "robô" else "robôs"} · $connected conectados",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Mais opções da estação") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Tipo de trabalho") }, leadingIcon = { Icon(Icons.Rounded.Category, null) }, onClick = { menu = false; onType() })
                        DropdownMenuItem(text = { Text("Mover para outra linha") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.DriveFileMove, null) }, onClick = { menu = false; onMove() })
                        if (!hidden) {
                            DropdownMenuItem(text = { Text("Subir") }, enabled = !first, leadingIcon = { Icon(Icons.Rounded.ArrowUpward, null) }, onClick = { menu = false; onUp() })
                            DropdownMenuItem(text = { Text("Descer") }, enabled = !last, leadingIcon = { Icon(Icons.Rounded.ArrowDownward, null) }, onClick = { menu = false; onDown() })
                        }
                        DropdownMenuItem(
                            text = { Text(if (hidden) "Mostrar" else "Ocultar") },
                            leadingIcon = { Icon(if (hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, null) },
                            onClick = { menu = false; onToggleHidden() }
                        )
                        DropdownMenuItem(text = { Text("Renomear") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onRename() })
                    }
                }
            }
            StationRobotGrid(station, ui, connecting, onOpenRobot)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    robots.isEmpty() -> {}
                    pending -> OutlinedButton(onClick = {}, enabled = false) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Conectando…")
                    }
                    connected < robots.size -> Button(onClick = onConnect) {
                        Icon(Icons.Rounded.Link, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (connected == 0) "Conectar" else "Conectar (${robots.size - connected})")
                    }
                    else -> OutlinedButton(onClick = onDisconnect) {
                        Icon(Icons.Rounded.LinkOff, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Desconectar")
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClick) { Text("Abrir estação") }
            }
        }
    }
}

/**
 * A cabine da estação em tamanho de tocar: a grade de vagas da estação, cada robô num quadrado
 * com o nome e a cor do status (o LED); vaga vazia fica só com o contorno. Os robôs fora do layout
 * vêm numa linha a mais. Tocar num robô abre o painel dele.
 */
@Composable
private fun StationRobotGrid(station: StationNode, ui: ClientsUi, connecting: Set<Int>, onOpenRobot: (Robot) -> Unit) {
    val rows = station.layout.rowCount.coerceAtLeast(1)
    val cols = station.layout.colCount.coerceAtLeast(1)
    val placed = station.robots
        .filter { r -> r.layoutRow != null && r.layoutCol != null && r.layoutRow!! in 0 until rows && r.layoutCol!! in 0 until cols }
        .associateBy { it.layoutRow!! to it.layoutCol!! }
    val outside = station.robots.filter { it !in placed.values }
    // linhas da grade sem nenhum robô não aparecem (como na tela de Projeto)
    val gridRows = (0 until rows).map { r -> (0 until cols).map { c -> placed[r to c] } }.filter { row -> row.any { it != null } }
    val outsideRows = outside.chunked(cols).map { chunk -> chunk + List(cols - chunk.size) { null } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (gridRows + outsideRows).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { robot ->
                    RobotTile(robot, robot?.let { ui.state(it.id) }, robot != null && robot.id in connecting, Modifier.weight(1f), onOpenRobot)
                }
            }
        }
    }
}

@Composable
private fun RobotTile(robot: Robot?, state: HeartbeatState?, pending: Boolean, modifier: Modifier, onOpenRobot: (Robot) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    if (robot == null || state == null) {
        Box(modifier.height(48.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape))
        return
    }
    val color = stateColor(state)
    Surface(
        onClick = { onOpenRobot(robot) },
        shape = shape,
        color = color.copy(alpha = 0.22f),
        border = BorderStroke(1.5.dp, color),
        modifier = modifier.height(48.dp)
    ) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Led(state, 8.dp)
            Column(Modifier.weight(1f)) {
                Text(robot.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (pending && state == HeartbeatState.DISCONNECTED) "Conectando…" else state.label(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

/** Entre duas estações: "próxima estação", ou o envio de programas de uma para a outra. */
@Composable
private fun Connector(link: StationLink?, onTransfer: (String, String) -> Unit) {
    if (link == null) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Rounded.ArrowDownward, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("próxima estação", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LinkRow(link, null, LinkBlue, onTransfer)
    }
}

/** Envio de programas entre estações: "Primer CAT → Top Coat CAT" (+ a outra linha) e o botão. */
@Composable
private fun LinkRow(link: StationLink, otherLine: String?, color: androidx.compose.ui.graphics.Color, onTransfer: (String, String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("⇄", color = color, fontWeight = FontWeight.Bold)
        Column(Modifier.weight(1f)) {
            Text(linkText(link), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            otherLine?.let { Text("outra linha: $it", style = MaterialTheme.typography.labelSmall, color = color) }
        }
        FilledTonalButton(onClick = { onTransfer(link.master, link.slave) }) {
            Icon(Icons.AutoMirrored.Rounded.Send, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Enviar programas")
        }
    }
}

/** Borda tracejada amarela (seção "Ligação com outra linha"). */
private fun Modifier.dashedBorder(): Modifier = drawBehind {
    drawRoundRect(
        color = CrossLineYellow,
        style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
        cornerRadius = CornerRadius(12.dp.toPx())
    )
}
