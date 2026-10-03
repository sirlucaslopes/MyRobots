package my.robots.feature.codeeditor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import my.robots.core.common.ascode.AsInstructions
import my.robots.core.common.ascode.InstructionDef
import my.robots.core.common.ascode.ParamKind
import my.robots.core.common.ascode.ParsedInstruction
import my.robots.core.designsystem.FormDialog

/**
 * "Alterar" de uma instrução conhecida, como o CHANGE do teach pendant: mostra a instrução,
 * as outras do mesmo grupo (para trocar, levando os valores) e um campo por parâmetro. Embaixo,
 * a linha como vai ficar. "Editar como texto" abre a edição livre da linha.
 *
 * Usado também para "Inserir" uma instrução nova: aí [start] vem com os valores padrão.
 */
@Composable
fun InstructionEditDialog(
    title: String,
    start: ParsedInstruction,
    onEditAsText: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var def by remember { mutableStateOf(start.def) }
    val values = remember { mutableStateListOf(*start.values.toTypedArray()) }

    fun switchTo(newDef: InstructionDef) {
        val carried = AsInstructions.carryValues(start.copy(def = def, values = values.toList()), newDef)
        values.clear()
        values.addAll(carried)
        def = newDef
    }

    val preview = start.render(def, values.toList())
    val siblings = AsInstructions.inGroup(def.group)

    FormDialog(
        title = title,
        onDismiss = onDismiss,
        content = {
            Text(def.group, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (siblings.size > 1) {
                // as outras instruções do grupo, como as opções do pendant
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    siblings.forEach { s ->
                        // o nome da instrução (curto, como no pendant); a descrição vai embaixo
                        val chipText = if (s.keyword == "LMOVE") s.label.removePrefix("Linear ") else s.keyword
                        FilterChip(selected = s === def, onClick = { if (s !== def) switchTo(s) }, label = { Text(chipText) })
                    }
                }
            }
            Text(def.label, style = MaterialTheme.typography.titleMedium)
            InstructionFields(def, values)
            Text("Como vai ficar", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                preview.trim(),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.horizontalScroll(rememberScrollState())
            )
            TextButton(onClick = onEditAsText) { Text("Editar como texto") }
        },
        confirmButton = { Button(onClick = { onSave(preview) }) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

/** Um campo por parâmetro: ON/OFF vira dois botões; número abre o teclado numérico. */
@Composable
private fun InstructionFields(def: InstructionDef, values: MutableList<String>) {
    def.params.forEachIndexed { i, p ->
        when (p.kind) {
            ParamKind.ON_OFF -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.label, modifier = Modifier.weight(1f))
                listOf("ON", "OFF").forEach { opt ->
                    FilterChip(
                        selected = values[i].equals(opt, ignoreCase = true),
                        onClick = { values[i] = opt },
                        label = { Text(opt) },
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            else -> OutlinedTextField(
                value = values[i],
                onValueChange = { values[i] = it },
                label = { Text(p.label) },
                suffix = if (p.unit.isNotEmpty()) ({ Text(p.unit) }) else null,
                singleLine = true,
                keyboardOptions = if (p.kind == ParamKind.NUMBER) KeyboardOptions(keyboardType = KeyboardType.Decimal)
                else KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * "Inserir": escolher o grupo e a instrução (como a lista de instruções do pendant), ou "Texto
 * livre" para digitar a linha. [onPick] recebe a instrução com os valores padrão.
 */
@Composable
fun InstructionPickerDialog(
    onPick: (InstructionDef) -> Unit,
    onFreeText: () -> Unit,
    onDismiss: () -> Unit
) {
    var group by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = if (group == null) "Inserir instrução" else group!!,
        onDismiss = onDismiss,
        content = {
            if (group == null) {
                AsInstructions.groups.forEach { g ->
                    PickerRow(g, AsInstructions.inGroup(g).joinToString(", ") { it.keyword }.let { if (it.length > 60) it.take(57) + "…" else it }) { group = g }
                }
                PickerRow("Texto livre", "Digitar a linha inteira") { onFreeText() }
            } else {
                AsInstructions.inGroup(group!!).forEach { d -> PickerRow(d.label, d.keyword) { onPick(d) } }
                TextButton(onClick = { group = null }) { Text("Voltar aos grupos") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun PickerRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
