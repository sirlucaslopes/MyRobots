package my.robots.feature.project

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import my.robots.core.common.ascode.AsMasterTransfer
import my.robots.core.data.MasterSlaveConfig
import my.robots.core.designsystem.ActionTone
import my.robots.core.designsystem.AppTopBar
import my.robots.core.designsystem.BarAction
import my.robots.core.designsystem.FormDialog
import my.robots.core.model.Robot

/**
 * Tela "Mestre / Escravo" (⋮ da lista de robôs ou da tela de Projeto): todas as configurações
 * de transferência entre projetos, uma por cartão. Em cada uma: o projeto de origem (mestre)
 * e o de destino (escravo), o robô de origem de cada robô de destino, se a base do programa de
 * destino recebe o offset (e o nome da variável), se a base (.TRANS) vai junto e o nome do
 * frame da base com "pgnum" no lugar do número do programa.
 */
@Composable
fun MasterSlaveScreen(viewModel: MasterSlaveViewModel, onBack: () -> Unit) {
    val setups by viewModel.setups.collectAsState()
    val projects by viewModel.projects.collectAsState()
    val robots by viewModel.robots.collectAsState()
    var showNew by remember { mutableStateOf(false) }
    var toRemove by remember { mutableStateOf<SlaveSetup?>(null) }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Mestre / Escravo",
                subtitle = when (setups.size) {
                    0 -> "Nenhuma configuração"
                    1 -> "1 configuração"
                    else -> "${setups.size} configurações"
                },
                onBack = onBack,
                actions = listOf(
                    BarAction(Icons.Rounded.Add, "Nova", tone = ActionTone.Primary, enabled = projects.size >= 2, onClick = { showNew = true })
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (setups.isEmpty()) {
                item {
                    Text(
                        "Nenhum projeto escravo ainda. Toque em \"Nova\" e escolha o projeto de origem (ex.: Primer) " +
                            "e o de destino (ex.: Top Coat).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(setups, key = { it.slaveProject }) { setup ->
                SetupCard(
                    setup = setup,
                    projects = projects,
                    robots = robots,
                    pairByPosition = { master -> viewModel.pairByPosition(setup.slaveProject, master) },
                    onSave = viewModel::save,
                    onRemove = { toRemove = setup }
                )
            }
        }
    }

    if (showNew) {
        NewSetupDialog(
            projects = projects,
            taken = setups.map { it.slaveProject }.toSet(),
            onCreate = { slave, master -> showNew = false; viewModel.create(slave, master) },
            onDismiss = { showNew = false }
        )
    }
    toRemove?.let { s ->
        AlertDialog(
            onDismissRequest = { toRemove = null },
            title = { Text("Remover ${s.masterProject} → ${s.slaveProject}?") },
            text = { Text("O ${s.slaveProject} deixa de ter mestre e os robôs dele perdem o par. Os programas nos robôs não mudam.") },
            confirmButton = { TextButton(onClick = { viewModel.remove(s); toRemove = null }) { Text("Remover") } },
            dismissButton = { TextButton(onClick = { toRemove = null }) { Text("Cancelar") } }
        )
    }
}

/** Cartão de uma configuração, editável; "Salvar" grava as mudanças. */
@Composable
private fun SetupCard(
    setup: SlaveSetup,
    projects: List<String>,
    robots: List<Robot>,
    pairByPosition: (String) -> Map<Int, Int?>,
    onSave: (SlaveSetup) -> Unit,
    onRemove: () -> Unit
) {
    var draft by remember(setup) { mutableStateOf(setup) }
    var menuOpen by remember { mutableStateOf(false) }
    val slaveRobots = robots.filter { it.project == setup.slaveProject }.sortedBy { it.name }
    val masterRobots = robots.filter { it.project == draft.masterProject }.sortedBy { it.name }
    val changed = draft != setup

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // título: origem -> destino
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Origem → destino", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${draft.masterProject} → ${setup.slaveProject}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, "Mais opções") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Remover configuração", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onRemove() }
                        )
                    }
                }
            }

            PickerButton(
                label = "Projeto de origem (mestre)",
                value = draft.masterProject,
                options = projects.filter { it != setup.slaveProject },
                optionText = { it },
                onPick = { m -> draft = draft.copy(masterProject = m, pairs = pairByPosition(m)) }
            )

            // robôs: origem -> destino
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Robôs (origem → destino)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { draft = draft.copy(pairs = pairByPosition(draft.masterProject)) }) { Text("Parear pela posição") }
            }
            slaveRobots.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PickerButton(
                        label = null,
                        value = masterRobots.firstOrNull { it.id == draft.pairs[r.id] }?.name ?: "Sem par",
                        options = listOf<Robot?>(null) + masterRobots,
                        optionText = { it?.name ?: "Sem par" },
                        onPick = { picked -> draft = draft.copy(pairs = draft.pairs + (r.id to picked?.id)) },
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.padding(horizontal = 10.dp))
                    Text(r.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(72.dp))
                }
            }

            HorizontalDivider()

            // base / offset
            SwitchRow(
                title = "Alterar a base no destino",
                subtitle = "Somar a variável de offset na base do programa",
                checked = draft.config.applyOffset,
                onChange = { draft = draft.copy(config = draft.config.copy(applyOffset = it)) }
            )
            OutlinedTextField(
                value = draft.offset,
                onValueChange = { draft = draft.copy(offset = it.replace(" ", "")) },
                label = { Text("Variável do offset") },
                enabled = draft.config.applyOffset,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth()
            )

            SwitchRow(
                title = "Enviar a base (.TRANS) junto",
                subtitle = "As linhas do frame da base saem do backup da origem",
                checked = draft.config.sendFrame,
                onChange = { draft = draft.copy(config = draft.config.copy(sendFrame = it)) }
            )
            OutlinedTextField(
                value = draft.config.framePattern,
                onValueChange = { draft = draft.copy(config = draft.config.copy(framePattern = it.replace(" ", ""))) },
                label = { Text("Frame da base do programa") },
                supportingText = {
                    Text("Use ${AsMasterTransfer.PGNUM} para o número do programa. Vazio = todas as bases.")
                },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth()
            )
            ExamplePreview(draft)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { draft = setup }, enabled = changed) { Text("Descartar") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSave(draft) }, enabled = changed && draft.offset.isNotBlank()) { Text("Salvar") }
            }
        }
    }
}

