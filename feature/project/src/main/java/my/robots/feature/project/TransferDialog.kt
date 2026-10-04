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
    onAnalyze: (List<MasterSlavePair>, List<String>) -> Unit,
    onBackFromAnalysis: () -> Unit,
    onConfirm: (List<MasterSlavePair>, List<String>, Boolean, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var chosenPairs by remember { mutableStateOf(pairs.map { it.slave.id }.toSet()) }
    var chosenPrograms by remember { mutableStateOf(listOf<String>()) }
    val config = configs[pairs.firstOrNull()?.slave?.project] ?: MasterSlaveConfig()
    var withFrames by remember { mutableStateOf(config.sendFrame) }
    var applyOffset by remember { mutableStateOf(config.applyOffset) }
    var filter by remember { mutableStateOf("") }
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
                    onClick = { onAnalyze(selected, chosenPrograms) },
                    enabled = selected.isNotEmpty() && chosenPrograms.isNotEmpty()
                ) { Text("Analisar") }
            } else {
                val a = analysis
                Button(
                    onClick = { onConfirm(selected, chosenPrograms, withFrames, applyOffset) },
                    enabled = a != null && a.pairs.any { it.toSend.isNotEmpty() },
                    colors = if (a?.hasWarnings == true) ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300), contentColor = Color.Black)
                        else ButtonDefaults.buttonColors()
                ) { Text(if (a?.hasWarnings == true) "Transferir mesmo assim" else "Transferir") }
            }
        },
        dismissButton = {
            if (step2) TextButton(onClick = onBackFromAnalysis) { Text("Voltar") }
            else TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    ) {
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
            AnalysisView(analysis, applyOffset, withFrames)
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

/** Etapa 2: resumo e, por par, cada programa na origem e no destino. */
@Composable
private fun AnalysisView(analysis: TransferAnalysis?, applyOffset: Boolean, withFrames: Boolean) {
    if (analysis == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Conferindo os backups da origem e do destino…", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    // resumo
    val toSend = analysis.pairs.sumOf { it.toSend.size }
    Surface(
        color = if (analysis.hasWarnings) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("$toSend envio(s) em ${analysis.pairs.size} robô(s) de destino", fontWeight = FontWeight.SemiBold)
            if (analysis.replacedCount > 0) Text("${analysis.replacedCount} programa(s) já existe(m) no destino e será(ão) SUBSTITUÍDO(S)", style = MaterialTheme.typography.bodySmall)
            if (analysis.missingCount > 0) Text("${analysis.missingCount} programa(s) não existe(m) na origem e não vai(ão)", style = MaterialTheme.typography.bodySmall)
            if (analysis.unknownTarget > 0) Text("${analysis.unknownTarget} destino(s) sem backup: não dá para saber se o programa já existe lá", style = MaterialTheme.typography.bodySmall)
            Text(
                listOf(if (applyOffset) "base + offset" else "base sem alteração", if (withFrames) ".TRANS junto" else "sem .TRANS").joinToString(" · "),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    analysis.pairs.forEach { pa ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pa.pair.master.name, fontWeight = FontWeight.Bold, color = OriginColor)
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.padding(horizontal = 8.dp).size(18.dp))
                    Text(pa.pair.slave.name, fontWeight = FontWeight.Bold, color = TargetColor)
                }
                Text(
                    "Backup da origem: ${fmt(pa.origin.backupAt)} · do destino: ${fmt(pa.target.backupAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                analysis.programs.forEach { name ->
                    val o = pa.origin.programs[name]
                    val t = pa.target.programs[name]
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                        StatusLine(
                            label = "Origem",
                            color = OriginColor,
                            icon = if (o != null) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
                            iconTint = if (o != null) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                            text = when {
                                pa.origin.backupAt == null -> "sem backup: não vai"
                                o == null -> "não existe: não vai"
                                else -> describe(o)
                            }
                        )
                        StatusLine(
                            label = "Destino",
                            color = TargetColor,
                            icon = when {
                                o == null -> Icons.Rounded.Error
                                pa.target.backupAt == null || t != null -> Icons.Rounded.Warning
                                else -> Icons.Rounded.CheckCircle
                            },
                            iconTint = when {
                                o == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                pa.target.backupAt == null || t != null -> Color(0xFFFFB300)
                                else -> Color(0xFF4CAF50)
                            },
                            text = when {
                                o == null -> "nada a fazer"
                                pa.target.backupAt == null -> "sem backup: não dá para saber se já existe"
                                t == null -> "não existe: será criado"
                                else -> "existe (${describe(t)}): SERÁ SUBSTITUÍDO"
                            }
                        )
                    }
                }
            }
        }
    }
    Text(
        "A conferência usa o último backup de cada robô. Para ter certeza, faça o backup dos destinos antes.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
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
