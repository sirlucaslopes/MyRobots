package my.robots.feature.project

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow
import my.robots.core.data.ProjectOperations.MasterSlavePair
import my.robots.core.data.RobotTask
import my.robots.core.data.TaskState
import my.robots.core.designsystem.FormDialog
import my.robots.core.designsystem.HeartbeatDot
import my.robots.core.model.HeartbeatState
import my.robots.core.model.Robot

// ---------------------------------------------------------------------------------------------
// Ações em grupo
// ---------------------------------------------------------------------------------------------

/**
 * Cartão "Ações em grupo": backup de todos, comando para todos e mestre -> escravo (só se o
 * projeto tiver pares). Mostra o andamento da última ação: quantos terminaram, quantos
 * falharam, e o botão de parar (durante) ou limpar (depois).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GroupActionsCard(
    running: String?,
    lastAction: String?,
    tasks: Map<Int, RobotTask>,
    hasPairs: Boolean,
    onBackup: () -> Unit,
    onCommand: () -> Unit,
    onTransfer: () -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Ações em grupo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onBackup, enabled = running == null) {
                    Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Backup de todos")
                }
                FilledTonalButton(onClick = onCommand, enabled = running == null) {
                    Icon(Icons.Rounded.Keyboard, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Comando")
                }
                if (hasPairs) {
                    FilledTonalButton(onClick = onTransfer, enabled = running == null) {
                        Icon(Icons.AutoMirrored.Rounded.Send, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Mestre → escravo")
                    }
                }
            }
            if (tasks.isNotEmpty()) {
                val done = tasks.values.count { it.finished }
                val failed = tasks.values.count { it.state == TaskState.FAILED }
                val warned = tasks.values.count { it.state == TaskState.WARNING }
                Text(
                    buildString {
                        append(lastAction ?: "")
                        append(" · $done de ${tasks.size} terminados")
                        if (failed > 0) append(" · $failed com falha")
                        if (warned > 0) append(" · $warned com aviso")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LinearProgressIndicator(progress = { done / tasks.size.toFloat() }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (running != null) TextButton(onClick = onCancel) { Text("Parar") }
                    else TextButton(onClick = onClear) { Text("Limpar") }
                }
            } else {
                Text(
                    "Cada robô é conectado antes (login e checagens) e todos rodam ao mesmo tempo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Escolha dos robôs para uma ação em grupo (todos marcados de início). Com [askCommand], pede
 * também o comando a mandar.
 */
@Composable
internal fun RobotChooserDialog(
    title: String,
    explanation: String,
    confirmLabel: String,
    robots: List<Robot>,
    connected: Set<Int>,
    askCommand: Boolean,
    onConfirm: (List<Robot>, String) -> Unit,
    onDismiss: () -> Unit
) {
    var chosen by remember { mutableStateOf(robots.map { it.id }.toSet()) }
    var command by remember { mutableStateOf("") }
    val ok = chosen.isNotEmpty() && (!askCommand || command.isNotBlank())
    FormDialog(
        title = title,
        onDismiss = onDismiss,
        confirmButton = {
            Button(onClick = { onConfirm(robots.filter { it.id in chosen }, command) }, enabled = ok) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    ) {
        Text(explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (askCommand) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text("Comando (ex.: ID, FREE, STATUS)") },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Robôs (${chosen.size} de ${robots.size})", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { chosen = if (chosen.size == robots.size) emptySet() else robots.map { it.id }.toSet() }) {
                Text(if (chosen.size == robots.size) "Nenhum" else "Todos")
            }
        }
        robots.forEach { r ->
            CheckRow(
                checked = r.id in chosen,
                text = r.name,
                supporting = if (r.id in connected) "conectado" else "vai conectar",
                onToggle = { chosen = if (r.id in chosen) chosen - r.id else chosen + r.id }
            )
        }
    }
}

@Composable
private fun CheckRow(checked: Boolean, text: String, supporting: String? = null, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (supporting != null) {
            Text(supporting, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
        }
    }
}

