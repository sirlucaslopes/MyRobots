package my.robots.feature.clients

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
 * Uma linha: as estações na ordem do processo. Entre duas estações aparece "próxima estação" ou
 * a ligação ("Top Coat reaproveita os programas do Primer") com Transferir. As ligações com
 * outra linha ficam numa seção com borda tracejada amarela. O ⋮ de cada estação muda o tipo de
 * trabalho, a linha, a ordem, oculta e renomeia. Tocar na estação abre a tela de Projeto.
 */
@Composable
fun LineScreen(
    viewModel: ClientsViewModel,
    lineId: Long,
    onBack: () -> Unit,
    onOpenStation: (String) -> Unit,
    onTransfer: (master: String, slave: String) -> Unit
) {
    val ui by viewModel.ui.collectAsState()
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
                        first = i == 0,
                        last = i == stations.lastIndex,
                        onClick = { onOpenStation(s.name) },
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
                        Text("Outras ligações nesta linha", style = MaterialTheme.typography.labelLarge, color = LinkBlue)
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
                        Text("Ligação com outra linha", style = MaterialTheme.typography.labelLarge, color = CrossLineYellow)
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
                                station = s, ui = ui, first = true, last = true,
                                onClick = { onOpenStation(s.name) },
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

/** Cartão de uma estação: LED do pior estado, nome, tipo, "N robôs · X conectados", mini grade e ⋮. */
@Composable
private fun StationCard(
    station: StationNode,
    ui: ClientsUi,
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit,
    onType: () -> Unit,
    onMove: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onToggleHidden: () -> Unit,
    onRename: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val hidden = station.layout.hidden
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().alpha(if (hidden) 0.55f else 1f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StationMiniGrid(station, ui, cell = 14.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Led(ClientTree.worstState(station.robots, ui::state))
                    Text(station.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "${station.robots.size} ${if (station.robots.size == 1) "robô" else "robôs"} · " +
                        "${ClientTree.connectedCount(station.robots, ui::state)} conectados",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    station.layout.workType?.let { Tag(it) }
                    if (hidden) Tag("oculta")
                }
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
    }
}

/** Entre duas estações: "próxima estação", ou a ligação de reaproveitamento com Transferir. */
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

/** Uma ligação: "⇄ Top Coat reaproveita os programas do Primer" (+ a outra linha) e Transferir. */
@Composable
private fun LinkRow(link: StationLink, otherLine: String?, color: androidx.compose.ui.graphics.Color, onTransfer: (String, String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("⇄", color = color, fontWeight = FontWeight.Bold)
        Column(Modifier.weight(1f)) {
            Text(linkText(link), style = MaterialTheme.typography.bodySmall)
            otherLine?.let { Text("outra linha: $it", style = MaterialTheme.typography.labelSmall, color = color) }
        }
        FilledTonalButton(onClick = { onTransfer(link.master, link.slave) }) {
            Icon(Icons.AutoMirrored.Rounded.Send, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Transferir")
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
