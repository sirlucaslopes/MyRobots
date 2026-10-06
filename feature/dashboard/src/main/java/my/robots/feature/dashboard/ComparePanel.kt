package my.robots.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import my.robots.core.common.ascode.AsBackupDiff
import my.robots.core.common.ascode.DiffLine
import my.robots.core.common.ascode.DiffStatus
import my.robots.core.common.ascode.ProgramDiff
import my.robots.core.common.ascode.VarDiff
import my.robots.core.data.DeleteItem
import my.robots.core.designsystem.AppTopBar
import my.robots.core.designsystem.BarAction
import my.robots.core.designsystem.SuccessGreen
import my.robots.core.model.BackupSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val OfflineColor = Color(0xFF42A5F5)
private val RobotColor = Color(0xFFFFA726)
private val WarnColor = Color(0xFFFFB300)

/** Chave de seleção de um programa na comparação (as variáveis usam a [my.robots.core.common.ascode.AsVar.key]). */
internal fun programKey(name: String) = "P:" + name.lowercase()

/**
 * Itens marcados para apagar no robô: programas primeiro (um programa apagado deixa de usar as
 * variáveis), depois as variáveis. Só entra o que está "só no robô" e não é do sistema.
 */
internal fun compareDeleteItems(state: RobotDashboardViewModel.CompareState, selected: Set<String>): List<DeleteItem> {
    val r = state.result ?: return emptyList()
    val programs = r.programs(DiffStatus.ONLY_ROBOT).filter { programKey(it.name) in selected }
        .map { DeleteItem(it.name, AsBackupDiff.deleteProgramCommand(it.name)) }
    val variables = r.variables(DiffStatus.ONLY_ROBOT).filter { it.any.key in selected && !it.any.isSystem }
        .map { DeleteItem(it.name, AsBackupDiff.deleteVariableCommand(it.any)) }
    return programs + variables
}

/**
 * Seção "Comparar" do painel, como a comparação do KIDE: o backup offline (o do app) contra o
 * robô (baixado agora ou um backup escolhido). Mostra os programas e variáveis que estão só no
 * robô (com caixa para apagar no robô), os diferentes (tocar no programa abre as linhas que
 * mudam), os que estão só no offline e quantos são iguais.
 */
