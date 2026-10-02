package my.robots.feature.project

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.robots.core.common.layout.Cell
import my.robots.core.common.layout.LayoutOps
import my.robots.core.designsystem.HeartbeatDot
import my.robots.core.designsystem.label
import my.robots.core.model.EquipmentType
import my.robots.core.model.HeartbeatState
import my.robots.core.model.ProjectEquipment
import my.robots.core.model.Robot

/**
 * Tela de Projeto: a cabine com os robôs dispostos como na real.
 *
 * Visualização:
 * - "Conectar todos" / "Desconectar todos" e quantos estão conectados;
 * - a grade: cada robô com LED de heartbeat, nome e estado. Tocar conecta ou desconecta;
 *   segurar abre o painel do robô. Linhas sem nenhum robô ficam ocultas;
 * - os equipamentos como faixas entre as linhas, com setas do sentido do fluxo;
 * - "Fora do layout": robôs sem vaga, também com LED;
 * - "Modo avançado": o Terminal Geral do projeto.
 *
 * Edição (lápis no topo): mexe numa cópia; "Salvar" grava e "Cancelar" descarta. Tocar num
 * robô o seleciona; com ele selecionado, tocar numa vaga move, tocar em outro robô troca os
 * dois, e "Tirar do layout" o manda para fora. "+" e "−" mudam linhas e colunas (só remove
 * vazias). Equipamentos: adicionar, subir/descer de faixa, trocar o sentido e excluir.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    viewModel: ProjectViewModel,
    onBack: () -> Unit,
    onOpenRobot: (Robot) -> Unit,
    onOpenTerminal: () -> Unit,
    onRenamed: (String) -> Unit
) {
    val view by viewModel.view.collectAsState()
    val draft by viewModel.draft.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val connected by viewModel.connectedIds.collectAsState()
    val heartbeats by viewModel.heartbeats.collectAsState()

    val editing = draft != null
    val shown = draft ?: view
    var menuOpen by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showAddEquipment by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (editing) "Editar layout" else viewModel.projectName,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (editing) viewModel.cancelEdit() else onBack() }) {
                        Icon(
                            if (editing) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = if (editing) "Cancelar" else "Voltar"
                        )
                    }
                },
                actions = {
                    if (editing) {
                        TextButton(onClick = viewModel::save) { Text("Salvar") }
                    } else {
                        IconButton(onClick = viewModel::startEdit, enabled = view != null) {
                            Icon(Icons.Rounded.Edit, contentDescription = "Editar layout")
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "Mais opções")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Renomear projeto") },
                                onClick = { menuOpen = false; showRename = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Terminal Geral") },
                                leadingIcon = { Icon(Icons.Rounded.Terminal, null) },
                                onClick = { menuOpen = false; onOpenTerminal() }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        val v = shown
        if (v == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (editing) {
                EditToolbar(
                    hasSelection = selected != null,
                    selectedName = selected?.let { v.robotsById[it]?.name },
                    onTakeOut = viewModel::takeSelectedOut,
                    onPlaceAll = viewModel::placeAll,
                    onAddEquipment = { showAddEquipment = true }
                )
            } else {
                ConnectionBar(
                    connectedCount = v.robots.count { it.id in connected },
                    total = v.robots.size,
                    onConnectAll = viewModel::connectAll,
                    onDisconnectAll = viewModel::disconnectAll
                )
            }

            CabinGrid(
                view = v,
                editing = editing,
                selected = selected,
                connected = connected,
                heartbeats = heartbeats,
                onRobotTap = { robot -> if (editing) viewModel.tapRobot(robot.id) else viewModel.toggleConnection(robot) },
                onRobotLongPress = { robot -> if (!editing) onOpenRobot(robot) },
                onCellTap = viewModel::tapCell,
                onAddRow = viewModel::addRow,
                onAddCol = viewModel::addCol,
                onRemoveRow = viewModel::removeRow,
                onRemoveCol = viewModel::removeCol,
                onMoveEquipment = viewModel::moveEquipment,
                onToggleDirection = viewModel::toggleDirection,
                onDeleteEquipment = viewModel::deleteEquipment
            )

            OutsideSection(
                robots = v.outside,
                editing = editing,
                selected = selected,
                connected = connected,
                heartbeats = heartbeats,
                onRobotTap = { robot -> if (editing) viewModel.tapRobot(robot.id) else viewModel.toggleConnection(robot) },
                onRobotLongPress = { robot -> if (!editing) onOpenRobot(robot) },
                onAreaTap = viewModel::takeSelectedOut
            )

            if (!editing) {
                Text(
                    "Toque num robô para conectar ou desconectar. Segure para abrir o painel dele.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedCard(onClick = onOpenTerminal, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Terminal, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Modo avançado", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Terminal Geral: o mesmo comando para todos os robôs do projeto.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showRename) {
        RenameDialog(
            current = viewModel.projectName,
            validate = viewModel::validateNewName,
            onConfirm = { name -> showRename = false; viewModel.rename(name, onRenamed) },
            onDismiss = { showRename = false }
        )
    }
    if (showAddEquipment) {
        AddEquipmentDialog(
            onConfirm = { type, name -> showAddEquipment = false; viewModel.addEquipment(type, name) },
            onDismiss = { showAddEquipment = false }
        )
    }
}

@Composable
private fun ConnectionBar(connectedCount: Int, total: Int, onConnectAll: () -> Unit, onDisconnectAll: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "$connectedCount de $total conectados",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onConnectAll, enabled = connectedCount < total, modifier = Modifier.weight(1f)) {
                Text("Conectar todos")
            }
            OutlinedButton(onClick = onDisconnectAll, enabled = connectedCount > 0, modifier = Modifier.weight(1f)) {
                Text("Desconectar todos")
            }
        }
    }
}

@Composable
private fun EditToolbar(
    hasSelection: Boolean,
    selectedName: String?,
    onTakeOut: () -> Unit,
    onPlaceAll: () -> Unit,
    onAddEquipment: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (hasSelection) "$selectedName selecionado: toque numa vaga para mover, ou noutro robô para trocar."
            else "Toque num robô para selecionar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onTakeOut, enabled = hasSelection, modifier = Modifier.weight(1f)) {
                Text("Tirar do layout", maxLines = 1)
            }
            OutlinedButton(onClick = onPlaceAll, modifier = Modifier.weight(1f)) {
                Text("Posicionar todos", maxLines = 1)
            }
        }
        OutlinedButton(onClick = onAddEquipment, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Adicionar equipamento")
        }
    }
}

/**
 * A grade: faixas de equipamento na posição de cada uma e as linhas de robôs. No modo de
 * edição aparecem as vagas vazias, os "−" das linhas/colunas vazias e os "+" de linha e coluna.
 */