/** Exemplo do que acontece com o pg100 na transferência, com os nomes atuais. */
@Composable
private fun ExamplePreview(draft: SlaveSetup) {
    val pattern = draft.config.framePattern
    val frame = if (pattern.isBlank()) "fr_[100]" else AsMasterTransfer.frameFor(pattern, "pg100") ?: pattern
    val base = if (draft.config.applyOffset) "BASE $frame+${draft.offset}" else "BASE $frame"
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Exemplo: pg100", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text("Origem:  BASE $frame", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("Destino: $base", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text(
                if (draft.config.sendFrame) "Vai junto: a linha $frame da .TRANS" else "A .TRANS não vai junto",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Nova configuração: o projeto de destino (que ainda não tem mestre) e o de origem. */
@Composable
private fun NewSetupDialog(
    projects: List<String>,
    taken: Set<String>,
    onCreate: (slave: String, master: String) -> Unit,
    onDismiss: () -> Unit
) {
    var master by remember { mutableStateOf<String?>(null) }
    var slave by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = "Nova configuração",
        onDismiss = onDismiss,
        confirmButton = {
            Button(onClick = { onCreate(slave!!, master!!) }, enabled = master != null && slave != null && master != slave) { Text("Criar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    ) {
        Text(
            "Os programas saem dos robôs da origem e vão para os robôs do destino. Os pares começam pela " +
                "mesma posição na cabine; dá para trocar depois.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        PickerButton(
            label = "Origem (mestre)",
            value = master ?: "Escolher",
            options = projects,
            optionText = { it },
            onPick = { master = it }
        )
        PickerButton(
            label = "Destino (escravo)",
            value = slave ?: "Escolher",
            options = projects.filter { it !in taken && it != master },
            optionText = { it },
            onPick = { slave = it }
        )
    }
}
