package my.robots.feature.clients

import androidx.compose.material.icons.rounded.SmartToy
import my.robots.core.designsystem.RobotDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import my.robots.core.data.hierarchy.ClientNode
import my.robots.core.data.hierarchy.ClientTree
import my.robots.core.data.hierarchy.Shortcut
import my.robots.core.data.hierarchy.StatusFilter
import my.robots.core.data.hierarchy.TreeFilter
import my.robots.core.designsystem.AppTopBar
import my.robots.core.designsystem.BarAction
import my.robots.core.model.Client
import my.robots.core.model.Manufacturer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tela inicial: um cartão por cliente com a mini planta de cada linha (estações na ordem do
 * processo, um quadrado por robô na cor do status). Último usado primeiro; ocultos no fim, com
 * "Mostrar ocultos". Com um cliente só, abre direto nele (ou na linha, se ele tiver uma só).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClientsScreen(
    viewModel: ClientsViewModel,
    onOpenClient: (Long) -> Unit,
    onOpenLine: (Long) -> Unit,
    onOpenStation: (String) -> Unit,
    onOpenOldList: () -> Unit,
    onOpenManufacturers: () -> Unit,
    onOpenMasterSlave: () -> Unit
) {
    val ui by viewModel.ui.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val showHidden by viewModel.showHidden.collectAsState()
    var autoOpened by rememberSaveable { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var newClient by remember { mutableStateOf(false) }
    var newLineFor by remember { mutableStateOf<Long?>(null) }
    var pickClientForLine by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Client?>(null) }
    var newRobot by remember { mutableStateOf(false) }

    // atalho: um cliente visível só -> abre nele (ou direto na linha, se ele tem uma só)
    LaunchedEffect(ui.loaded) {
        if (ui.loaded && !autoOpened) {
            autoOpened = true
            when (val s = ClientTree.shortcut(ui.visible)) {
                is Shortcut.OpenClient -> onOpenClient(s.clientId)
                is Shortcut.OpenLine -> onOpenLine(s.lineId)
                null -> {}
            }
        }
    }

    val shown = ClientTree.filter(ui.visible, filter, ui::state)
    val hiddenClients = ui.withHidden.filter { it.client.hidden }.map { it.withoutHiddenInside() }
    val allRobots = ui.visible.flatMap { it.robots }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "MyRobots",
                subtitle = "${ui.visible.size} ${if (ui.visible.size == 1) "cliente" else "clientes"} · " +
                    "${ClientTree.connectedCount(allRobots, ui::state)} de ${allRobots.size} robôs conectados",
                actions = listOf(
                    BarAction(
                        Icons.Rounded.FilterList,
                        if (filter.isEmpty) "Filtros" else "Filtros (${filter.count})",
                        selected = !filter.isEmpty,
                        badge = !filter.isEmpty,
                        onClick = { showFilters = true }
                    ),
                    BarAction(Icons.Rounded.Add, "Novo", menu = { close ->
                        DropdownMenuItem(
                            text = { Text("Novo cliente") },
                            leadingIcon = { Icon(Icons.Rounded.Business, null) },
                            onClick = { close(); newClient = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Nova linha") },
                            leadingIcon = { Icon(Icons.Rounded.AccountTree, null) },
                            enabled = ui.clients.isNotEmpty(),
                            onClick = { close(); pickClientForLine = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Novo robô") },
                            leadingIcon = { Icon(Icons.Rounded.SmartToy, null) },
                            onClick = { close(); newRobot = true }
                        )
                    }),
                    BarAction(Icons.Rounded.Settings, "Configurações", menu = { close ->
                        DropdownMenuItem(
                            text = { Text("Lista de robôs (antiga)") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.List, null) },
                            onClick = { close(); onOpenOldList() }
                        )
                        DropdownMenuItem(
                            text = { Text("Fabricantes: pesquisa e comandos") },
                            leadingIcon = { Icon(Icons.Rounded.Tune, null) },
                            onClick = { close(); onOpenManufacturers() }
                        )
                        DropdownMenuItem(
                            text = { Text("Mestre / Escravo") },
                            leadingIcon = { Icon(Icons.Rounded.AccountTree, null) },
                            onClick = { close(); onOpenMasterSlave() }
                        )
                    })
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!filter.isEmpty) {
                item { ActiveFilters(filter, ui, onChange = viewModel::setFilter) }
            }
            if (ui.loaded && ui.clients.isEmpty()) {
                item { EmptyState(onNewRobot = { newRobot = true }) }
            } else if (ui.loaded && shown.isEmpty() && !filter.isEmpty) {
                item {
                    Text("Nenhum cliente com esses filtros.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                }
            }
            items(shown, key = { "c${it.client.id}" }) { c ->
                ClientCard(
                    node = c,
                    ui = ui,
                    onClick = { viewModel.touchClient(c.client.id); onOpenClient(c.client.id) },
                    onStationClick = onOpenStation,
                    onRename = { renaming = c.client },
                    onToggleHidden = { viewModel.setClientHidden(c.client.id, !c.client.hidden) }
                )
            }
            if (hiddenClients.isNotEmpty()) {
                item {
                    TextButton(onClick = viewModel::toggleShowHidden, modifier = Modifier.fillMaxWidth()) {
                        Icon(if (showHidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (showHidden) "Esconder ocultos" else "Mostrar ocultos (${hiddenClients.size})")
                    }
                }
                if (showHidden) {
                    items(hiddenClients, key = { "h${it.client.id}" }) { c ->
                        ClientCard(
                            node = c,
                            ui = ui,
                            onClick = { onOpenClient(c.client.id) },
                            onStationClick = onOpenStation,
                            onRename = { renaming = c.client },
                            onToggleHidden = { viewModel.setClientHidden(c.client.id, false) }
                        )
                    }
                }
            }
        }
    }

    if (showFilters) {
        FilterSheet(ui = ui, current = filter, onApply = { viewModel.setFilter(it); showFilters = false }, onDismiss = { showFilters = false })
    }
    if (newClient) {
        NameDialog("Novo cliente", "Nome do cliente", confirm = "Criar", onConfirm = { viewModel.createClient(it); newClient = false }, onDismiss = { newClient = false })
    }
    if (pickClientForLine) {
        ChoiceDialog(
            title = "Nova linha em qual cliente?",
            options = ui.clients.sortedWith(ClientTree.byLastUsed()),
            text = { it.name + if (it.hidden) " (oculto)" else "" },
            onPick = { pickClientForLine = false; newLineFor = it.id },
            onDismiss = { pickClientForLine = false }
        )
    }
    newLineFor?.let { cid ->
        NameDialog(
            "Nova linha · ${ui.client(cid)?.name.orEmpty()}", "Nome da linha (cabine)", confirm = "Criar",
            onConfirm = { viewModel.createLine(cid, it); newLineFor = null },
            onDismiss = { newLineFor = null }
        )
    }
    if (newRobot) {
        RobotDialog(
            existingProjects = viewModel.stationNames(),
            onDismiss = { newRobot = false },
            onConfirm = { name, ip, port, project, manufacturer, autoLogin, user, password ->
                viewModel.addRobot(name, ip, port, project, manufacturer, autoLogin, user, password)
                newRobot = false
            }
        )
    }
    renaming?.let { c ->
        NameDialog("Renomear cliente", "Nome do cliente", initial = c.name, onConfirm = { viewModel.renameClient(c.id, it); renaming = null }, onDismiss = { renaming = null })
    }
}

/** Cartão do cliente: nome, resumo, último usado, ⋮ e a mini planta de cada linha. */
@Composable
private fun ClientCard(
    node: ClientNode,
    ui: ClientsUi,
    onClick: () -> Unit,
    onStationClick: (String) -> Unit,
    onRename: () -> Unit,
    onToggleHidden: () -> Unit
) {
    val c = node.client
    var menu by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().alpha(if (c.hidden) 0.55f else 1f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (c.hidden) Tag("oculto")
                    }
                    Text(
                        "${node.lines.size} ${if (node.lines.size == 1) "linha" else "linhas"} · ${connectedText(node.robots, ui)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (c.lastUsedAt > 0) "Último uso: ${fmt.format(Date(c.lastUsedAt))}" else "Ainda não aberto",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Mais opções do cliente") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Renomear") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(
                            text = { Text(if (c.hidden) "Mostrar" else "Ocultar") },
                            leadingIcon = { Icon(if (c.hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, null) },
                            onClick = { menu = false; onToggleHidden() }
                        )
                    }
                }
            }
            node.lines.forEach { l ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(l.line.name, style = MaterialTheme.typography.labelLarge)
                        ClientTree.lineWorkType(l)?.let { Tag(it) }
                    }
                    MiniPlant(l, ui) { onStationClick(it.name) }
                }
            }
            if (node.lines.isEmpty()) {
                Text("Nenhuma linha. Use + Nova linha.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Filtros ativos como etiquetas; tocar tira o filtro. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveFilters(f: TreeFilter, ui: ClientsUi, onChange: (TreeFilter) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        f.workTypes.forEach { t -> RemovableChip(t) { onChange(f.copy(workTypes = f.workTypes - t)) } }
        f.lineIds.forEach { id -> RemovableChip(ui.line(id)?.name ?: "Linha") { onChange(f.copy(lineIds = f.lineIds - id)) } }
        f.status?.let { s -> RemovableChip(s.label) { onChange(f.copy(status = null)) } }
        f.manufacturers.forEach { m -> RemovableChip(m.displayName) { onChange(f.copy(manufacturers = f.manufacturers - m)) } }
        TextButton(onClick = { onChange(TreeFilter()) }) { Text("Limpar") }
    }
}

