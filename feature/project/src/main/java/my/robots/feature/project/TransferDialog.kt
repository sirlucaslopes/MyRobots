package my.robots.feature.project

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import my.robots.core.data.MasterSlaveConfig
import my.robots.core.data.ProjectOperations.MasterSlavePair
import my.robots.core.designsystem.FormDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val OriginColor = Color(0xFF4FA3FF)
private val TargetColor = Color(0xFFFFB74D)

/**
 * Transferência mestre -> escravo em duas etapas.
 *
 * 1. **Escolher:** a ORIGEM (projeto e robôs mestres, de onde os programas saem) e o DESTINO
 *    (projeto e robôs escravos, que recebem) em blocos separados, cada par com a sua caixa; os
 *    programas (do último backup dos mestres, com busca) e as opções (alterar a base, enviar a
 *    .TRANS). "Analisar" passa para a etapa 2.
 * 2. **Conferir:** para cada par e programa, o que o último backup da origem tem (existe?
 *    linhas, data) e o do destino (não existe: será criado; existe: SERÁ SUBSTITUÍDO, porque o
 *    LOAD do controlador troca o programa sem perguntar). Com avisos, o botão vira
 *    "Transferir mesmo assim"; "Voltar" muda a escolha.
 */
@Composable
internal fun TransferDialog(
    pairs: List<MasterSlavePair>,
    configs: Map<String, MasterSlaveConfig>,
    offsets: Map<String, String>,
    programs: List<String>?,
    analysis: TransferAnalysis?,
    analyzing: Boolean,
    onAnalyze: (List<MasterSlavePair>, List<String>, Boolean, Boolean) -> Unit,
    onBackFromAnalysis: () -> Unit,
    onConfirm: (List<MasterSlavePair>, List<String>, Boolean, Boolean) -> Unit,
    onDismiss: () -> Unit,
    crossLine: Boolean = false
) {
    var chosenPairs by remember { mutableStateOf(pairs.map { it.slave.id }.toSet()) }
    var chosenPrograms by remember { mutableStateOf(listOf<String>()) }
    val config = configs[pairs.firstOrNull()?.slave?.project] ?: MasterSlaveConfig()
    var withFrames by remember { mutableStateOf(config.sendFrame) }
    var applyOffset by remember { mutableStateOf(config.applyOffset) }
    var filter by remember { mutableStateOf("") }
    // na conferência: robôs de destino que ficam de fora (desmarcados)
    var excluded by remember { mutableStateOf(setOf<Int>()) }
    val offsetText = offsets.values.distinct().joinToString(" / ").ifBlank { "top_offset" }
    val pattern = config.framePattern.ifBlank { "todas as bases" }
    val selected = pairs.filter { it.slave.id in chosenPairs }
    val step2 = analysis != null || analyzing

    FormDialog(
        title = if (step2) "Conferir a transferência" else "Mestre → escravo",
        onDismiss = onDismiss,
        confirmButton = {
            if (!step2) {
                Button(
                    onClick = { excluded = emptySet(); onAnalyze(selected, chosenPrograms, applyOffset, withFrames) },
                    enabled = selected.isNotEmpty() && chosenPrograms.isNotEmpty()
                ) { Text("Analisar") }
            } else {
                val a = analysis
                val going = a?.pairs?.filter { it.pair.slave.id !in excluded && it.toSend.isNotEmpty() }.orEmpty()
                // aviso: algo vai ser substituído/sobrescrito, falta na origem ou o destino não tem backup
                val warn = a != null && a.pairs.filter { it.pair.slave.id !in excluded }.any {
                    it.replaced.isNotEmpty() || it.missing.isNotEmpty() || it.targetBackupAt == null || (a.withFrames && it.framesOverwritten > 0)
                }
                Button(
                    onClick = { onConfirm(going.map { it.pair }, chosenPrograms, withFrames, applyOffset) },
                    enabled = going.isNotEmpty(),
                    colors = if (warn) ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300), contentColor = Color.Black)
                        else ButtonDefaults.buttonColors()
                ) { Text(if (warn) "Transferir mesmo assim" else "Transferir") }
            }
        },
        dismissButton = {
            if (step2) TextButton(onClick = onBackFromAnalysis) { Text("Voltar") }
            else TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    ) {
        // origem e destino em linhas de produção diferentes (fora do mesmo processo)
        if (crossLine) {
            Surface(color = Color(0xFFFFB300), shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Origem e destino em linhas diferentes",
                    color = Color.Black,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
        if (!step2) {
            // ---------- 1. ORIGEM ----------
            SideBlock("ORIGEM (mestre)", "de onde os programas saem", OriginColor) {
                Text(pairs.map { it.master.project }.distinct().joinToString(), fontWeight = FontWeight.SemiBold)
                Text(
                    "Robôs: " + pairs.map { it.master.name }.distinct().joinToString(),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Os programas saem do último backup de cada robô de origem.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // ---------- 2. DESTINO ----------
            SideBlock("DESTINO (escravo)", "quem recebe os programas", TargetColor) {
                Text(pairs.map { it.slave.project }.distinct().joinToString(), fontWeight = FontWeight.SemiBold)
                pairs.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = p.slave.id in chosenPairs,
                            onCheckedChange = { chosenPairs = if (p.slave.id in chosenPairs) chosenPairs - p.slave.id else chosenPairs + p.slave.id }
                        )
                        Text(p.slave.name, fontWeight = FontWeight.Bold, color = TargetColor)
                        Text("  recebe de  ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(p.master.name, fontWeight = FontWeight.Bold, color = OriginColor)
                    }
                }
            }

            // ---------- 3. Opções ----------
            Text("Opções", style = MaterialTheme.typography.labelLarge)
            CheckRow(checked = applyOffset, text = "Alterar a base (somar o offset)", onToggle = { applyOffset = !applyOffset })
            Text(
                if (applyOffset) "No destino, as bases \"$pattern\" ganham \"+$offsetText\" (ex.: BASE fr_[100] → BASE fr_[100]+$offsetText)."
                else "Os programas vão para o destino exatamente como estão na origem.",
                style = MaterialTheme.typography.bodySmall,
                color = if (applyOffset) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(start = 48.dp)
            )
            CheckRow(checked = withFrames, text = "Enviar a base (.TRANS) junto", onToggle = { withFrames = !withFrames })
            Text(
                if (withFrames) "Vão junto as linhas \"$pattern\" da .TRANS da origem."
                else "A .TRANS não vai; o destino usa o frame que já tem.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 48.dp)
            )

            // ---------- 4. Programas ----------
            Text("Programas (${chosenPrograms.size})", style = MaterialTheme.typography.labelLarge)
            if (chosenPrograms.isNotEmpty()) {
                Text(chosenPrograms.joinToString(), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.primary)
            }
            OutlinedTextField(
                value = filter, onValueChange = { filter = it },
                label = { Text("Procurar programa") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            when {
                programs == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Lendo os backups da origem…", style = MaterialTheme.typography.bodySmall)
                }
                programs.isEmpty() -> Text(
                    "Os robôs de origem ainda não têm backup com programas. Faça um backup deles primeiro.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
                )
                else -> {
                    val shown = programs.filter { it.contains(filter.trim(), ignoreCase = true) }
                    shown.take(MAX_PROGRAMS_SHOWN).forEach { name ->
                        CheckRow(
                            checked = name in chosenPrograms, text = name,
                            onToggle = { chosenPrograms = if (name in chosenPrograms) chosenPrograms - name else chosenPrograms + name }
                        )
                    }
                    if (shown.size > MAX_PROGRAMS_SHOWN) {
                        Text("Mais ${shown.size - MAX_PROGRAMS_SHOWN}: procure pelo nome.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            AnalysisView(analysis, excluded) { id -> excluded = if (id in excluded) excluded - id else excluded + id }
        }
    }
}

/** Bloco com faixa colorida à esquerda: ORIGEM em azul, DESTINO em laranja. */
@Composable
private fun SideBlock(title: String, subtitle: String, color: Color, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = color.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = color)
                Spacer(Modifier.width(8.dp))
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

/**
 * Etapa 2: resumo e, por par (com a caixa para mandar ou não), cada programa na origem e no
 * destino, as linhas BASE que mudam e os frames da .TRANS.
 */
@Composable
private fun AnalysisView(analysis: TransferAnalysis?, excluded: Set<Int>, onToggle: (Int) -> Unit) {
    if (analysis == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Conferindo os backups da origem e do destino…", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val going = analysis.pairs.filter { it.pair.slave.id !in excluded }
    val replaced = going.sumOf { it.replaced.size }
    val missing = going.sumOf { it.missing.size }
    val unknown = going.count { it.targetBackupAt == null }
    val framesOver = if (analysis.withFrames) going.sumOf { it.framesOverwritten } else 0
    val warn = replaced + missing + unknown + framesOver > 0
    Surface(
        color = if (warn) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${going.sumOf { it.toSend.size }} envio(s) para ${going.size} de ${analysis.pairs.size} robô(s)", fontWeight = FontWeight.SemiBold)
            if (replaced > 0) Text("$replaced programa(s) já existe(m) no destino: SERÁ(ÃO) SUBSTITUÍDO(S)", style = MaterialTheme.typography.bodySmall)
            if (framesOver > 0) Text("$framesOver frame(s) da .TRANS já existe(m) no destino com outro valor: será(ão) sobrescrito(s)", style = MaterialTheme.typography.bodySmall)
            if (missing > 0) Text("$missing programa(s) não existe(m) na origem e não vai(ão)", style = MaterialTheme.typography.bodySmall)
            if (unknown > 0) Text("$unknown destino(s) sem backup: não dá para saber o que já existe lá", style = MaterialTheme.typography.bodySmall)
            Text(
                listOf(
                    if (analysis.applyOffset) "base + ${analysis.offset}" else "base sem alteração",
                    if (analysis.withFrames) ".TRANS junto" else "sem .TRANS"
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall
            )
            Text("Desmarque um robô para ele ficar de fora.", style = MaterialTheme.typography.bodySmall)
        }
    }
    analysis.pairs.forEach { pa ->
        val on = pa.pair.slave.id !in excluded
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = on, onCheckedChange = { onToggle(pa.pair.slave.id) }, enabled = pa.toSend.isNotEmpty())
                    Text(pa.pair.master.name, fontWeight = FontWeight.Bold, color = OriginColor)
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.padding(horizontal = 8.dp).size(18.dp))
                    Text(pa.pair.slave.name, fontWeight = FontWeight.Bold, color = TargetColor)
                    if (!on) Text("  (fica de fora)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!on) return@Column
                Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Backup da origem: ${fmt(pa.originBackupAt)} · do destino: ${fmt(pa.targetBackupAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    pa.checks.forEach { c -> ProgramCheckView(c, pa, analysis) }
                }
            }
        }
    }
    Text(
        "A conferência usa o último backup de cada robô. Para ter certeza, use o Atualizar no painel dos destinos antes.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Um programa: origem, destino, as linhas BASE que mudam e os frames da .TRANS. */
@Composable
private fun ProgramCheckView(c: ProgramCheck, pa: PairAnalysis, analysis: TransferAnalysis) {
    val o = c.origin
    val t = c.target
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(c.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        StatusLine(
            label = "Origem", color = OriginColor,
            icon = if (o != null) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
            iconTint = if (o != null) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
            text = when {
                pa.originBackupAt == null -> "sem backup: não vai"
                o == null -> "não existe: não vai"
                else -> describe(o)
            }
        )
        StatusLine(
            label = "Destino", color = TargetColor,
            icon = when {
                o == null -> Icons.Rounded.Error
                pa.targetBackupAt == null || t != null -> Icons.Rounded.Warning
                else -> Icons.Rounded.CheckCircle
            },
            iconTint = when {
                o == null -> MaterialTheme.colorScheme.onSurfaceVariant
                pa.targetBackupAt == null || t != null -> Color(0xFFFFB300)
                else -> Color(0xFF4CAF50)
            },
            text = when {
                o == null -> "nada a fazer"
                pa.targetBackupAt == null -> "sem backup: não dá para saber se já existe"
                t == null -> "não existe: será criado"
                else -> "existe (${describe(t)}): SERÁ SUBSTITUÍDO"
            }
        )
        if (o == null) return@Column
        // mudança na base
        if (analysis.applyOffset) {
            if (c.baseChanges.isEmpty()) {
                DetailLine("Base", "nenhuma linha BASE do padrão: fica igual")
            } else {
                c.baseChanges.take(4).forEach { (old, new) -> DetailLine("Base", "$old  →  $new", mono = true) }
                if (c.baseChanges.size > 4) DetailLine("Base", "mais ${c.baseChanges.size - 4} linha(s)")
            }
        }
        // frames da .TRANS
        if (analysis.withFrames) {
            if (c.frames.isEmpty()) DetailLine(".TRANS", "nenhum frame do padrão nas bases: nada vai junto")
            c.frames.forEach { f ->
                DetailLine(
                    ".TRANS",
                    when {
                        f.origin == null -> "${f.name}: não existe na origem (não vai)"
                        pa.targetBackupAt == null -> "${f.name}: vai junto (destino sem backup)"
                        f.target == null -> "${f.name}: vai junto (não existe no destino)"
                        f.target.trim() == f.origin.trim() -> "${f.name}: igual no destino"
                        else -> "${f.name}: existe no destino com outro valor: SERÁ SOBRESCRITO"
                    }
                )
                if (f.origin != null) DetailLine("", f.origin, mono = true)
                if (f.target != null && f.origin != null && f.target.trim() != f.origin.trim()) DetailLine("destino", f.target, mono = true)
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, text: String, mono: Boolean = false) {
    Row {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) FontFamily.Monospace else null,
            maxLines = 2
        )
    }
}

@Composable
private fun StatusLine(label: String, color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, iconTint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.width(56.dp))
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

private fun describe(p: ProgramState): String =
    listOfNotNull("${p.lines} linhas", p.modifiedAt.takeIf { it.isNotBlank() }, p.comment.takeIf { it.isNotBlank() }?.take(30))
        .joinToString(" · ")

private fun fmt(t: Long?): String = t?.let { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(it)) } ?: "nenhum"