@Composable
private fun CabinGrid(
    view: CabinView,
    editing: Boolean,
    selected: Int?,
    connected: Set<Int>,
    heartbeats: Map<Int, HeartbeatState>,
    onRobotTap: (Robot) -> Unit,
    onRobotLongPress: (Robot) -> Unit,
    onCellTap: (Cell) -> Unit,
    onAddRow: () -> Unit,
    onAddCol: () -> Unit,
    onRemoveRow: (Int) -> Unit,
    onRemoveCol: (Int) -> Unit,
    onMoveEquipment: (Int, Int) -> Unit,
    onToggleDirection: (Int) -> Unit,
    onDeleteEquipment: (Int) -> Unit
) {
    val cabin = view.cabin
    val rowHandleWidth = if (editing) 32.dp else 0.dp

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // "−" em cima de cada coluna vazia
        if (editing) {
            Row(Modifier.fillMaxWidth().padding(start = rowHandleWidth), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in 0 until cabin.cols) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (LayoutOps.canRemoveCol(cabin, c)) {
                            SmallIconButton(Icons.Rounded.Remove, "Remover coluna ${c + 1}") { onRemoveCol(c) }
                        }
                    }
                }
                // um "+" só para a coluna nova, no alto da grade
                if (LayoutOps.canAddCol(cabin)) {
                    SmallIconButton(Icons.Rounded.Add, "Adicionar coluna", modifier = Modifier.width(34.dp), onClick = onAddCol)
                }
            }
        }

        for (r in 0..cabin.rows) {
            view.equipment.forEachIndexed { i, e ->
                if (e.position == r) {
                    EquipmentBand(
                        equipment = e,
                        editing = editing,
                        canMoveUp = e.position > 0,
                        canMoveDown = e.position < cabin.rows,
                        onMoveUp = { onMoveEquipment(i, -1) },
                        onMoveDown = { onMoveEquipment(i, 1) },
                        onToggleDirection = { onToggleDirection(i) },
                        onDelete = { onDeleteEquipment(i) }
                    )
                }
            }
            if (r == cabin.rows) break
            if (!editing && cabin.isRowEmpty(r)) continue

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (editing) {
                    Box(Modifier.width(rowHandleWidth - 6.dp), contentAlignment = Alignment.Center) {
                        if (LayoutOps.canRemoveRow(cabin, r)) {
                            SmallIconButton(Icons.Rounded.Remove, "Remover linha ${r + 1}") { onRemoveRow(r) }
                        }
                    }
                }
                for (c in 0 until cabin.cols) {
                    val cell = Cell(r, c)
                    val robot = cabin.robotAt(cell)?.let { view.robotsById[it] }
                    Box(Modifier.weight(1f)) {
                        if (robot != null) {
                            RobotCell(
                                robot = robot,
                                isConnected = robot.id in connected,
                                heartbeat = heartbeats[robot.id] ?: HeartbeatState.DISCONNECTED,
                                isSelected = robot.id == selected,
                                onTap = { onRobotTap(robot) },
                                onLongPress = { onRobotLongPress(robot) }
                            )
                        } else {
                            EmptyCell(editing = editing, highlight = editing && selected != null, onTap = { onCellTap(cell) })
                        }
                    }
                }
                // mesmo espaço do "+" do alto, para as vagas ficarem alinhadas
                if (editing && LayoutOps.canAddCol(cabin)) Spacer(Modifier.width(34.dp))
            }
        }

        if (editing && LayoutOps.canAddRow(cabin)) {
            OutlinedButton(onClick = onAddRow, modifier = Modifier.fillMaxWidth().padding(start = rowHandleWidth)) {
                Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Adicionar linha")
            }
        }
        if (!editing && cabin.placed.isEmpty()) {
            Text(
                "Nenhum robô posicionado. Toque no lápis para montar a cabine.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RobotCell(
    robot: Robot,
    isConnected: Boolean,
    heartbeat: HeartbeatState,
    isSelected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    val border = when {
        isSelected -> BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
        else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isConnected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = border,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            HeartbeatDot(state = heartbeat)
            Text(
                robot.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Text(
                heartbeat.label(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun EmptyCell(editing: Boolean, highlight: Boolean, onTap: () -> Unit) {
    val color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(BorderStroke(1.dp, color.copy(alpha = if (editing) 0.9f else 0.4f)), RoundedCornerShape(12.dp))
            .then(if (editing) Modifier.clickable(onClick = onTap) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (highlight) Icon(Icons.Rounded.Add, contentDescription = "Mover para cá", tint = color)
    }
}

/**
 * Faixa de um equipamento. Mostra o nome (ou o tipo) e o sentido do fluxo com setas. Na
 * edição: tocar alterna o sentido, setas ↑↓ mudam a faixa e a lixeira exclui.
 */
@Composable
private fun EquipmentBand(
    equipment: ProjectEquipment,
    editing: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleDirection: () -> Unit,
    onDelete: () -> Unit
) {
    val title = equipment.name.ifBlank { equipment.type.displayName }
    val arrow = when (equipment.flowDirection) {
        1 -> Icons.AutoMirrored.Rounded.ArrowForward
        -1 -> Icons.AutoMirrored.Rounded.ArrowBack
        else -> null
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(if (editing) Modifier.clickable(onClick = onToggleDirection) else Modifier)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (arrow != null) Icon(arrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (arrow != null && !editing) Icon(arrow, contentDescription = null, modifier = Modifier.size(18.dp))
            if (editing) {
                SmallIconButton(Icons.Rounded.KeyboardArrowUp, "Subir", enabled = canMoveUp, onClick = onMoveUp)
                SmallIconButton(Icons.Rounded.KeyboardArrowDown, "Descer", enabled = canMoveDown, onClick = onMoveDown)
                SmallIconButton(Icons.Rounded.Delete, "Excluir equipamento", onClick = onDelete)
            }
        }
    }
}

/**
 * Robôs sem vaga na grade. No modo de edição, tocar na área com um robô selecionado tira ele
 * do layout.
 */
@Composable
private fun OutsideSection(
    robots: List<Robot>,
    editing: Boolean,
    selected: Int?,
    connected: Set<Int>,
    heartbeats: Map<Int, HeartbeatState>,
    onRobotTap: (Robot) -> Unit,
    onRobotLongPress: (Robot) -> Unit,
    onAreaTap: () -> Unit
) {
    if (robots.isEmpty() && !editing) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .then(if (editing && selected != null) Modifier.clickable(onClick = onAreaTap) else Modifier)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Fora do layout", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (robots.isEmpty()) {
            Text(
                "Todos os robôs estão na grade.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        robots.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { robot ->
                    Box(Modifier.weight(1f)) {
                        RobotCell(
                            robot = robot,
                            isConnected = robot.id in connected,
                            heartbeat = heartbeats[robot.id] ?: HeartbeatState.DISCONNECTED,
                            isSelected = robot.id == selected,
                            onTap = { onRobotTap(robot) },
                            onLongPress = { onRobotLongPress(robot) }
                        )
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SmallIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(34.dp)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun RenameDialog(
    current: String,
    validate: (String) -> String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(current) }
    val error = validate(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Renomear projeto") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Nome") },
                    isError = error != null && name != current,
                    supportingText = { if (error != null && name != current) Text(error) }
                )
                Text(
                    "Se já existir um projeto com esse nome, os robôs passam para ele.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = error == null) { Text("Renomear") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun AddEquipmentDialog(onConfirm: (EquipmentType, String) -> Unit, onDismiss: () -> Unit) {
    var type by remember { mutableStateOf(EquipmentType.CONVEYOR) }
    var name by remember { mutableStateOf("") }
    val nameMissing = type == EquipmentType.OTHER && name.isBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adicionar equipamento") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EquipmentType.entries.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { type = t },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = type == t, onClick = { type = t })
                        Text(t.displayName)
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(if (type == EquipmentType.OTHER) "Nome (obrigatório)" else "Nome (opcional)") }
                )
                Text(
                    "Ele entra abaixo da última linha. Depois, use as setas da faixa para mudar de lugar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(type, name) }, enabled = !nameMissing) { Text("Adicionar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}
