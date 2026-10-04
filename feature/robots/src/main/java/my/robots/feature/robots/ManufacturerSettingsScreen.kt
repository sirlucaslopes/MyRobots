package my.robots.feature.robots

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import my.robots.core.data.ManufacturerSettings
import my.robots.core.designsystem.AppTopBar
import my.robots.core.designsystem.BarAction
import my.robots.core.designsystem.FormDialog
import my.robots.core.model.CommandCategory
import my.robots.core.model.Manufacturer
import my.robots.core.model.RobotCommand

/**
 * Tela "Fabricantes" (⋮ da lista de robôs): o que muda de um fabricante para outro.
 * - A linha de ações escolhe o fabricante.
 * - **Pesquisa rápida**: os termos do botão de lista no campo de pesquisa do editor de
 *   programas. Adicionar (digitando ou tocando numa sugestão), subir/descer, remover e
 *   restaurar o padrão.
 * - **Comandos padrão**: os comandos rápidos que um robô novo desse fabricante recebe.
 *   Adicionar, editar, subir/descer, remover e restaurar.
 * Tudo é gravado na hora (ManufacturerSettings).
 */
@Composable
fun ManufacturerSettingsScreen(settings: ManufacturerSettings, onBack: () -> Unit) {
    var selectedName by rememberSaveable { mutableStateOf(Manufacturer.KAWASAKI.name) }
    val manufacturer = Manufacturer.valueOf(selectedName)
    val terms by settings.searchTerms(manufacturer).collectAsState()
    val commands by settings.defaultCommands(manufacturer).collectAsState()
    var newTerm by remember(selectedName) { mutableStateOf("") }
    var commandToEdit by remember { mutableStateOf<Pair<Int, RobotCommand>?>(null) }
    var confirmReset by remember { mutableStateOf<String?>(null) }

    fun addTerm(t: String) {
        val term = t.trim()
        if (term.isNotEmpty()) settings.setSearchTerms(manufacturer, terms + term)
        newTerm = ""
    }
    fun <T> List<T>.moved(i: Int, d: Int): List<T> {
        val j = i + d
        if (j !in indices) return this
        return toMutableList().also { val x = it[i]; it[i] = it[j]; it[j] = x }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Fabricantes",
                subtitle = manufacturer.displayName,
                onBack = onBack,
                menu = { close ->
                    DropdownMenuItem(
                        text = { Text("Restaurar a pesquisa rápida") },
                        leadingIcon = { Icon(Icons.Default.RestartAlt, null) },
                        onClick = { close(); confirmReset = "termos" }
                    )
                    DropdownMenuItem(
                        text = { Text("Restaurar os comandos padrão") },
                        leadingIcon = { Icon(Icons.Default.RestartAlt, null) },
                        onClick = { close(); confirmReset = "comandos" }
                    )
                },
                actions = Manufacturer.entries.map { m ->
                    BarAction(
                        Icons.Default.PrecisionManufacturing,
                        m.displayName.substringBefore(" "),
                        selected = m == manufacturer,
                        onClick = { selectedName = m.name }
                    )
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ---------- Pesquisa rápida ----------
            item {
                SectionTitle(
                    "Pesquisa rápida do editor",
                    "Termos do botão de lista no campo de pesquisa do editor de programas, nesta ordem." +
                        if (manufacturer != Manufacturer.KAWASAKI) " O editor de código AS usa os da Kawasaki." else ""
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newTerm,
                        onValueChange = { newTerm = it },
                        label = { Text("Novo termo") },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addTerm(newTerm) }),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = { addTerm(newTerm) }, enabled = newTerm.isNotBlank()) { Text("Adicionar") }
                }
            }
            val suggestions = settings.defaultTerms(manufacturer).filter { it !in terms }
            if (suggestions.isNotEmpty()) {
                item {
                    Text("Sugestões (toque para adicionar)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        suggestions.forEach { s ->
                            AssistChip(onClick = { addTerm(s) }, label = { Text(s, fontFamily = FontFamily.Monospace) },
                                leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(16.dp)) })
                        }
                    }
                }
            }
            if (terms.isEmpty()) {
                item { EmptyHint("Nenhum termo. Adicione acima ou restaure o padrão no ⋮.") }
            }
            itemsIndexed(terms, key = { _, t -> "t_$t" }) { i, term ->
                ReorderRow(
                    title = term,
                    subtitle = null,
                    canUp = i > 0,
                    canDown = i < terms.lastIndex,
                    onUp = { settings.setSearchTerms(manufacturer, terms.moved(i, -1)) },
                    onDown = { settings.setSearchTerms(manufacturer, terms.moved(i, 1)) },
                    onDelete = { settings.setSearchTerms(manufacturer, terms - term) }
                )
            }

            // ---------- Comandos padrão ----------
            item {
                Spacer(Modifier.height(12.dp))
                SectionTitle(
                    "Comandos rápidos padrão",
                    "Os comandos que um robô novo deste fabricante recebe ao ser cadastrado. Os de cada robô " +
                        "continuam editáveis no terminal dele."
                )
            }
            item {
                OutlinedButton(onClick = { commandToEdit = -1 to RobotCommand("", "", "", CommandCategory.UTILITY) }) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Adicionar comando")
                }
            }
            if (commands.isEmpty()) {
                item { EmptyHint("Nenhum comando padrão para este fabricante.") }
            }
            itemsIndexed(commands, key = { i, c -> "c_${i}_${c.command}" }) { i, c ->
                ReorderRow(
                    title = c.label,
                    subtitle = c.command,
                    canUp = i > 0,
                    canDown = i < commands.lastIndex,
                    onClick = { commandToEdit = i to c },
                    onUp = { settings.setDefaultCommands(manufacturer, commands.moved(i, -1)) },
                    onDown = { settings.setDefaultCommands(manufacturer, commands.moved(i, 1)) },
                    onDelete = { settings.setDefaultCommands(manufacturer, commands.filterIndexed { j, _ -> j != i }) }
                )
            }
        }
    }

    commandToEdit?.let { (index, cmd) ->
        CommandEditDialog(
            start = cmd,
            isNew = index < 0,
            onDismiss = { commandToEdit = null },
            onSave = { edited ->
                val list = if (index < 0) commands + edited else commands.toMutableList().also { it[index] = edited }
                settings.setDefaultCommands(manufacturer, list)
                commandToEdit = null
            }
        )
    }
    confirmReset?.let { what ->
        AlertDialog(
            onDismissRequest = { confirmReset = null },
            title = { Text(if (what == "termos") "Restaurar a pesquisa rápida?" else "Restaurar os comandos padrão?") },
            text = { Text("Volta para a lista original de ${manufacturer.displayName}. As suas alterações nessa lista serão perdidas.") },
            confirmButton = {
                TextButton(onClick = {
                    if (what == "termos") settings.resetSearchTerms(manufacturer) else settings.resetDefaultCommands(manufacturer)
                    confirmReset = null
                }) { Text("Restaurar") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun SectionTitle(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Linha de uma lista ordenável: texto, subir, descer e remover. */
@Composable
private fun ReorderRow(
    title: String,
    subtitle: String?,
    canUp: Boolean,
    canDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDelete: () -> Unit,
    onClick: (() -> Unit)? = null
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            IconButton(onClick = onUp, enabled = canUp) { Icon(Icons.Default.KeyboardArrowUp, "Subir") }
            IconButton(onClick = onDown, enabled = canDown) { Icon(Icons.Default.KeyboardArrowDown, "Descer") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Remover", tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun CommandEditDialog(start: RobotCommand, isNew: Boolean, onDismiss: () -> Unit, onSave: (RobotCommand) -> Unit) {
    var label by remember { mutableStateOf(start.label) }
    var command by remember { mutableStateOf(start.command) }
    var description by remember { mutableStateOf(start.description) }
    FormDialog(
        title = if (isNew) "Novo comando" else "Editar comando",
        onDismiss = onDismiss,
        confirmButton = {
            Button(
                onClick = { onSave(start.copy(label = label.trim(), command = command.trim(), description = description.trim())) },
                enabled = label.isNotBlank() && command.isNotBlank()
            ) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    ) {
        OutlinedTextField(label, { label = it }, label = { Text("Nome do botão") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            command, { command = it }, label = { Text("Comando") }, singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(description, { description = it }, label = { Text("Explicação (opcional)") }, modifier = Modifier.fillMaxWidth())
    }
}
