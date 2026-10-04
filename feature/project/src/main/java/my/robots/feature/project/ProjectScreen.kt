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
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Check
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
import my.robots.core.designsystem.ActionTone
import my.robots.core.designsystem.AppTopBar
import my.robots.core.designsystem.BarAction
import my.robots.core.designsystem.HeartbeatDot
import my.robots.core.designsystem.label
import my.robots.core.model.EquipmentType
import my.robots.core.model.HeartbeatState
import my.robots.core.model.ProjectEquipment
import my.robots.core.model.ProjectLayout
import my.robots.core.model.Robot

/**
 * Tela de Projeto: a cabine com os robôs dispostos como na real.
 *
 * Visualização:
 * - barra do topo (AppTopBar): o nome e quantos estão conectados; na linha de ações, Conectar
 *   todos, Desconectar, Editar layout e Terminal Geral; no ⋮, Renomear e Projeto mestre;
 * - a grade: cada robô com LED de heartbeat, nome e estado. Tocar conecta ou desconecta;
 *   segurar abre o painel do robô. Linhas sem nenhum robô ficam ocultas;
 * - os equipamentos como faixas entre as linhas, com setas do sentido do fluxo;
 * - "Fora do layout": robôs sem vaga, também com LED;
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
    onOpenRobotTerminal: (Robot) -> Unit,
    onRenamed: (String) -> Unit
) {
    val view by viewModel.view.collectAsState()
    val draft by viewModel.draft.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val connected by viewModel.connectedIds.collectAsState()
    val heartbeats by viewModel.heartbeats.collectAsState()
    val tasks by viewModel.tasks.collectAsState()
    val running by viewModel.action.collectAsState()
    val lastAction by viewModel.lastAction.collectAsState()
    val pairs by viewModel.pairs.collectAsState()
    val pairViews by viewModel.pairViews.collectAsState()
    val layout by viewModel.layout.collectAsState()
    val allRobots by viewModel.allRobots.collectAsState()
    val otherProjects by viewModel.otherProjects.collectAsState()
    val programChoices by viewModel.programChoices.collectAsState()
    var showBackupAll by remember { mutableStateOf(false) }
    var showCommandAll by remember { mutableStateOf(false) }
    var showTransfer by remember { mutableStateOf(false) }
    var showMasterConfig by remember { mutableStateOf(false) }

    val editing = draft != null
    val shown = draft ?: view
    var showRename by remember { mutableStateOf(false) }
    var showAddEquipment by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            val total = view?.robots?.size ?: 0
            val connectedCount = view?.robots?.count { it.id in connected } ?: 0
            if (editing) {
                AppTopBar(
                    title = "Editar layout",
                    subtitle = viewModel.projectName,
                    onBack = viewModel::cancelEdit,
                    backIcon = Icons.Rounded.Close,
                    backLabel = "Cancelar",
                    actions = listOf(
                        BarAction(Icons.Rounded.Check, "Salvar", tone = ActionTone.Primary, onClick = viewModel::save),
                        BarAction(Icons.Rounded.Close, "Descartar", onClick = viewModel::cancelEdit)
                    )
                )
            } else {
                AppTopBar(
                    title = viewModel.projectName,
                    subtitle = if (total > 0) "$connectedCount de $total conectados" else null,
                    onBack = onBack,
                    menu = { close ->
                        DropdownMenuItem(
                            text = { Text("Renomear projeto") },
                            leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                            onClick = { close(); showRename = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Projeto mestre…") },
                            leadingIcon = { Icon(Icons.Rounded.AccountTree, null) },
                            onClick = { close(); showMasterConfig = true }
                        )
                    },
                    actions = listOf(
                        BarAction(
                            Icons.Rounded.Link, "Conectar todos",
                            tone = ActionTone.Primary,
                            enabled = connectedCount < total,
                            onClick = viewModel::connectAll
                        ),
                        BarAction(
                            Icons.Rounded.LinkOff, "Desconectar",
                            enabled = connectedCount > 0,
                            onClick = viewModel::disconnectAll
                        ),
                        BarAction(Icons.Rounded.GridView, "Editar layout", enabled = view != null, onClick = viewModel::startEdit),
                        BarAction(Icons.Rounded.Terminal, "Terminal Geral", onClick = onOpenTerminal)
                    )
                )
            }
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
                GroupActionsCard(
                    running = running,
                    lastAction = lastAction,
                    tasks = tasks,
                    hasPairs = pairs.isNotEmpty(),
                    onBackup = { showBackupAll = true },
                    onCommand = { showCommandAll = true },
                    onTransfer = {
                        viewModel.loadProgramChoices(pairs.map { it.master })
                        showTransfer = true
                    },
                    onCancel = viewModel::cancelAction,
                    onClear = viewModel::clearTasks
                )
            }

            CabinGrid(
                view = v,
                pairLabels = if (editing) emptyMap() else pairLabels(pairs, viewModel.projectName),
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
                pairViews.forEach { pv ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) { MasterSlaveDiagram(pv, viewModel.projectName) }
                    }
                }
                if (v.robots.isNotEmpty()) {
                    MiniTerminals(
                        robots = v.inCabinOrder,
                        tasks = tasks,
                        heartbeats = heartbeats,
                        history = viewModel::history,
                        onOpen = onOpenRobotTerminal
                    )
                }
                Text(
                    "Toque num robô para conectar ou desconectar. Segure para abrir o painel dele.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
    val current = view
    if (showBackupAll && current != null) {
        RobotChooserDialog(
            title = "Backup de todos",
            explanation = "Cada robô faz SAVE/FULL e o arquivo entra como backup dele. Quem não estiver conectado é conectado antes.",
            confirmLabel = "Fazer backup",
            robots = current.inCabinOrder,
            connected = connected,
            askCommand = false,
            onConfirm = { robots, _ -> showBackupAll = false; viewModel.backupAll(robots) },
            onDismiss = { showBackupAll = false }
        )
    }
    if (showCommandAll && current != null) {
        RobotChooserDialog(
            title = "Comando para todos",
            explanation = "O mesmo comando em cada robô escolhido, depois de conectar. A resposta aparece no mini terminal de cada um.",
            confirmLabel = "Enviar",
            robots = current.inCabinOrder,
            connected = connected,
            askCommand = true,
            onConfirm = { robots, cmd -> showCommandAll = false; viewModel.commandAll(robots, cmd) },
            onDismiss = { showCommandAll = false }
        )
    }
    if (showTransfer && pairs.isNotEmpty()) {
        TransferDialog(
            pairs = pairs,
            offsets = pairViews.associate { it.slaveName to it.offset },
            programs = programChoices,
            onConfirm = { selected, programs, withFrames, applyOffset ->
                showTransfer = false
                viewModel.transfer(selected, programs, withFrames, applyOffset)
            },
            onDismiss = { showTransfer = false }
        )
    }
    if (showMasterConfig && current != null) {
        MasterConfigDialog(
            projectName = viewModel.projectName,
            robots = current.inCabinOrder,
            otherProjects = otherProjects,
            allRobots = allRobots,
            currentMaster = layout?.masterProject,
            currentOffset = layout?.baseOffset ?: ProjectLayout.DEFAULT_BASE_OFFSET,
            onSave = { master, offset, map -> showMasterConfig = false; viewModel.saveMasterConfig(master, offset, map) },
            onDismiss = { showMasterConfig = false }
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
    pairLabels: Map<Int, String>,
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
                                pairLabel = pairLabels[robot.id],
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
    pairLabel: String? = null,
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
            // par mestre/escravo: "← R10" no escravo, "→ R14" no mestre
            if (pairLabel != null) {
                Text(
                    pairLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
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
