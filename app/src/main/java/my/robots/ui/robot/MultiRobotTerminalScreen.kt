package my.robots.ui.robot

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.robots.data.model.QuickCommand

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiRobotTerminalScreen(
    projectName: String,
    viewModel: MultiRobotTerminalViewModel,
    onBack: () -> Unit
) {
    val robots by viewModel.robots.collectAsState()
    val commandHistory by viewModel.commandHistory.collectAsState()
    val connectedRobotsIds by viewModel.connectedRobotsIds.collectAsState()
    val isConnecting by viewModel.isConnecting.collectAsState()
    val quickCommands by viewModel.quickCommands.collectAsState()
    
    var commandText by remember { mutableStateOf("") }
    var showConnectDialog by remember { mutableStateOf(false) }
    var showQuickCommands by remember { mutableStateOf(false) }
    
    val scrollState = rememberLazyListState()
    val horizontalScrollState = rememberScrollState()

    val isAnyConnected = connectedRobotsIds.isNotEmpty()

    // Scroll to bottom when history changes
    LaunchedEffect(commandHistory.size) {
        if (commandHistory.isNotEmpty()) {
            scrollState.animateScrollToItem(commandHistory.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Terminal Geral", style = MaterialTheme.typography.titleMedium)
                        Text(projectName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.clearHistory() }) {
                        Icon(Icons.Default.DeleteSweep, "Limpar Log", tint = MaterialTheme.colorScheme.error)
                    }

                    Button(
                        onClick = { 
                            if (!isAnyConnected) {
                                viewModel.connectAll()
                                showConnectDialog = true
                            } else {
                                viewModel.toggleConnection()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isAnyConnected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.padding(end = 8.dp).height(36.dp)
                    ) {
                        Text(if (isAnyConnected) "Desconectar" else "Conectar", fontSize = 12.sp)
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(top = padding.calculateTopPadding())
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding()
        ) {
            // Área de Texto (Logs) - Design idêntico ao TerminalPanel
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 4.dp)
                    .background(Color.Black, MaterialTheme.shapes.small)
                    .border(1.dp, Color.DarkGray, MaterialTheme.shapes.small)
                    .padding(8.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize().horizontalScroll(horizontalScrollState)) {
                    LazyColumn(
                        state = scrollState, 
                        modifier = Modifier.width(2000.dp) 
                    ) {
                        items(commandHistory) { line ->
                            Text(
                                text = line,
                                color = if (line.startsWith(">")) Color.Cyan else Color.Green,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                softWrap = false,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }

            // Campo de Entrada - Design idêntico ao TerminalPanel
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { showQuickCommands = true },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = "Comandos Rápidos",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                OutlinedTextField(
                    value = commandText,
                    onValueChange = { commandText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Enviar para todos...") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (commandText.isNotBlank()) {
                            viewModel.sendCommandToAll(commandText)
                            commandText = ""
                        }
                    }),
                    shape = MaterialTheme.shapes.medium
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = { },
                        modifier = Modifier.size(28.dp),
                        enabled = false
                    ) {
                        Icon(Icons.Default.ArrowUpward, null, modifier = Modifier.size(20.dp), tint = Color.Gray)
                    }
                    
                    IconButton(
                        onClick = {
                            if (commandText.isNotBlank()) {
                                viewModel.sendCommandToAll(commandText)
                                commandText = ""
                            }
                        },
                        enabled = commandText.isNotBlank() && isAnyConnected,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, null, tint = if (isAnyConnected) MaterialTheme.colorScheme.primary else Color.Gray)
                    }

                    IconButton(
                        onClick = { },
                        modifier = Modifier.size(28.dp),
                        enabled = false
                    ) {
                        Icon(Icons.Default.ArrowDownward, null, modifier = Modifier.size(20.dp), tint = Color.Gray)
                    }
                }
            }
        }
    }

    // Modal de Comandos Rápidos (Botão do Raio)
    if (showQuickCommands) {
        AlertDialog(
            onDismissRequest = { showQuickCommands = false },
            title = { Text("Biblioteca Kawasaki (Geral)") },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    Text(
                        "Comandos serão enviados para todos os robôs do projeto.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    if (quickCommands.isEmpty()) {
                        Text("Nenhum comando configurado.")
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(quickCommands) { cmd ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { 
                                            viewModel.sendQuickCommandToAll(cmd)
                                            showQuickCommands = false
                                        },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(cmd.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                        Text(cmd.command, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQuickCommands = false }) { Text("Fechar") }
            }
        )
    }

    // Pop-up de Conexão Automática
    if (showConnectDialog) {
        AlertDialog(
            onDismissRequest = { showConnectDialog = false },
            title = { Text("Conectando Projeto: $projectName") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Tentando conectar a todos os robôs automaticamente...")
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    robots.forEach { robot ->
                        val isConnected = connectedRobotsIds.contains(robot.id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isConnected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isConnected) Color(0xFF4CAF50) else Color.Gray,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(robot.name, style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.weight(1f))
                            if (!isConnected && isConnecting) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showConnectDialog = false }) {
                    Text("OK")
                }
            }
        )
    }
}