/**
 * Transferência mestre -> escravo: quais pares, quais programas (do último backup dos
 * mestres) e se o frame da base vai junto. Explica a troca da base pelo offset.
 */
@Composable
internal fun TransferDialog(
    pairs: List<MasterSlavePair>,
    offsets: Map<String, String>,
    programs: List<String>?,
    onConfirm: (List<MasterSlavePair>, List<String>, Boolean, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var chosenPairs by remember { mutableStateOf(pairs.map { it.slave.id }.toSet()) }
    var chosenPrograms by remember { mutableStateOf(listOf<String>()) }
    var withFrames by remember { mutableStateOf(true) }
    var applyOffset by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf("") }
    val offsetText = offsets.values.distinct().joinToString(" / ").ifBlank { "top_offset" }

    FormDialog(
        title = "Mestre → escravo",
        onDismiss = onDismiss,
        confirmButton = {
            Button(
                onClick = { onConfirm(pairs.filter { it.slave.id in chosenPairs }, chosenPrograms, withFrames, applyOffset) },
                enabled = chosenPairs.isNotEmpty() && chosenPrograms.isNotEmpty()
            ) { Text("Transferir") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    ) {
        Text(
            "Os programas saem do último backup de cada mestre.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("Pares", style = MaterialTheme.typography.labelLarge)
        pairs.forEach { p ->
            CheckRow(
                checked = p.slave.id in chosenPairs,
                text = "${p.master.name} → ${p.slave.name}",
                supporting = p.slave.project,
                onToggle = { chosenPairs = if (p.slave.id in chosenPairs) chosenPairs - p.slave.id else chosenPairs + p.slave.id }
            )
        }
        // pergunta se a base do programa de destino recebe o offset
        CheckRow(checked = applyOffset, text = "Alterar a base (somar o offset)", onToggle = { applyOffset = !applyOffset })
        Text(
            if (applyOffset) "No escravo, \"BASE fr_[N]\" vira \"BASE fr_[N]+$offsetText\". BASE NULL e bases que já " +
                "somam o offset ficam iguais."
            else "Os programas vão para o escravo exatamente como estão no mestre, sem mexer na base.",
            style = MaterialTheme.typography.bodySmall,
            color = if (applyOffset) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(start = 48.dp)
        )
        CheckRow(checked = withFrames, text = "Levar o frame da base (.TRANS)", onToggle = { withFrames = !withFrames })

        Text("Programas (${chosenPrograms.size})", style = MaterialTheme.typography.labelLarge)
        if (chosenPrograms.isNotEmpty()) {
            Text(
                chosenPrograms.joinToString(),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary
            )
        }
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("Procurar programa") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        when {
            programs == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Lendo os backups dos mestres…", style = MaterialTheme.typography.bodySmall)
            }
            programs.isEmpty() -> Text(
                "Os mestres ainda não têm backup com programas. Faça um backup deles primeiro.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            else -> {
                val shown = programs.filter { it.contains(filter.trim(), ignoreCase = true) }
                shown.take(MAX_PROGRAMS_SHOWN).forEach { name ->
                    CheckRow(
                        checked = name in chosenPrograms,
                        text = name,
                        onToggle = { chosenPrograms = if (name in chosenPrograms) chosenPrograms - name else chosenPrograms + name }
                    )
                }
                if (shown.size > MAX_PROGRAMS_SHOWN) {
                    Text(
                        "Mais ${shown.size - MAX_PROGRAMS_SHOWN}: procure pelo nome.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private const val MAX_PROGRAMS_SHOWN = 40

/**
 * Configura este projeto como escravo de outro: o projeto mestre, a variável de offset e o
 * robô mestre de cada robô daqui. Ao escolher o mestre, os pares vêm pela mesma posição na
 * cabine (R10 na vaga 2,1 do Primer -> o robô na vaga 2,1 do Top Coat).
 */
@Composable
internal fun MasterConfigDialog(
    projectName: String,
    robots: List<Robot>,
    otherProjects: List<String>,
    allRobots: List<Robot>,
    currentMaster: String?,
    currentOffset: String,
    onSave: (String?, String, Map<Int, Int?>) -> Unit,
    onDismiss: () -> Unit
) {
    var master by remember { mutableStateOf(currentMaster) }
    var offset by remember { mutableStateOf(currentOffset) }
    var masters by remember { mutableStateOf(robots.associate { it.id to it.masterRobotId }) }
    val masterRobots = allRobots.filter { it.project == master }.sortedBy { it.name }

    fun pairByPosition(project: String?) {
        val candidates = allRobots.filter { it.project == project }
        masters = robots.associate { r ->
            r.id to candidates.firstOrNull { it.layoutRow != null && it.layoutRow == r.layoutRow && it.layoutCol == r.layoutCol }?.id
        }
    }

    FormDialog(
        title = "Projeto mestre",
        onDismiss = onDismiss,
        confirmButton = {
            Button(onClick = { onSave(master, offset, masters) }, enabled = master == null || offset.isNotBlank()) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    ) {
        Text(
            "$projectName recebe os programas do mestre. Ex.: o Primer é o mestre e o Top Coat o escravo: " +
                "a trajetória é a mesma, só muda a altura, que vem da variável de offset somada na base.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        PickerButton(
            label = "Mestre",
            value = master ?: "Nenhum (projeto sem mestre)",
            options = listOf<String?>(null) + otherProjects,
            optionText = { it ?: "Nenhum" },
            onPick = { picked -> master = picked; if (picked != null) pairByPosition(picked) }
        )
        if (master != null) {
            OutlinedTextField(
                value = offset,
                onValueChange = { offset = it.replace(" ", "") },
                label = { Text("Offset somado na base (no escravo)") },
                supportingText = { Text("BASE fr_[100] → BASE fr_[100]+${offset.ifBlank { "…" }}") },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pares", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { pairByPosition(master) }) { Text("Parear pela posição") }
            }
            robots.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(64.dp))
                    Text("←", modifier = Modifier.padding(horizontal = 8.dp))
                    PickerButton(
                        label = null,
                        value = masterRobots.firstOrNull { it.id == masters[r.id] }?.name ?: "Sem par",
                        options = listOf<Robot?>(null) + masterRobots,
                        optionText = { it?.name ?: "Sem par" },
                        onPick = { picked -> masters = masters + (r.id to picked?.id) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> PickerButton(
    label: String?,
    value: String,
    options: List<T>,
    optionText: (T) -> String,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        if (label != null) Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(value, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Rounded.ArrowDropDown, null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { o ->
                    DropdownMenuItem(text = { Text(optionText(o)) }, onClick = { open = false; onPick(o) })
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Mini terminais
// ---------------------------------------------------------------------------------------------

/**
 * Um mini terminal por robô, dois por linha, na ordem da cabine: LED, nome, o andamento da
 * ação em grupo e as últimas linhas do terminal. Tocar abre o terminal do robô.
 */
@Composable
internal fun MiniTerminals(
    robots: List<Robot>,
    tasks: Map<Int, RobotTask>,
    heartbeats: Map<Int, HeartbeatState>,
    history: (Int) -> StateFlow<List<String>>,
    onOpen: (Robot) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Terminais", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        robots.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { r ->
                    MiniTerminal(
                        robot = r,
                        task = tasks[r.id],
                        heartbeat = heartbeats[r.id] ?: HeartbeatState.DISCONNECTED,
                        history = history(r.id),
                        onClick = { onOpen(r) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MiniTerminal(
    robot: Robot,
    task: RobotTask?,
    heartbeat: HeartbeatState,
    history: StateFlow<List<String>>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lines by history.collectAsState()
    val tail = remember(lines) { lines.map { it.trimEnd() }.filter { it.isNotEmpty() }.takeLast(MINI_LINES) }
    val color = task?.state?.let { taskColor(it) } ?: Color(0xFF3A3A3A)
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF0E0E0E),
        border = BorderStroke(if (task != null) 2.dp else 1.dp, color),
        modifier = modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeartbeatDot(state = heartbeat)
                Spacer(Modifier.width(6.dp))
                Text(robot.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (task != null && (task.state == TaskState.CONNECTING || task.state == TaskState.RUNNING)) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = color)
                }
            }
            Column(Modifier.fillMaxWidth().height((MINI_LINES * 13).dp)) {
                tail.forEach { l ->
                    Text(
                        l, color = Color(0xFFB8C4B8), fontFamily = FontFamily.Monospace, fontSize = 9.sp, lineHeight = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Clip, softWrap = false
                    )
                }
            }
            if (task != null) {
                Text(
                    task.message.ifBlank { taskLabel(task.state) },
                    color = color, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private const val MINI_LINES = 5

private fun taskColor(state: TaskState): Color = when (state) {
    TaskState.WAITING -> Color(0xFF8A8A8A)
    TaskState.CONNECTING, TaskState.RUNNING -> Color(0xFF4FA3FF)
    TaskState.DONE -> Color(0xFF4CAF50)
    TaskState.WARNING -> Color(0xFFFFB300)
    TaskState.FAILED -> Color(0xFFEF5350)
}

private fun taskLabel(state: TaskState): String = when (state) {
    TaskState.WAITING -> "Na fila"
    TaskState.CONNECTING -> "Conectando…"
    TaskState.RUNNING -> "Rodando…"
    TaskState.DONE -> "Pronto"
    TaskState.WARNING -> "Pronto, com aviso"
    TaskState.FAILED -> "Falhou"
}

// ---------------------------------------------------------------------------------------------
// Desenho mestre -> escravo
// ---------------------------------------------------------------------------------------------

/**
 * Os dois layouts, o mestre em cima e o escravo embaixo, com uma seta de cada robô mestre até
 * o seu escravo. As setas correm pelos corredores à esquerda de cada coluna (cada linha da
 * cabine numa faixa), para não passar por cima dos robôs. Linhas sem robô não aparecem. Pares
 * com robô fora do layout ficam listados embaixo.
 */
@Composable
internal fun MasterSlaveDiagram(pv: ProjectPairView, current: String) {
    val measurer = rememberTextMeasurer()
    val lineColor = MaterialTheme.colorScheme.primary
    val masterFill = MaterialTheme.colorScheme.primaryContainer
    val slaveFill = MaterialTheme.colorScheme.tertiaryContainer
    val idleFill = MaterialTheme.colorScheme.surfaceVariant
    val onMaster = MaterialTheme.colorScheme.onPrimaryContainer
    val onSlave = MaterialTheme.colorScheme.onTertiaryContainer
    val onIdle = MaterialTheme.colorScheme.onSurfaceVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val titleStyle = MaterialTheme.typography.labelLarge

    val mRows = pv.master.visibleRows()
    val sRows = pv.slave.visibleRows()
    val cols = maxOf(pv.master.cabin.cols, pv.slave.cabin.cols, 1)
    val pairedMasters = pv.pairs.map { it.master.id }.toSet()
    val pairedSlaves = pv.pairs.map { it.slave.id }.toSet()
    val lanes = maxOf(mRows.size, 1)

    val cellH = 38.dp
    val rowGap = 10.dp
    val titleH = 22.dp
    val midGap = 22.dp
    val height = titleH + (cellH + rowGap) * mRows.size + midGap + titleH + (cellH + rowGap) * sRows.size

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(height)) {
            val laneStep = 7.dp.toPx()
            val gutter = 8.dp.toPx() + laneStep * lanes
            val cw = (size.width - gutter * cols) / cols
            val ch = cellH.toPx()
            val rg = rowGap.toPx()
            val th = titleH.toPx()
            fun cellLeft(c: Int) = gutter + c * (cw + gutter)
            val mTop = th
            val sTitleTop = mTop + (ch + rg) * mRows.size + midGap.toPx()
            val sTop = sTitleTop + th

            fun title(text: String, y: Float, highlight: Boolean) {
                val t = measurer.measure(text, titleStyle.copy(color = if (highlight) lineColor else muted, fontWeight = FontWeight.SemiBold))
                drawText(t, topLeft = Offset(gutter, y))
            }
            title("${pv.masterName} (mestre)", 0f, pv.masterName == current)
            title("${pv.slaveName} (escravo)", sTitleTop, pv.slaveName == current)

            fun drawCabin(v: CabinView, rows: List<Int>, top: Float, paired: Set<Int>, fill: Color, onFill: Color) {
                rows.forEachIndexed { i, r ->
                    for (c in 0 until v.cabin.cols) {
                        val id = v.cabin.robotAt(my.robots.core.common.layout.Cell(r, c)) ?: continue
                        val robot = v.robotsById[id] ?: continue
                        val tl = Offset(cellLeft(c), top + i * (ch + rg))
                        drawRoundRect(if (id in paired) fill else idleFill, tl, Size(cw, ch), CornerRadius(8.dp.toPx()))
                        val t = measurer.measure(
                            robot.name,
                            TextStyle(color = if (id in paired) onFill else onIdle, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                            maxLines = 1
                        )
                        drawText(t, topLeft = Offset(tl.x + (cw - t.size.width) / 2, tl.y + (ch - t.size.height) / 2))
                    }
                }
            }
            drawCabin(pv.master, mRows, mTop, pairedMasters, masterFill, onMaster)
            drawCabin(pv.slave, sRows, sTop, pairedSlaves, slaveFill, onSlave)

            val stroke = 2.dp.toPx()
            val head = 7.dp.toPx()
            pv.pairs.forEach { p ->
                val mc = pv.master.cabin.placed[p.master.id] ?: return@forEach
                val sc = pv.slave.cabin.placed[p.slave.id] ?: return@forEach
                val mi = mRows.indexOf(mc.row).takeIf { it >= 0 } ?: return@forEach
                val si = sRows.indexOf(sc.row).takeIf { it >= 0 } ?: return@forEach
                val y1 = mTop + mi * (ch + rg) + ch / 2
                val y2 = sTop + si * (ch + rg) + ch / 2
                val x1 = cellLeft(mc.col)
                val x2 = cellLeft(sc.col)
                val lane = cellLeft(mc.col) - 6.dp.toPx() - laneStep * mi
                val path = Path().apply {
                    moveTo(x1, y1); lineTo(lane, y1); lineTo(lane, y2); lineTo(x2 - head, y2)
                }
                drawPath(path, lineColor, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawCircle(lineColor, radius = 3.dp.toPx(), center = Offset(x1, y1))
                val arrow = Path().apply {
                    moveTo(x2, y2); lineTo(x2 - head, y2 - head * 0.6f); lineTo(x2 - head, y2 + head * 0.6f); close()
                }
                drawPath(arrow, lineColor)
            }
        }
        val outside = pv.pairs.filter { it.master.id !in pv.master.cabin.placed || it.slave.id !in pv.slave.cabin.placed }
        if (outside.isNotEmpty()) {
            Text(
                "Fora do layout: " + outside.joinToString { "${it.master.name} → ${it.slave.name}" },
                style = MaterialTheme.typography.bodySmall,
                color = muted
            )
        }
        Text(
            "Base no escravo: BASE fr_[N]+${pv.offset}",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = muted
        )
    }
}

/** Linhas da grade que têm algum robô, em ordem. */
private fun CabinView.visibleRows(): List<Int> = (0 until cabin.rows).filter { !cabin.isRowEmpty(it) }

/** Texto curto do par para a vaga do robô na grade ("← R10" no escravo, "→ R14" no mestre). */
internal fun pairLabels(pairs: List<MasterSlavePair>, projectName: String): Map<Int, String> {
    val labels = mutableMapOf<Int, String>()
    pairs.forEach { p ->
        if (p.slave.project == projectName) labels[p.slave.id] = "← ${p.master.name}"
        if (p.master.project == projectName) labels[p.master.id] = listOfNotNull(labels[p.master.id], "→ ${p.slave.name}").joinToString(" ")
    }
    return labels
}
