package my.robots.feature.project

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
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
import my.robots.core.common.ascode.AsMasterTransfer
import my.robots.core.designsystem.FormDialog
import my.robots.core.model.Robot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SourceColor = Color(0xFF4FA3FF)
private val NewColor = Color(0xFF81C784)

/**
 * Duplicar um programa em todos os robôs do projeto, em duas etapas.
 *
 * 1. **Escolher:** o programa de ORIGEM (lista do último backup dos robôs, com busca), o NOME
 *    NOVO (sugere o próximo livre: pg100 -> pg101) e, se quiser, um COMENTÁRIO novo para a
 *    cópia. Os robôs do projeto aparecem com a sua caixa.
 * 2. **Conferir:** em cada robô, se o programa de origem existe (linhas, data) e se o nome novo
 *    já existe (SERÁ SUBSTITUÍDO). Caixa por robô para fazer ou não; com avisos, o botão vira
 *    "Duplicar mesmo assim". O resultado de cada robô aparece no mini terminal dele.
 */
@Composable
internal fun DuplicateDialog(
    robots: List<Robot>,
    framePattern: String,
    programs: List<String>?,
    analysis: DuplicateAnalysis?,
    analyzing: Boolean,
    onAnalyze: (List<Robot>, String, String, String?, String?, String?) -> Unit,
    onBackFromAnalysis: () -> Unit,
    onConfirm: (List<Robot>, DuplicateAnalysis) -> Unit,
    onDismiss: () -> Unit
) {
    var chosenRobots by remember { mutableStateOf(robots.map { it.id }.toSet()) }
    var source by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var changeComment by remember { mutableStateOf(false) }
    var comment by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var excluded by remember { mutableStateOf(setOf<Int>()) }
    // frame da base: pré-preenchido pelo padrão (fr_[pgnum]: pg100 -> fr_[100], pg102 -> fr_[102]);
    // editado à mão, deixa de acompanhar o nome
    var copyFrame by remember { mutableStateOf(true) }
    var frameFrom by remember { mutableStateOf("") }
    var frameTo by remember { mutableStateOf("") }
    var frameToEdited by remember { mutableStateOf(false) }
    val frameOk = !copyFrame || (AsMasterTransfer.isValidPoseName(frameFrom) && AsMasterTransfer.isValidPoseName(frameTo) &&
        !frameFrom.equals(frameTo, ignoreCase = true))
    fun suggestFrameTo(name: String) {
        if (!frameToEdited) frameTo = AsMasterTransfer.frameFor(framePattern, name).orEmpty()
    }
    val step2 = analysis != null || analyzing
    val nameOk = GroupAnalysis.isValidProgramName(newName) && !newName.equals(source, ignoreCase = true)

    FormDialog(
        title = if (step2) "Conferir a duplicação" else "Duplicar programa",
        onDismiss = onDismiss,
        confirmButton = {
            if (!step2) {
                Button(
                    onClick = {
                        excluded = emptySet()
                        onAnalyze(
                            robots.filter { it.id in chosenRobots }, source!!, newName, if (changeComment) comment else null,
                            if (copyFrame) frameFrom.trim() else null, if (copyFrame) frameTo.trim() else null
                        )
                    },
                    enabled = source != null && nameOk && frameOk && chosenRobots.isNotEmpty()
                ) { Text("Analisar") }
            } else {
                val a = analysis
                val going = a?.checks?.filter { it.robot.id !in excluded && it.canDo }.orEmpty()
                val warn = going.any { it.target != null || it.backupAt == null } ||
                    (a?.checks?.any { !it.canDo && it.robot.id !in excluded } == true) ||
                    (a?.frameTo != null && going.any { it.frameSource == null || it.frameTarget != null })
                Button(
                    onClick = { onConfirm(going.map { it.robot }, a!!) },
                    enabled = going.isNotEmpty(),
                    colors = if (warn) ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300), contentColor = Color.Black)
                        else ButtonDefaults.buttonColors()
                ) { Text(if (warn) "Duplicar mesmo assim" else "Duplicar") }
            }
        },
        dismissButton = {
            if (step2) TextButton(onClick = onBackFromAnalysis) { Text("Voltar") }
            else TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    ) {
        if (!step2) {
            // ---------- ORIGEM ----------
            Block("ORIGEM", "o programa que será copiado", SourceColor) {
                Text(source ?: "Escolha o programa abaixo", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = filter, onValueChange = { filter = it },
                    label = { Text("Procurar programa") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                when {
                    programs == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Lendo os backups dos robôs…", style = MaterialTheme.typography.bodySmall)
                    }
                    programs.isEmpty() -> Text("Os robôs ainda não têm backup com programas.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    else -> {
                        val shown = programs.filter { it.contains(filter.trim(), ignoreCase = true) }
                        shown.take(MAX_PROGRAMS_SHOWN).forEach { name ->
                            val pick = {
                                source = name
                                newName = GroupAnalysis.nextFreeName(name, programs)
                                frameFrom = AsMasterTransfer.frameFor(framePattern, name).orEmpty()
                                frameToEdited = false
                                suggestFrameTo(newName)
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { pick() }
                            ) {
                                RadioButton(selected = name == source, onClick = pick)
                                Text(name, fontFamily = FontFamily.Monospace)
                            }
                        }
                        if (shown.size > MAX_PROGRAMS_SHOWN) {
                            Text("Mais ${shown.size - MAX_PROGRAMS_SHOWN}: procure pelo nome.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            // ---------- CÓPIA ----------
            Block("CÓPIA", "o programa novo", NewColor) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it.trim(); suggestFrameTo(newName) },
                    label = { Text("Nome novo") },
                    isError = newName.isNotEmpty() && !nameOk,
                    supportingText = {
                        Text(
                            when {
                                newName.isEmpty() -> "Sugerido o próximo número livre."
                                !GroupAnalysis.isValidProgramName(newName) -> "Começa com letra; até 15 letras, números, _ ou ."
                                newName.equals(source, ignoreCase = true) -> "Igual ao de origem."
                                else -> "Começa com letra; até 15 caracteres."
                            }
                        )
                    },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { changeComment = !changeComment }
                ) {
                    Checkbox(checked = changeComment, onCheckedChange = { changeComment = it })
                    Text("Trocar o comentário do programa")
                }
                if (changeComment) {
                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it },
                        label = { Text("Comentário novo (vazio = sem comentário)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text("A cópia mantém o comentário do programa de origem.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // ---------- FRAME DA BASE ----------
            Block("FRAME DA BASE", "a base (.TRANS) do programa", Color(0xFFFFB74D)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { copyFrame = !copyFrame }
                ) {
                    Checkbox(checked = copyFrame, onCheckedChange = { copyFrame = it })
                    Text("Copiar o frame para um novo")
                }
                if (copyFrame) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = frameFrom,
                            onValueChange = { frameFrom = it.trim() },
                            label = { Text("Frame de origem") },
                            isError = frameFrom.isNotEmpty() && !AsMasterTransfer.isValidPoseName(frameFrom),
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(1f)
                        )
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null)
                        OutlinedTextField(
                            value = frameTo,
                            onValueChange = { frameTo = it.trim(); frameToEdited = true },
                            label = { Text("Frame da cópia") },
                            isError = frameTo.isNotEmpty() && (!AsMasterTransfer.isValidPoseName(frameTo) || frameTo.equals(frameFrom, ignoreCase = true)),
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        "No programa copiado, \"$frameFrom\" vira \"$frameTo\", e a linha dele na .TRANS vai junto com o nome novo. " +
                            "Nome de variável: letras, números, _ e . com índice opcional, ex.: fr_[102].",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("A cópia usa o mesmo frame do programa de origem.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // ---------- ROBÔS ----------
            Text("Robôs (${chosenRobots.size} de ${robots.size})", style = MaterialTheme.typography.labelLarge)
            robots.forEach { r ->
                CheckRow(
                    checked = r.id in chosenRobots, text = r.name,
                    onToggle = { chosenRobots = if (r.id in chosenRobots) chosenRobots - r.id else chosenRobots + r.id }
                )
            }
        } else {
            DuplicateAnalysisView(analysis, excluded) { id -> excluded = if (id in excluded) excluded - id else excluded + id }
        }
    }
}

@Composable
private fun Block(title: String, subtitle: String, color: Color, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = color.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = color)
                Spacer(Modifier.width(8.dp))
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
private fun DuplicateAnalysisView(analysis: DuplicateAnalysis?, excluded: Set<Int>, onToggle: (Int) -> Unit) {
    if (analysis == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Conferindo os backups dos robôs…", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val going = analysis.checks.filter { it.robot.id !in excluded && it.canDo }
    val replaced = going.count { it.target != null }
    val missing = analysis.checks.count { !it.canDo }
    Surface(
        color = if (replaced + missing > 0) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(analysis.source, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = SourceColor)
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.padding(horizontal = 8.dp).size(18.dp))
                Text(analysis.newName, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = NewColor)
            }
            Text("Duplicar em ${going.size} de ${analysis.checks.size} robô(s)", fontWeight = FontWeight.SemiBold)
            Text(
                analysis.comment?.let { if (it.isBlank()) "Cópia sem comentário" else "Comentário da cópia: $it" } ?: "Cópia com o comentário da origem",
                style = MaterialTheme.typography.bodySmall
            )
            if (replaced > 0) Text("$replaced robô(s) já têm ${analysis.newName}: SERÁ SUBSTITUÍDO", style = MaterialTheme.typography.bodySmall)
            if (missing > 0) Text("$missing robô(s) não têm ${analysis.source}: ficam de fora", style = MaterialTheme.typography.bodySmall)
            Text("Desmarque um robô para ele ficar de fora.", style = MaterialTheme.typography.bodySmall)
        }
    }
    analysis.checks.forEach { c ->
        val on = c.robot.id !in excluded && c.canDo
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = on, onCheckedChange = { onToggle(c.robot.id) }, enabled = c.canDo)
                    Text(c.robot.name, fontWeight = FontWeight.Bold)
                    Text("  backup de ${fmtDate(c.backupAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Line(
                        "Origem", SourceColor,
                        if (c.source != null) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
                        if (c.source != null) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                        when {
                            c.backupAt == null -> "sem backup: não dá para duplicar"
                            c.source == null -> "${analysis.source} não existe: fica de fora"
                            else -> "${analysis.source} · ${describeDup(c.source)}"
                        }
                    )
                    if (c.canDo && analysis.frameFrom != null && analysis.frameTo != null) {
                        Line(
                            "Frame", Color(0xFFFFB74D),
                            when {
                                c.frameSource == null -> Icons.Rounded.Warning
                                c.frameTarget != null -> Icons.Rounded.Warning
                                else -> Icons.Rounded.CheckCircle
                            },
                            if (c.frameSource == null || c.frameTarget != null) Color(0xFFFFB300) else Color(0xFF4CAF50),
                            when {
                                c.frameSource == null -> "${analysis.frameFrom} não existe: frame não é copiado"
                                c.frameTarget != null -> "${analysis.frameFrom} → ${analysis.frameTo} (usado ${c.frameUses}x no programa); " +
                                    "${analysis.frameTo} existe: SERÁ SOBRESCRITO"
                                else -> "${analysis.frameFrom} → ${analysis.frameTo} (usado ${c.frameUses}x no programa): será criado"
                            }
                        )
                        if (c.frameSource != null) Text("        " + c.frameSource, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1)
                    }
                    if (c.canDo) {
                        Line(
                            "Cópia", NewColor,
                            if (c.target != null) Icons.Rounded.Warning else Icons.Rounded.CheckCircle,
                            if (c.target != null) Color(0xFFFFB300) else Color(0xFF4CAF50),
                            if (c.target != null) "${analysis.newName} existe (${describeDup(c.target)}): SERÁ SUBSTITUÍDO"
                            else "${analysis.newName} não existe: será criado"
                        )
                    }
                }
            }
        }
    }
    Text(
        "A conferência usa o último backup de cada robô. Para ter certeza, use o Atualizar no painel deles antes.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun Line(label: String, color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.width(52.dp))
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

private fun describeDup(p: ProgramState): String =
    listOfNotNull("${p.lines} linhas", p.modifiedAt.takeIf { it.isNotBlank() }, p.comment.takeIf { it.isNotBlank() }?.take(30)).joinToString(" · ")

private fun fmtDate(t: Long?): String = t?.let { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(it)) } ?: "nenhum"
