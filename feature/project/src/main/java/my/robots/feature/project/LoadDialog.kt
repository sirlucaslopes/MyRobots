package my.robots.feature.project

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import my.robots.core.common.ascode.LoadCheck
import my.robots.core.common.ascode.LoadItem
import my.robots.core.common.ascode.LoadKind
import my.robots.core.designsystem.AppTopBar
import my.robots.core.model.BackupSummary
import my.robots.core.model.Robot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LoadStep { ROBOTS, SOURCE, CONTENT, CHECK }

/** Quantos itens de um grupo aparecem de uma vez (os outros pela busca). */
private const val MAX_ITEMS_SHOWN = 150

/**
 * "Carregar" das ações em grupo, em tela cheia, em quatro passos:
 * 1. **Robôs** que recebem o LOAD (um ou mais, da estação);
 * 2. **Arquivo:** um backup de qualquer robô do app, ou um .as do aparelho;
 * 3. **Conteúdo:** tudo o que tem no arquivo, por grupo (programas, posições, reais, textos, Data
 *    Bank e dados do sistema), cada item com caixa; só o marcado vai. Os dados do sistema mudam a
 *    configuração do controlador e vêm desmarcados, com aviso;
 * 4. **Conferência:** em cada robô, pelo último backup dele, o que é novo e o que SERÁ
 *    SUBSTITUÍDO; caixa por robô. Depois o LOAD conferido em cada um.
 */