@Composable
internal fun ComparePanel(
    state: RobotDashboardViewModel.CompareState,
    backups: List<BackupSummary>,
    offlineId: Int?,
    robotBackupId: Int?,
    onPickOffline: (Int) -> Unit,
    onPickRobot: (Int?) -> Unit,
    onCompare: () -> Unit,
    selected: Set<String>,
    onSelect: (Collection<String>, Boolean) -> Unit,
    onOpenDiff: (ProgramDiff) -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }
    fun label(b: BackupSummary?) = b?.let { "${it.backupName} · ${dateFmt.format(Date(it.timestamp))}" } ?: "—"
    var collapsed by rememberSaveable { mutableStateOf(listOf(DiffStatus.EQUAL.name)) }
    fun toggle(s: DiffStatus) { collapsed = if (s.name in collapsed) collapsed - s.name else collapsed + s.name }
    val result = state.result

    LazyColumn(
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ---------- o que comparar ----------
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SidePicker(
                        title = "OFFLINE",
                        hint = "o backup do app (o que você editou aqui)",
                        color = OfflineColor,
                        current = label(backups.firstOrNull { it.id == offlineId }),
                        options = backups.map { it.id to label(it) },
                        enabled = !state.running,
                        onPick = { onPickOffline(it!!) }
                    )
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, modifier = Modifier.align(Alignment.CenterHorizontally))
                    SidePicker(
                        title = "ROBÔ",
                        hint = "o que está no controlador",
                        color = RobotColor,
                        current = if (robotBackupId == null) "Agora: conecta e baixa (SAVE/FULL)" else label(backups.firstOrNull { it.id == robotBackupId }),
                        options = listOf<Pair<Int?, String>>(null to "Agora: conecta e baixa (SAVE/FULL)") + backups.map { it.id to label(it) },
                        enabled = !state.running,
                        onPick = onPickRobot
                    )
                    Button(onClick = onCompare, enabled = !state.running && offlineId != null, modifier = Modifier.fillMaxWidth()) {
                        Text(if (result == null) "Comparar" else "Comparar de novo")
                    }
                    if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.status?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = if (state.running) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        if (result == null) {
            item {
                Text(
                    "Compara programas e variáveis do backup offline com o que está no robô. O que estiver só no " +
                        "robô pode ser apagado nele; o que estiver só no offline ou diferente vai pelo Enviar das seções " +
                        "Programas e Variáveis.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(4.dp)
                )
            }
            return@LazyColumn
        }

        // ---------- resumo ----------
        item {
            Column(Modifier.padding(horizontal = 4.dp)) {
                Text("${state.offlineName}  →  ${state.robotName}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SummaryLine("Programas", result.programs.groupingBy { it.status }.eachCount())
                SummaryLine("Variáveis", result.variables.groupingBy { it.status }.eachCount())
            }
        }

        listOf(DiffStatus.ONLY_ROBOT, DiffStatus.DIFFERENT, DiffStatus.ONLY_OFFLINE, DiffStatus.EQUAL).forEach { status ->
            val programs = result.programs(status)
            val variables = result.variables(status)
            if (programs.isEmpty() && variables.isEmpty()) return@forEach
            val open = status.name !in collapsed
            val deletable = if (status == DiffStatus.ONLY_ROBOT) {
                programs.map { programKey(it.name) } + variables.filter { !it.any.isSystem }.map { it.any.key }
            } else emptyList()
            item(key = "h_$status") {
                StatusHeader(
                    status = status,
                    count = programs.size + variables.size,
                    open = open,
                    onToggle = { toggle(status) },
                    allMarked = deletable.isNotEmpty() && deletable.all { it in selected },
                    onMarkAll = if (deletable.isEmpty()) null else { mark -> onSelect(deletable, mark) }
                )
            }
            if (!open) return@forEach
            if (status == DiffStatus.EQUAL) {
                item(key = "eq") {
                    val dateOnly = programs.count { it.dateOnly }
                    Text(
                        "${programs.size} programas e ${variables.size} variáveis iguais" +
                            if (dateOnly > 0) " ($dateOnly programas só com a data do cabeçalho diferente)" else "",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
                return@forEach
            }
            items(programs, key = { "p_${status}_${it.name}" }) { p ->
                ProgramRow(
                    diff = p,
                    checked = if (status == DiffStatus.ONLY_ROBOT) programKey(p.name) in selected else null,
                    onCheck = { onSelect(listOf(programKey(p.name)), it) },
                    onOpen = if (status == DiffStatus.DIFFERENT) ({ onOpenDiff(p) }) else null
                )
            }
            items(variables, key = { "v_${status}_${it.any.key}" }) { v ->
                VariableRow(
                    diff = v,
                    checked = if (status == DiffStatus.ONLY_ROBOT && !v.any.isSystem) v.any.key in selected else null,
                    onCheck = { onSelect(listOf(v.any.key), it) }
                )
            }
            if (status == DiffStatus.ONLY_OFFLINE) {
                item(key = "hint_off") {
                    Text(
                        "Estão no app e não no robô: para mandar, use Enviar em Programas ou Variáveis.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SidePicker(
    title: String,
    hint: String,
    color: Color,
    current: String,
    options: List<Pair<Int?, String>>,
    enabled: Boolean,
    onPick: (Int?) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            Text(current, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled) { Text("Trocar") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
                options.forEach { (id, text) ->
                    DropdownMenuItem(text = { Text(text, maxLines = 2) }, onClick = { open = false; onPick(id) })
                }
            }
        }
    }
}

@Composable
private fun SummaryLine(title: String, counts: Map<DiffStatus, Int>) {
    Text(
        "$title: ${counts[DiffStatus.ONLY_ROBOT] ?: 0} só no robô · ${counts[DiffStatus.DIFFERENT] ?: 0} diferentes · " +
            "${counts[DiffStatus.ONLY_OFFLINE] ?: 0} só no offline · ${counts[DiffStatus.EQUAL] ?: 0} iguais",
        style = MaterialTheme.typography.bodyMedium
    )
}

private fun statusTitle(s: DiffStatus) = when (s) {
    DiffStatus.ONLY_ROBOT -> "SÓ NO ROBÔ"
    DiffStatus.DIFFERENT -> "DIFERENTES"
    DiffStatus.ONLY_OFFLINE -> "SÓ NO OFFLINE"
    DiffStatus.EQUAL -> "IGUAIS"
}

private fun statusColor(s: DiffStatus) = when (s) {
    DiffStatus.ONLY_ROBOT -> RobotColor
    DiffStatus.DIFFERENT -> WarnColor
    DiffStatus.ONLY_OFFLINE -> OfflineColor
    DiffStatus.EQUAL -> SuccessGreen
}

@Composable
private fun StatusHeader(
    status: DiffStatus,
    count: Int,
    open: Boolean,
    onToggle: () -> Unit,
    allMarked: Boolean,
    onMarkAll: ((Boolean) -> Unit)?
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(if (open) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("${statusTitle(status)} · $count", color = statusColor(status), fontWeight = FontWeight.Bold)
                if (status == DiffStatus.ONLY_ROBOT) {
                    Text("Não estão no offline: marque para apagar no robô", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (onMarkAll != null) {
                TextButton(onClick = { onMarkAll(!allMarked) }) { Text(if (allMarked) "Desmarcar" else "Marcar todos") }
            }
        }
    }
}

@Composable
private fun ProgramRow(diff: ProgramDiff, checked: Boolean?, onCheck: (Boolean) -> Unit, onOpen: (() -> Unit)?) {
    val p = diff.robot ?: diff.offline!!
    Row(
        Modifier.fillMaxWidth().then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier).padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (checked != null) Checkbox(checked = checked, onCheckedChange = onCheck) else Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text("Programa ${p.name}", fontWeight = FontWeight.SemiBold)
            val detail = when (diff.status) {
                DiffStatus.DIFFERENT -> "${diff.changedLines} ${if (diff.changedLines == 1) "linha muda" else "linhas mudam"} · tocar para ver"
                else -> "${p.body.size} ${if (p.body.size == 1) "linha" else "linhas"}"
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onOpen != null) Icon(Icons.Rounded.ChevronRight, null)
    }
}

@Composable
private fun VariableRow(diff: VarDiff, checked: Boolean?, onCheck: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (checked != null) Checkbox(checked = checked, onCheckedChange = onCheck) else Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                "${diff.kind.label} ${diff.name}" + if (diff.any.isSystem) " (do sistema)" else "",
                fontWeight = FontWeight.SemiBold
            )
            val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            if (diff.status == DiffStatus.DIFFERENT) {
                Text("offline: ${diff.offline!!.value}", style = mono, color = OfflineColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("robô:    ${diff.robot!!.value}", style = mono, color = RobotColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                Text(diff.any.value, style = mono, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * As linhas de um programa diferente: "−" só no offline (vermelho), "+" só no robô (verde),
 * com o número da linha de cada lado. "Só as diferenças" esconde os trechos iguais longe das
 * mudanças (fica um resumo "… N linhas iguais").
 */
@Composable
internal fun ProgramDiffDialog(diff: ProgramDiff, onDismiss: () -> Unit) {
    val lines = remember(diff) { AsBackupDiff.lines(diff.offline?.body.orEmpty(), diff.robot?.body.orEmpty()) }
    var onlyChanges by rememberSaveable { mutableStateOf(true) }
    val shown = remember(lines, onlyChanges) { if (onlyChanges) diffWithContext(lines, 3) else lines.map { it to 0 } }
    val headerChanged = diff.offline?.headerKey != diff.robot?.headerKey

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                AppTopBar(
                    title = diff.name,
                    subtitle = "− só no offline · + só no robô · ${diff.changedLines} linha(s) mudam",
                    onBack = onDismiss,
                    actions = listOf(
                        BarAction(
                            if (onlyChanges) Icons.Rounded.UnfoldMore else Icons.Rounded.UnfoldLess,
                            if (onlyChanges) "Mostrar tudo" else "Só as diferenças",
                            onClick = { onlyChanges = !onlyChanges }
                        )
                    )
                )
            }
        ) { padding ->
            LazyColumn(Modifier.padding(padding).fillMaxSize().navigationBarsPadding()) {
                if (headerChanged) {
                    item {
                        Column(Modifier.padding(8.dp)) {
                            Text("Cabeçalho", fontWeight = FontWeight.Bold)
                            DiffRow(DiffLine(DiffLine.Kind.OFFLINE, diff.offline?.header.orEmpty(), null, null))
                            DiffRow(DiffLine(DiffLine.Kind.ROBOT, diff.robot?.header.orEmpty(), null, null))
                            HorizontalDivider()
                        }
                    }
                }
                items(shown.size) { i ->
                    val (line, skipped) = shown[i]
                    if (skipped > 0) {
                        Text(
                            "… $skipped linhas iguais",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    } else {
                        DiffRow(line)
                    }
                }
            }
        }
    }
}

/** Linhas para mostrar: as mudanças e [context] linhas iguais em volta; o resto vira (linha, N ocultas). */
private fun diffWithContext(lines: List<DiffLine>, context: Int): List<Pair<DiffLine, Int>> {
    val keep = BooleanArray(lines.size)
    lines.forEachIndexed { i, l ->
        if (l.kind != DiffLine.Kind.SAME) for (k in (i - context).coerceAtLeast(0)..(i + context).coerceAtMost(lines.size - 1)) keep[k] = true
    }
    val out = mutableListOf<Pair<DiffLine, Int>>()
    var hidden = 0
    lines.forEachIndexed { i, l ->
        if (keep[i]) {
            if (hidden > 0) { out += l to hidden; hidden = 0 }
            out += l to 0
        } else hidden++
    }
    if (hidden > 0) out += lines.last() to hidden
    return out
}

@Composable
private fun DiffRow(line: DiffLine) {
    val (bg, sign) = when (line.kind) {
        DiffLine.Kind.SAME -> Color.Transparent to " "
        DiffLine.Kind.OFFLINE -> Color(0x33E53935) to "−"
        DiffLine.Kind.ROBOT -> Color(0x3343A047) to "+"
    }
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    Row(Modifier.fillMaxWidth().background(bg).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(line.offlineLine?.toString() ?: "", style = mono, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(36.dp))
        Text(line.robotLine?.toString() ?: "", style = mono, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(36.dp))
        Text(sign, style = mono, fontWeight = FontWeight.Bold, modifier = Modifier.width(14.dp))
        Text(line.text, style = mono)
    }
}

/** Confirmação antes de apagar no robô: a lista dos comandos que vão. */
@Composable
internal fun ConfirmRobotDeleteDialog(items: List<DeleteItem>, robotName: String, note: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apagar ${items.size} item(ns) no $robotName?") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Cada um é apagado sozinho no controlador (sem sub-rotinas nem variáveis de outros programas) " +
                        "e a resposta é conferida. O controlador pergunta e o app confirma.",
                    style = MaterialTheme.typography.bodySmall
                )
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                items.forEach { Text(it.command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Apagar no robô", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

/** Andamento e resultado do apagar no robô, item por item. */
@Composable
internal fun RobotDeleteDialog(state: RobotDashboardViewModel.RobotDeleteState, onDismiss: () -> Unit, onCompareAgain: (() -> Unit)?) {
    AlertDialog(
        onDismissRequest = { if (!state.running) onDismiss() },
        title = { Text("Apagar no robô") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.running) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(state.status)
                }
                state.results.forEach { r ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            if (r.ok) Icons.Rounded.CheckCircle else Icons.Rounded.Error, null,
                            tint = if (r.ok) SuccessGreen else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(r.item.label, fontWeight = FontWeight.SemiBold)
                            Text(r.message, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (onCompareAgain != null && !state.running && state.results.isNotEmpty()) {
                TextButton(onClick = onCompareAgain) { Text("Comparar de novo") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.running) { Text("Fechar") } }
    )
}
