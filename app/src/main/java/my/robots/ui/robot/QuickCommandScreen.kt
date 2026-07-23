package my.robots.ui.robot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import my.robots.data.model.Manufacturer
import my.robots.data.model.QuickCommand

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickCommandScreen(
    viewModel: QuickCommandViewModel,
    onBack: () -> Unit
) {
    val commands by viewModel.commands.collectAsState()
    var commandToEdit by remember { mutableStateOf<QuickCommand?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Biblioteca: ${viewModel.manufacturer.displayName}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Voltar")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Adicionar Comando")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().imePadding()) {
            Text(
                text = "Clique em um comando para enviar ao robô",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            
            if (commands.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nenhum comando cadastrado.", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(commands) { cmd ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.sendQuickCommand(cmd)
                                    onBack() // Volta para o terminal após enviar
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(cmd.label, style = MaterialTheme.typography.titleMedium)
                                    Text(cmd.command, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                }
                                IconButton(onClick = { commandToEdit = cmd }) {
                                    Icon(Icons.Default.Edit, "Editar", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { viewModel.deleteCommand(cmd) }) {
                                    Icon(Icons.Default.Delete, "Excluir", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showAddDialog) {
            AddQuickCommandDialog(
                onDismiss = { showAddDialog = false },
                onConfirm = { label, command ->
                    viewModel.addCommand(label, command)
                    showAddDialog = false
                }
            )
        }

        if (commandToEdit != null) {
            AddQuickCommandDialog(
                initialLabel = commandToEdit!!.label,
                initialCommand = commandToEdit!!.command,
                isEdit = true,
                onDismiss = { commandToEdit = null },
                onConfirm = { label, command ->
                    viewModel.updateCommand(commandToEdit!!.copy(label = label, command = command))
                    commandToEdit = null
                }
            )
        }
    }
}

@Composable
fun AddQuickCommandDialog(
    initialLabel: String = "",
    initialCommand: String = "",
    isEdit: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var label by remember { mutableStateOf(initialLabel) }
    var command by remember { mutableStateOf(initialCommand) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "Editar Comando Rápido" else "Novo Comando Rápido") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Dica: Use [ROBOT] para o nome do robô e [DATA] para data/hora (_aaaammdd_hhmm).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Nome do Botão (ex: Reset)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("Comando AS (ex: SAVE/P [ROBOT][DATA])") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(label, command) },
                enabled = label.isNotBlank() && command.isNotBlank()
            ) { Text(if (isEdit) "Salvar" else "Adicionar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}