@Composable
private fun RemovableChip(text: String, onRemove: () -> Unit) {
    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(text) },
        trailingIcon = { Icon(Icons.Rounded.Close, "Tirar o filtro", Modifier.size(16.dp)) }
    )
}

/**
 * Filtros em folha de baixo: tipo de trabalho, linha, status e marca. Muda numa cópia e só
 * aplica em "Ver resultados".
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(ui: ClientsUi, current: TreeFilter, onApply: (TreeFilter) -> Unit, onDismiss: () -> Unit) {
    var f by remember { mutableStateOf(current) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Filtros", style = MaterialTheme.typography.titleLarge)
            Section("Tipo de trabalho") {
                ui.workTypes.forEach { t ->
                    FilterChip(selected = t.name in f.workTypes, onClick = {
                        f = f.copy(workTypes = if (t.name in f.workTypes) f.workTypes - t.name else f.workTypes + t.name)
                    }, label = { Text(t.name) })
                }
            }
            Section("Linha") {
                ui.visible.forEach { c ->
                    c.lines.forEach { l ->
                        FilterChip(selected = l.line.id in f.lineIds, onClick = {
                            f = f.copy(lineIds = if (l.line.id in f.lineIds) f.lineIds - l.line.id else f.lineIds + l.line.id)
                        }, label = { Text(if (ui.visible.size > 1) "${c.client.name} · ${l.line.name}" else l.line.name) })
                    }
                }
            }
            Section("Status") {
                StatusFilter.entries.forEach { s ->
                    FilterChip(selected = f.status == s, onClick = { f = f.copy(status = if (f.status == s) null else s) }, label = { Text(s.label) })
                }
            }
            Section("Marca") {
                // por enquanto só a Kawasaki tem suporte
                val m = Manufacturer.KAWASAKI
                FilterChip(selected = m in f.manufacturers, onClick = {
                    f = f.copy(manufacturers = if (m in f.manufacturers) f.manufacturers - m else f.manufacturers + m)
                }, label = { Text("Kawasaki") })
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { f = TreeFilter() }, modifier = Modifier.weight(1f)) { Text("Limpar") }
                Button(onClick = { onApply(f) }, modifier = Modifier.weight(1f)) { Text("Ver resultados") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

/** Sem nenhum cliente (instalação nova, sem robôs): cadastrar pelo caminho de hoje. */
@Composable
private fun EmptyState(onNewRobot: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Nenhum cliente ainda.", style = MaterialTheme.typography.titleMedium)
        Text(
            "Cadastre um robô: o projeto dele vira uma estação de \"Meu cliente › Linha 1\". " +
                "Depois dá para criar clientes e linhas e mover as estações.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onNewRobot) { Text("Cadastrar robô") }
    }
}
