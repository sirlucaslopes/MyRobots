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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import my.robots.core.data.hierarchy.ClientTree
import my.robots.core.data.hierarchy.LineNode
import my.robots.core.designsystem.AppTopBar
import my.robots.core.designsystem.BarAction
import my.robots.core.model.ProductionLine

/**
 * As linhas (cabines) de um cliente: um cartão por linha com o tipo de trabalho, o resumo e a
 * mini planta. Tocar abre a linha; as linhas ocultas ficam no fim, em "Mostrar ocultas".
 */
@Composable
fun ClientScreen(
    viewModel: ClientsViewModel,
    clientId: Long,
    onBack: () -> Unit,
    onOpenLine: (Long) -> Unit,
    onOpenStation: (String) -> Unit
) {
    val ui by viewModel.ui.collectAsState()
    var showHiddenLines by rememberSaveable { mutableStateOf(false) }
    var renamingClient by remember { mutableStateOf(false) }
    var renamingLine by remember { mutableStateOf<ProductionLine?>(null) }
    var newLine by remember { mutableStateOf(false) }

    LaunchedEffect(clientId) { viewModel.touchClient(clientId) }

    // com os ocultos (um cliente oculto também abre), mas sem as estações ocultas dentro das linhas
    val node = ui.withHidden.firstOrNull { it.client.id == clientId }?.withoutHiddenStations()
    val visibleLines = node?.lines.orEmpty().filter { !it.line.hidden }
    val hiddenLines = node?.lines.orEmpty().filter { it.line.hidden }
    val robots = visibleLines.flatMap { it.robots }

    Scaffold(
        topBar = {
            AppTopBar(
                title = node?.client?.name ?: "Cliente",
                subtitle = "${visibleLines.size} ${if (visibleLines.size == 1) "linha" else "linhas"} · ${connectedText(robots, ui)}",
                onBack = onBack,
                menu = { close ->
                    DropdownMenuItem(text = { Text("Renomear cliente") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { close(); renamingClient = true })
                    node?.client?.let { c ->
                        DropdownMenuItem(
                            text = { Text(if (c.hidden) "Mostrar cliente" else "Ocultar cliente") },
                            leadingIcon = { Icon(if (c.hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, null) },
                            onClick = { close(); viewModel.setClientHidden(c.id, !c.hidden) }
                        )
                    }
                },
                actions = listOf(BarAction(Icons.Rounded.Add, "Nova linha", onClick = { newLine = true }))
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (ui.loaded && visibleLines.isEmpty()) {
                item { Text("Nenhuma linha. Toque em Nova linha.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
            }
            items(visibleLines, key = { it.line.id }) { l ->
                LineCard(l, ui, onClick = { viewModel.touchLine(l.line); onOpenLine(l.line.id) }, onStationClick = onOpenStation,
                    onRename = { renamingLine = l.line }, onToggleHidden = { viewModel.setLineHidden(l.line.id, true) })
            }
            if (hiddenLines.isNotEmpty()) {
                item {
                    TextButton(onClick = { showHiddenLines = !showHiddenLines }, modifier = Modifier.fillMaxWidth()) {
                        Icon(if (showHiddenLines) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (showHiddenLines) "Esconder ocultas" else "Mostrar ocultas (${hiddenLines.size})")
                    }
                }
                if (showHiddenLines) {
                    items(hiddenLines, key = { "h${it.line.id}" }) { l ->
                        LineCard(l, ui, onClick = { onOpenLine(l.line.id) }, onStationClick = onOpenStation,
                            onRename = { renamingLine = l.line }, onToggleHidden = { viewModel.setLineHidden(l.line.id, false) })
                    }
                }
            }
        }
    }

    if (renamingClient && node != null) {
        NameDialog("Renomear cliente", "Nome do cliente", initial = node.client.name,
            onConfirm = { viewModel.renameClient(clientId, it); renamingClient = false }, onDismiss = { renamingClient = false })
    }
    renamingLine?.let { l ->
        NameDialog("Renomear linha", "Nome da linha", initial = l.name,
            onConfirm = { viewModel.renameLine(l.id, it); renamingLine = null }, onDismiss = { renamingLine = null })
    }
    if (newLine) {
        NameDialog("Nova linha", "Nome da linha (cabine)", confirm = "Criar",
            onConfirm = { viewModel.createLine(clientId, it); newLine = false }, onDismiss = { newLine = false })
    }
}

/** Cartão de uma linha: nome, tipo de trabalho, "N estações · X de Y conectados", ⋮ e mini planta. */
@Composable
internal fun LineCard(
    node: LineNode,
    ui: ClientsUi,
    onClick: () -> Unit,
    onStationClick: (String) -> Unit,
    onRename: () -> Unit,
    onToggleHidden: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val l = node.line
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().alpha(if (l.hidden) 0.55f else 1f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Led(ClientTree.worstState(node.robots, ui::state))
                        Text(l.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        ClientTree.lineWorkType(node)?.let { Tag(it) }
                        if (l.hidden) Tag("oculta")
                    }
                    Text(
                        "${node.stations.size} ${if (node.stations.size == 1) "estação" else "estações"} · ${connectedText(node.robots, ui)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Mais opções da linha") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Renomear") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(
                            text = { Text(if (l.hidden) "Mostrar" else "Ocultar") },
                            leadingIcon = { Icon(if (l.hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, null) },
                            onClick = { menu = false; onToggleHidden() }
                        )
                    }
                }
            }
            MiniPlant(node, ui) { onStationClick(it.name) }
        }
    }
}