@Composable
internal fun LoadDialog(
    targets: List<Robot>,
    allRobots: List<Robot>,
    connected: Set<Int>,
    source: ProjectViewModel.LoadSource?,
    busy: String?,
    checks: Map<Int, LoadCheck>?,
    backupsOf: suspend (Int) -> List<BackupSummary>,
    onPickDeviceFile: () -> Unit,
    onPickBackup: (Robot, BackupSummary) -> Unit,
    onCheck: (List<Robot>, List<LoadItem>) -> Unit,
    onBackFromCheck: () -> Unit,
    onConfirm: (List<Robot>, List<LoadItem>) -> Unit,
    onDismiss: () -> Unit
) {
    var step by remember { mutableStateOf(LoadStep.ROBOTS) }
    var chosenRobots by remember { mutableStateOf(targets.map { it.id }.toSet()) }
    var chosenItems by remember { mutableStateOf(emptySet<String>()) }
    var excluded by remember { mutableStateOf(emptySet<Int>()) }
    var sourceRobot by remember { mutableStateOf<Robot?>(null) }
    var filter by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(setOf(LoadKind.PROGRAM)) }
    val fmt = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }

    // arquivo lido e analisado: vai para o conteúdo (começa sem nada marcado)
    LaunchedEffect(source) {
        if (source != null && step == LoadStep.SOURCE) {
            chosenItems = emptySet()
            step = LoadStep.CONTENT
        }
    }
    LaunchedEffect(checks) { if (checks != null) step = LoadStep.CHECK }

    val robots = targets.filter { it.id in chosenRobots }
    val items = source?.items.orEmpty()
    val chosen = items.filter { it.key in chosenItems }

    fun back() {
        when (step) {
            LoadStep.ROBOTS -> onDismiss()
            LoadStep.SOURCE -> if (sourceRobot != null) sourceRobot = null else step = LoadStep.ROBOTS
            LoadStep.CONTENT -> step = LoadStep.SOURCE
            LoadStep.CHECK -> { onBackFromCheck(); step = LoadStep.CONTENT }
        }
    }

    Dialog(onDismissRequest = { if (busy == null) back() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                AppTopBar(
                    title = when (step) {
                        LoadStep.ROBOTS -> "Carregar: robôs"
                        LoadStep.SOURCE -> "Carregar: arquivo"
                        LoadStep.CONTENT -> "Carregar: o que vai"
                        LoadStep.CHECK -> "Conferir o carregamento"
                    },
                    subtitle = when (step) {
                        LoadStep.ROBOTS -> "Passo 1 de 4 · quem recebe"
                        LoadStep.SOURCE -> "Passo 2 de 4 · de onde vem"
                        LoadStep.CONTENT -> "Passo 3 de 4 · ${source?.label.orEmpty()}"
                        LoadStep.CHECK -> "Passo 4 de 4 · ${chosen.size} item(ns) em ${robots.size - excluded.count { it in chosenRobots }} robô(s)"
                    },
                    onBack = { if (busy == null) back() }
                )
            },
            bottomBar = {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss, enabled = busy == null) {
                            Icon(Icons.Rounded.Close, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Cancelar")
                        }
                        Spacer(Modifier.weight(1f))
                        when (step) {
                            LoadStep.ROBOTS -> Button(onClick = { step = LoadStep.SOURCE }, enabled = robots.isNotEmpty()) { Text("Escolher o arquivo") }
                            LoadStep.SOURCE -> {}
                            LoadStep.CONTENT -> Button(
                                onClick = { excluded = emptySet(); onCheck(robots, chosen) },
                                enabled = chosen.isNotEmpty() && busy == null
                            ) { Text("Conferir (${chosen.size})") }
                            LoadStep.CHECK -> {
                                val going = robots.filter { it.id !in excluded }
                                val warn = going.any { checks?.get(it.id)?.warn == true }
                                Button(
                                    onClick = { onConfirm(going, chosen) },
                                    enabled = going.isNotEmpty() && busy == null,
                                    colors = if (warn) ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300), contentColor = Color.Black)
                                    else ButtonDefaults.buttonColors()
                                ) { Text(if (warn) "Carregar mesmo assim" else "Carregar em ${going.size} robô(s)") }
                            }
                        }
                    }
                }
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                busy?.let { b ->
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(b, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                when (step) {
                    LoadStep.ROBOTS -> {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Robôs (${robots.size} de ${targets.size})", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                TextButton(onClick = { chosenRobots = if (robots.size == targets.size) emptySet() else targets.map { it.id }.toSet() }) {
                                    Text(if (robots.size == targets.size) "Nenhum" else "Todos")
                                }
                            }
                        }
                        items(targets, key = { it.id }) { r ->
                            CheckRow(
                                checked = r.id in chosenRobots,
                                text = r.name,
                                supporting = if (r.id in connected) "conectado" else "vai conectar",
                                onToggle = { chosenRobots = if (r.id in chosenRobots) chosenRobots - r.id else chosenRobots + r.id }
                            )
                        }
                        item {
                            Text(
                                "O LOAD substitui sem perguntar o programa que já existe no robô. A conferência (passo 4) mostra o que será substituído.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    LoadStep.SOURCE -> {
                        val pick = sourceRobot
                        if (pick == null) {
                            item {
                                OutlinedButton(onClick = onPickDeviceFile, enabled = busy == null, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                                    Text("Arquivo do aparelho (.as)")
                                }
                            }
                            item { Text("Ou um backup de um robô:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp)) }
                            items(allRobots.sortedWith(compareBy({ it.project.lowercase() }, { it.name.lowercase() })), key = { "r${it.id}" }) { r ->
                                PickRow(icon = true, title = r.name, subtitle = r.project.trim()) { sourceRobot = r }
                            }
                        } else {
                            item { Text("Backups do ${pick.name} (o mais novo primeiro):", style = MaterialTheme.typography.labelLarge) }
                            item {
                                var list by remember(pick.id) { mutableStateOf<List<BackupSummary>?>(null) }
                                LaunchedEffect(pick.id) { list = backupsOf(pick.id) }
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    when {
                                        list == null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                        list!!.isEmpty() -> Text("Este robô não tem backup.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        else -> list!!.forEach { b ->
                                            PickRow(
                                                icon = false,
                                                title = fmt.format(Date(b.timestamp)),
                                                subtitle = "${b.fileName} · ${b.programsCount} programas · ${b.variablesCount} variáveis"
                                            ) { if (busy == null) onPickBackup(pick, b) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    LoadStep.CONTENT -> {
                        item {
                            OutlinedTextField(
                                value = filter,
                                onValueChange = { filter = it },
                                label = { Text("Procurar (nome ou valor)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        LoadKind.entries.forEach { kind ->
                            val all = items.filter { it.kind == kind }
                            if (all.isEmpty()) return@forEach
                            val shown = if (filter.isBlank()) all else all.filter { it.name.contains(filter.trim(), true) || it.detail.contains(filter.trim(), true) }
                            val marked = all.count { it.key in chosenItems }
                            val isOpen = kind in open || filter.isNotBlank()
                            item(key = "h_$kind") {
                                GroupHeader(
                                    kind = kind,
                                    total = all.size,
                                    marked = marked,
                                    open = isOpen,
                                    onToggleOpen = { open = if (kind in open) open - kind else open + kind },
                                    onToggleAll = {
                                        val keys = shown.map { it.key }.toSet()
                                        chosenItems = if (keys.all { it in chosenItems }) chosenItems - keys else chosenItems + keys
                                    },
                                    allMarked = shown.isNotEmpty() && shown.all { it.key in chosenItems }
                                )
                            }
                            if (isOpen) {
                                items(shown.take(MAX_ITEMS_SHOWN), key = { it.key }) { it2 ->
                                    ItemRow(it2, it2.key in chosenItems) {
                                        chosenItems = if (it2.key in chosenItems) chosenItems - it2.key else chosenItems + it2.key
                                    }
                                }
                                if (shown.size > MAX_ITEMS_SHOWN) {
                                    item(key = "more_$kind") {
                                        Text(
                                            "… mais ${shown.size - MAX_ITEMS_SHOWN}. Use a busca para achar um item (\"todos\" marca também os que não aparecem).",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                    LoadStep.CHECK -> {
                        items(robots, key = { "c${it.id}" }) { r ->
                            CheckCard(r, checks?.get(r.id), r.id !in excluded) {
                                excluded = if (r.id in excluded) excluded - r.id else excluded + r.id
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickRow(icon: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (icon) Icon(Icons.Rounded.SmartToy, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.ChevronRight, null)
    }
}

@Composable
private fun GroupHeader(
    kind: LoadKind,
    total: Int,
    marked: Int,
    open: Boolean,
    allMarked: Boolean,
    onToggleOpen: () -> Unit,
    onToggleAll: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggleOpen).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = allMarked, onCheckedChange = { onToggleAll() })
                Column(Modifier.weight(1f)) {
                    Text("${kind.label} · $total", fontWeight = FontWeight.SemiBold, color = if (kind.system) Color(0xFFFFB300) else Color.Unspecified)
                    Text("$marked marcado(s)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (open) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight, null)
            }
            if (kind.system) {
                Text(
                    "Mudam a configuração do controlador (o .NETCONF muda até o IP). Só marque se tiver certeza.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFB300),
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ItemRow(item: LoadItem, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggle).padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.detail.isNotBlank()) {
                Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Conferência de um robô: o que entra, o que SERÁ SUBSTITUÍDO, e a caixa para mandar ou não. */
@Composable
private fun CheckCard(robot: Robot, check: LoadCheck?, going: Boolean, onToggle: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = going, onCheckedChange = { onToggle() })
                Text(robot.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (check?.warn == true && going) Icon(Icons.Rounded.Warning, null, tint = Color(0xFFFFB300))
            }
            if (check == null) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                return@Column
            }
            if (!check.backupKnown) Line("Sem backup: não dá para saber o que já existe no robô.", warn = true)
            val p = check.newPrograms.size + check.replacedPrograms.size
            if (p > 0) {
                Line("Programas: ${check.newPrograms.size} novo(s)" +
                    if (check.replacedPrograms.isNotEmpty()) " · ${check.replacedPrograms.size} SERÁ(ÃO) SUBSTITUÍDO(S)" else "", warn = check.replacedPrograms.isNotEmpty())
                if (check.replacedPrograms.isNotEmpty()) Line("   " + check.replacedPrograms.take(12).joinToString(", ") + if (check.replacedPrograms.size > 12) "…" else "")
            }
            val v = check.newVariables + check.changedVariables.size + check.sameVariables
            if (v > 0) {
                Line("Variáveis: ${check.newVariables} nova(s) · ${check.changedVariables.size} mudam de valor · ${check.sameVariables} igual(is)", warn = check.changedVariables.isNotEmpty())
                if (check.changedVariables.isNotEmpty()) Line("   " + check.changedVariables.take(12).joinToString(", ") + if (check.changedVariables.size > 12) "…" else "")
            }
            val d = check.newDataBank + check.replacedDataBank.size
            if (d > 0) Line("Data Bank: ${check.newDataBank} novo(s) · ${check.replacedDataBank.size} SERÁ(ÃO) SUBSTITUÍDO(S)", warn = check.replacedDataBank.isNotEmpty())
            if (check.systemSections.isNotEmpty()) Line("Dados do sistema: ${check.systemSections.joinToString(", ")}: muda a configuração do controlador", warn = true)
            HorizontalDivider(Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun Line(text: String, warn: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (warn) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurface,
        fontWeight = if (warn) FontWeight.SemiBold else FontWeight.Normal
    )
}
