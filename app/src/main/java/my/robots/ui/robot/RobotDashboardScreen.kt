package my.robots.ui.robot

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import my.robots.data.model.Backup
import my.robots.data.model.Manufacturer
import my.robots.data.model.QuickCommand
import my.robots.data.model.Robot
import my.robots.data.remote.RobotStatusResponse
import my.robots.ui.backup.AsCodeViewer
import java.text.SimpleDateFormat
import java.util.*

enum class DashboardFeature(val label: String, val icon: ImageVector) {
    Logs("Terminal", Icons.AutoMirrored.Rounded.Article),
    Programs("Programas", Icons.Rounded.Code),
    Variables("Variáveis", Icons.Rounded.Tune),
    FullCode("Código AS", Icons.Rounded.Description),
    DataBank("Data Bank", Icons.Rounded.Storage)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RobotDashboardScreen(
    viewModel: RobotDashboardViewModel? = null,
    onViewBackups: () -> Unit = {},
    onProgramClick: (Backup, String) -> Unit = { _, _ -> },
    onVariablesClick: (Backup) -> Unit = {},
    onBack: () -> Unit = {},
    onFullCodeClick: (Backup) -> Unit = {},
    onQuickCommandsClick: () -> Unit = {},
    onNavigateToRobot: (robotId: Int, backupId: Int, feature: DashboardFeature?) -> Unit = { _, _, _ -> },
    onFeatureClick: (DashboardFeature) -> Unit = {},
    initialFeature: DashboardFeature? = null,
    mockRobot: Robot? = null,
    mockStatus: RobotStatusResponse? = null
) {
    val robotState = if (viewModel != null) viewModel.robot.collectAsState() else remember { mutableStateOf(mockRobot) }
    val terminalOutputState = if (viewModel != null) viewModel.terminalOutput.collectAsState() else remember { mutableStateOf(emptyList<String>()) }
    val programsState = if (viewModel != null) viewModel.programs.collectAsState() else remember { mutableStateOf(emptyList<RobotProgram>()) }
    val variablesState = if (viewModel != null) viewModel.variables.collectAsState() else remember { mutableStateOf(emptyList<RobotVariable>()) }
    val dataBankEntriesState = if (viewModel != null) viewModel.dataBankEntries.collectAsState() else remember { mutableStateOf(emptyList<RobotDataBankEntry>()) }
    val latestBackupState = if (viewModel != null) viewModel.latestBackup.collectAsState() else remember { mutableStateOf(null) }
    val isLoadingState = if (viewModel != null) viewModel.isLoading.collectAsState() else remember { mutableStateOf(false) }
    val allRobotsState = if (viewModel != null) viewModel.allRobots.collectAsState() else remember { mutableStateOf(emptyList<Robot>()) }
    val quickCommandsState = if (viewModel != null) viewModel.quickCommands.collectAsState() else remember { mutableStateOf(emptyList<QuickCommand>()) }

    val robot by robotState
    val terminalOutput by terminalOutputState
    val programs by programsState
    val variables by variablesState
    val dataBankEntries by dataBankEntriesState
    val latestBackup by latestBackupState
    val isLoading by isLoadingState
    val allRobots by allRobotsState
    val quickCommands by quickCommandsState

    // Usar rememberSaveable para garantir que o estado sobreviva à navegação
    var activeFeature by rememberSaveable { mutableStateOf<DashboardFeature?>(initialFeature) }
    
    // Sincronizar activeFeature com initialFeature se ela mudar (ex: popBackStack com novos argumentos)
    LaunchedEffect(initialFeature) {
        if (initialFeature != null) {
            activeFeature = initialFeature
        }
    }

    val context = LocalContext.current
    
    // Estados para diálogos
    var programToUpload by remember { mutableStateOf<RobotProgram?>(null) }
    var variableToUpload by remember { mutableStateOf<RobotVariable?>(null) }
    var dataBankToUpload by remember { mutableStateOf<List<RobotDataBankEntry>?>(null) }
    var programToDuplicate by remember { mutableStateOf<RobotProgram?>(null) }
    var programToDelete by remember { mutableStateOf<RobotProgram?>(null) }
    var variableToDelete by remember { mutableStateOf<RobotVariable?>(null) }
    var dataBankToDelete by remember { mutableStateOf<RobotDataBankEntry?>(null) }
    
    // BackHandler: Se entramos com uma feature inicial (ex: Terminal via lista), não queremos que o back apenas limpe a feature.
    // Se activeFeature foi alterada pelo usuário e é diferente da inicial, voltamos para a Home do Dashboard.
    val canGoBackToHome = activeFeature != null && activeFeature != initialFeature
    
    BackHandler(enabled = canGoBackToHome) {
        activeFeature = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { 
                        Text(
                            text = when (activeFeature) {
                                null -> robot?.name ?: "Painel"
                                DashboardFeature.Logs -> "Terminal: ${robot?.name ?: ""}"
                                DashboardFeature.Programs -> "Programas: ${robot?.name ?: ""}"
                                DashboardFeature.Variables -> "Variáveis: ${robot?.name ?: ""}"
                                DashboardFeature.DataBank -> "Data Bank: ${robot?.name ?: ""}"
                                else -> activeFeature!!.label
                            },
                            style = MaterialTheme.typography.titleLarge
                        ) 
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (canGoBackToHome) {
                                activeFeature = null 
                            } else {
                                onBack()
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (activeFeature == null) {
                            IconButton(onClick = { viewModel?.refreshStatus() }) {
                                Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                            }
                        } else if (activeFeature == DashboardFeature.Logs) {
                            val isConnected by (viewModel?.isConnected?.collectAsState() ?: remember { mutableStateOf(false) })
                            
                            IconButton(onClick = {
                                viewModel?.clearTerminal()
                            }) {
                                Icon(Icons.Default.DeleteSweep, "Limpar Log", tint = MaterialTheme.colorScheme.error)
                            }

                            IconButton(onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW)
                                    val rootUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMyRobots")
                                    intent.setDataAndType(rootUri, DocumentsContract.Document.MIME_TYPE_DIR)
                                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    // Fallback se falhar
                                    onViewBackups()
                                }
                            }) {
                                Icon(Icons.Rounded.Folder, "Arquivos", tint = MaterialTheme.colorScheme.primary)
                            }

                            Button(
                                onClick = { viewModel?.toggleConnection() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isConnected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.padding(end = 8.dp).height(36.dp)
                            ) {
                                Text(if (isConnected) "Desconectar" else "Conectar", fontSize = 12.sp)
                            }
                        }
                    }
                )
            },
            // IMPORTANTE: Definimos contentWindowInsets para zero para gerenciar o padding inferior manualmente no Terminal
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { padding ->
            // Aplicamos apenas o topo do Scaffold (AppBar)
            Box(modifier = Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
                AnimatedContent(
                    targetState = activeFeature,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "FeatureTransition"
                ) { feature ->
                    when (feature) {
                        null -> DashboardHome(
                            robot = robot,
                            backup = latestBackup,
                            onFeatureClick = { selected -> 
                                if (selected == DashboardFeature.FullCode) {
                                    if (latestBackup != null) onFullCodeClick(latestBackup!!)
                                } else {
                                    activeFeature = selected
                                    onFeatureClick(selected)
                                }
                            }
                        )
                        DashboardFeature.Logs -> TerminalPanel(
                            output = terminalOutput,
                            quickCommands = quickCommands,
                            viewModel = viewModel,
                            onQuickCommandsClick = onQuickCommandsClick
                        )
                        DashboardFeature.Programs -> ProgramsPanel(
                            programs = programs, 
                            onProgramClick = { prog -> 
                                if (latestBackup != null) onProgramClick(latestBackup!!, prog.name)
                            },
                            onUpload = { prog -> programToUpload = prog },
                            onDuplicate = { prog -> programToDuplicate = prog },
                            onShare = { /* Logic */ },
                            onDelete = { prog -> programToDelete = prog }
                        )
                        DashboardFeature.Variables -> VariablesPanel(
                            variables = variables, 
                            viewModel = viewModel,
                            onUpload = { v -> variableToUpload = v },
                            onDelete = { v -> variableToDelete = v }
                        )
                        DashboardFeature.DataBank -> DataBankPanel(
                            entries = dataBankEntries,
                            viewModel = viewModel,
                            onUploadSelected = { selected -> dataBankToUpload = selected },
                            onDelete = { e -> dataBankToDelete = e }
                        )
                        DashboardFeature.FullCode -> { /* Handled in onFeatureClick */ }
                    }
                }
                
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }

        // Diálogo de Duplicação e Exclusão
        if (programToDuplicate != null) {
            DuplicateProgramDialog(
                program = programToDuplicate!!,
                viewModel = viewModel,
                onDismiss = { programToDuplicate = null },
                onConfirm = { newName ->
                    viewModel?.duplicateProgram(programToDuplicate!!, newName)
                    programToDuplicate = null
                }
            )
        }

        if (programToDelete != null) {
            AlertDialog(
                onDismissRequest = { programToDelete = null },
                title = { Text("Excluir Programa") },
                text = { Text("Tem certeza que deseja excluir o programa \"${programToDelete?.name}\"? Esta ação removerá o código do backup.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            programToDelete?.let { viewModel?.deleteProgram(it) }
                            programToDelete = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Excluir") }
                },
                dismissButton = {
                    TextButton(onClick = { programToDelete = null }) { Text("Cancelar") }
                }
            )
        }

        if (variableToDelete != null) {
            AlertDialog(
                onDismissRequest = { variableToDelete = null },
                title = { Text("Excluir Variável") },
                text = { Text("Tem certeza que deseja excluir a variável \"${variableToDelete?.name}\"?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            variableToDelete?.let { viewModel?.deleteVariable(it) }
                            variableToDelete = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Excluir") }
                },
                dismissButton = {
                    TextButton(onClick = { variableToDelete = null }) { Text("Cancelar") }
                }
            )
        }

        if (dataBankToDelete != null) {
            AlertDialog(
                onDismissRequest = { dataBankToDelete = null },
                title = { Text("Excluir Data Bank") },
                text = { Text("Tem certeza que deseja excluir o registro \"${dataBankToDelete?.num}\"?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            dataBankToDelete?.let { viewModel?.deleteDataBankEntry(it) }
                            dataBankToDelete = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Excluir") }
                },
                dismissButton = {
                    TextButton(onClick = { dataBankToDelete = null }) { Text("Cancelar") }
                }
            )
        }

        // Popup Seleção de Robô para Upload
        if (programToUpload != null) {
            RobotSelectionDialog(
                title = "Enviar Programa para qual Robô?",
                itemName = programToUpload?.name ?: "",
                robots = allRobots,
                onSelect = { r ->
                    viewModel?.sendProgramToRobot(programToUpload!!, r)
                    val targetId = r.id
                    programToUpload = null
                    if (targetId == (robot?.id ?: -1)) {
                        activeFeature = DashboardFeature.Logs
                    } else {
                        onNavigateToRobot(targetId, -1, DashboardFeature.Logs)
                    }
                },
                onDismiss = { programToUpload = null }
            )
        }

        if (variableToUpload != null) {
            RobotSelectionDialog(
                title = "Enviar Variável para qual Robô?",
                itemName = variableToUpload?.name ?: "",
                robots = allRobots,
                onSelect = { r ->
                    viewModel?.sendVariableToRobot(variableToUpload!!, r)
                    val targetId = r.id
                    variableToUpload = null
                    if (targetId == (robot?.id ?: -1)) {
                        activeFeature = DashboardFeature.Logs
                    } else {
                        onNavigateToRobot(targetId, -1, DashboardFeature.Logs)
                    }
                },
                onDismiss = { variableToUpload = null }
            )
        }

        if (dataBankToUpload != null) {
            RobotSelectionDialog(
                title = "Enviar Data Bank para qual Robô?",
                itemName = if (dataBankToUpload!!.size == 1) "DB${dataBankToUpload!![0].num}" else "${dataBankToUpload!!.size} itens selecionados",
                robots = allRobots,
                onSelect = { r ->
                    viewModel?.sendDataBankEntriesToRobot(dataBankToUpload!!, r)
                    val targetId = r.id
                    dataBankToUpload = null
                    if (targetId == (robot?.id ?: -1)) {
                        activeFeature = DashboardFeature.Logs
                    } else {
                        onNavigateToRobot(targetId, -1, DashboardFeature.Logs)
                    }
                },
                onDismiss = { dataBankToUpload = null }
            )
        }
    }
}

@Composable
fun TerminalPanel(
    output: List<String>, 
    quickCommands: List<QuickCommand>, 
    viewModel: RobotDashboardViewModel?,
    onQuickCommandsClick: () -> Unit = {}
) {
    var commandText by remember { mutableStateOf("") }
    val scrollState = rememberLazyListState()
    val horizontalScrollState = rememberScrollState()
    
    LaunchedEffect(output.size) {
        if (output.isNotEmpty()) {
            scrollState.animateScrollToItem(output.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // Área de Texto (Logs)
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
                    items(output) { line ->
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

        // Campo de Entrada
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onQuickCommandsClick,
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
                onValueChange = { newValue ->
                    val diff = newValue.length - commandText.length
                    if (diff > 0) {
                        val added = newValue.substring(commandText.length)
                        added.forEach { viewModel?.sendChar(it.toString()) }
                    } else if (diff < 0) {
                        repeat(-diff) { viewModel?.sendChar("\b") }
                    }
                    commandText = newValue
                },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Enviar comando...") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Send
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = {
                    if (commandText.isNotBlank()) {
                        viewModel?.sendCommand("") // Enter (\r\n)
                        commandText = ""
                    }
                }),
                shape = MaterialTheme.shapes.medium
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    onClick = { viewModel?.sendChar("UP") },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.ArrowUpward, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                }
                
                IconButton(
                    onClick = {
                        viewModel?.sendCommand("")
                        commandText = ""
                    },
                    enabled = commandText.isNotBlank(),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, null, tint = MaterialTheme.colorScheme.primary)
                }

                IconButton(
                    onClick = { viewModel?.sendChar("DOWN") },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.ArrowDownward, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
fun DataBankPanel(
    entries: List<RobotDataBankEntry>,
    viewModel: RobotDashboardViewModel?,
    onUploadSelected: (List<RobotDataBankEntry>) -> Unit,
    onDelete: (RobotDataBankEntry) -> Unit
) {
    var selectedEntry by remember { mutableStateOf<RobotDataBankEntry?>(null) }
    val selectedEntries = remember { mutableStateListOf<String>() }
    
    var showEditDialog by remember { mutableStateOf<RobotDataBankEntry?>(null) }
    var showDuplicateDialog by remember { mutableStateOf<RobotDataBankEntry?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var sortAscending by remember { mutableStateOf(true) }

    val sortedEntries = remember(entries, sortAscending) {
        if (sortAscending) entries.sortedBy { it.num.toIntOrNull() ?: 0 }
        else entries.sortedByDescending { it.num.toIntOrNull() ?: 0 }
    }

    Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
        DataBankToolbar(
            selected = selectedEntry,
            hasSelection = selectedEntries.isNotEmpty(),
            sortAscending = sortAscending,
            onSortToggle = { sortAscending = !sortAscending },
            onCreate = { showCreateDialog = true },
            onEdit = { showEditDialog = selectedEntry },
            onDuplicate = { showDuplicateDialog = selectedEntry },
            onUpload = { 
                if (selectedEntries.isNotEmpty()) {
                    val batch = entries.filter { it.num in selectedEntries }
                    onUploadSelected(batch)
                } else {
                    selectedEntry?.let { onUploadSelected(listOf(it)) }
                }
            },
            onDelete = { selectedEntry?.let { onDelete(it) } }
        )

        val horizontalScrollState = rememberScrollState()

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .width(50.dp)
                                .height(40.dp)
                                .background(MaterialTheme.colorScheme.surface)
                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                .zIndex(2f),
                            contentAlignment = Alignment.Center
                        ) {
                            Checkbox(
                                checked = selectedEntries.size == entries.size && entries.isNotEmpty(),
                                onCheckedChange = { checked ->
                                    selectedEntries.clear()
                                    if (checked) {
                                        selectedEntries.addAll(entries.map { it.num })
                                    }
                                }
                            )
                        }

                        TableCell(text = "Num.", width = 50.dp, isHeader = true, modifier = Modifier.background(MaterialTheme.colorScheme.surface).zIndex(1f))
                        TableCell(text = "Comment", width = 120.dp, isHeader = true, modifier = Modifier.background(MaterialTheme.colorScheme.surface).zIndex(1f))
                        
                        Box(modifier = Modifier.horizontalScroll(horizontalScrollState)) {
                            Row(modifier = Modifier.width(600.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                listOf("FRATE", "PATTERN", "ATOMIZE", "HVOLT", "SPEED", "JSPEED").forEach { label ->
                                    TableCell(text = label, width = 100.dp, isHeader = true)
                                }
                            }
                        }
                    }
                }

                items(sortedEntries) { entry ->
                    val isRowSelected = selectedEntry?.num == entry.num
                    val isChecked = entry.num in selectedEntries
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedEntry = if (isRowSelected) null else entry }
                            .background(if (isRowSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(50.dp)
                                .height(40.dp)
                                .background(if (isRowSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                .zIndex(2f),
                            contentAlignment = Alignment.Center
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    if (checked) selectedEntries.add(entry.num)
                                    else selectedEntries.remove(entry.num)
                                }
                            )
                        }

                        TableCell(text = entry.num, width = 50.dp, modifier = Modifier.background(if (isRowSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface).zIndex(1f))
                        TableCell(text = entry.comment, width = 120.dp, textAlign = TextAlign.Start, modifier = Modifier.background(if (isRowSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface).zIndex(1f))

                        Box(modifier = Modifier.horizontalScroll(horizontalScrollState)) {
                            Row(modifier = Modifier.width(600.dp)) {
                                TableCell(text = entry.frate, width = 100.dp)
                                TableCell(text = entry.pattern, width = 100.dp)
                                TableCell(text = entry.atomize, width = 100.dp)
                                TableCell(text = entry.hvolt, width = 100.dp)
                                TableCell(text = entry.speed, width = 100.dp)
                                TableCell(text = entry.jspeed, width = 100.dp)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditDialog != null) {
        DataBankEditDialog(
            entry = showEditDialog!!,
            onDismiss = { showEditDialog = null },
            onSave = { updated ->
                viewModel?.updateDataBankEntry(updated)
                showEditDialog = null
                selectedEntry = updated
            }
        )
    }

    if (showDuplicateDialog != null) {
        DataBankDuplicateDialog(
            entry = showDuplicateDialog!!,
            viewModel = viewModel,
            onDismiss = { showDuplicateDialog = null },
            onConfirm = { newNum ->
                viewModel?.duplicateDataBankEntry(showDuplicateDialog!!, newNum)
                showDuplicateDialog = null
            }
        )
    }

    if (showCreateDialog) {
        DataBankEditDialog(
            entry = RobotDataBankEntry(num = "", comment = "", frate = "0", pattern = "0", atomize = "0", hvolt = "0", speed = "0", jspeed = "0"),
            isNew = true,
            viewModel = viewModel,
            onDismiss = { showCreateDialog = false },
            onSave = { newEntry ->
                viewModel?.duplicateDataBankEntry(newEntry, newEntry.num)
                showCreateDialog = false
            }
        )
    }
}

@Composable
fun DataBankToolbar(
    selected: RobotDataBankEntry?,
    hasSelection: Boolean,
    sortAscending: Boolean,
    onSortToggle: () -> Unit,
    onCreate: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onUpload: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onCreate) {
                Icon(Icons.Default.Add, "Criar", tint = Color(0xFF2E7D32))
            }
            VerticalDivider(modifier = Modifier.height(32.dp).align(Alignment.CenterVertically))
            
            IconButton(onClick = onSortToggle) {
                Icon(
                    imageVector = if (sortAscending) Icons.Default.SortByAlpha else Icons.Default.VerticalAlignBottom, 
                    contentDescription = "Ordenar"
                )
            }

            IconButton(onClick = onEdit, enabled = selected != null) {
                Icon(Icons.Default.Edit, "Editar")
            }
            IconButton(onClick = onDuplicate, enabled = selected != null) {
                Icon(Icons.Default.ContentCopy, "Duplicar")
            }
            
            Button(
                onClick = onUpload,
                enabled = selected != null || hasSelection,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Icon(Icons.Rounded.CloudUpload, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (hasSelection) "Enviar Seleção" else "Enviar", fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onDelete, enabled = selected != null) {
                Icon(Icons.Default.Delete, "Excluir", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun DataBankEditDialog(
    entry: RobotDataBankEntry,
    isNew: Boolean = false,
    viewModel: RobotDashboardViewModel? = null,
    onDismiss: () -> Unit,
    onSave: (RobotDataBankEntry) -> Unit
) {
    var num by remember { mutableStateOf(entry.num) }
    var comment by remember { mutableStateOf(entry.comment) }
    var frate by remember { mutableStateOf(entry.frate) }
    var pattern by remember { mutableStateOf(entry.pattern) }
    var atomize by remember { mutableStateOf(entry.atomize) }
    var hvolt by remember { mutableStateOf(entry.hvolt) }
    var speed by remember { mutableStateOf(entry.speed) }
    var jspeed by remember { mutableStateOf(entry.jspeed) }

    val numError = if (isNew) remember(num) { viewModel?.validateDataBankNum(num) } else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Novo Registro" else "Editar Data Bank: ${entry.num}") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isNew) {
                    OutlinedTextField(
                        value = num, 
                        onValueChange = { num = it }, 
                        label = { Text("Número (Num.)") },
                        isError = numError != null,
                        supportingText = { numError?.let { Text(it) } },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(value = comment, onValueChange = { comment = it }, label = { Text("Comment") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = frate, onValueChange = { frate = it }, label = { Text("FRATE") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = pattern, onValueChange = { pattern = it }, label = { Text("PATTERN") }, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = atomize, onValueChange = { atomize = it }, label = { Text("ATOMIZE") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = hvolt, onValueChange = { hvolt = it }, label = { Text("HVOLT") }, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = speed, onValueChange = { speed = it }, label = { Text("SPEED") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = jspeed, onValueChange = { jspeed = it }, label = { Text("JSPEED") }, modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(entry.copy(num = if (isNew) num else entry.num, comment = comment, frate = frate, pattern = pattern, atomize = atomize, hvolt = hvolt, speed = speed, jspeed = jspeed)) },
                enabled = !isNew || (numError == null && num.isNotBlank())
            ) {
                Text("Salvar")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
fun DataBankDuplicateDialog(
    entry: RobotDataBankEntry,
    viewModel: RobotDashboardViewModel?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var newNum by remember { mutableStateOf("") }
    val error = remember(newNum) { viewModel?.validateDataBankNum(newNum) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Duplicar Registro") },
        text = {
            OutlinedTextField(
                value = newNum,
                onValueChange = { newNum = it },
                label = { Text("Novo Número") },
                isError = error != null,
                supportingText = { error?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(newNum) }, enabled = error == null && newNum.isNotBlank()) { Text("Confirmar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
fun VariablesPanel(
    variables: List<RobotVariable>, 
    viewModel: RobotDashboardViewModel?,
    onUpload: (RobotVariable) -> Unit,
    onDelete: (RobotVariable) -> Unit
) {
    var selectedVariable by remember { mutableStateOf<RobotVariable?>(null) }
    var showEditDialog by remember { mutableStateOf<RobotVariable?>(null) }
    var showDuplicateDialog by remember { mutableStateOf<RobotVariable?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var sortAscending by remember { mutableStateOf(true) }

    val sortedVariables = remember(variables, sortAscending) {
        if (sortAscending) variables.sortedBy { it.name }
        else variables.sortedByDescending { it.name }
    }

    Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
        VariableToolbar(
            selected = selectedVariable,
            sortAscending = sortAscending,
            onSortToggle = { sortAscending = !sortAscending },
            onCreate = { showCreateDialog = true },
            onEdit = { showEditDialog = selectedVariable },
            onDuplicate = { showDuplicateDialog = selectedVariable },
            onUpload = { selectedVariable?.let { onUpload(it) } },
            onDelete = { selectedVariable?.let { onDelete(it) } }
        )

        val horizontalScrollState = rememberScrollState()

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TableCell(text = "Name", width = 150.dp, isHeader = true, textAlign = TextAlign.Start, 
                            modifier = Modifier.background(MaterialTheme.colorScheme.surface).zIndex(1f))
                        
                        Box(modifier = Modifier.horizontalScroll(horizontalScrollState)) {
                            Row(modifier = Modifier.width(680.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                listOf("X", "Y", "Z", "O", "A", "T", "JT7", "JT8").forEach { label ->
                                    TableCell(text = label, width = 85.dp, isHeader = true)
                                }
                            }
                        }
                    }
                }

                items(sortedVariables) { variable ->
                    val values = remember(variable.value) { 
                        variable.value.split(Regex("\\s+")).filter { it.isNotBlank() } 
                    }
                    val isSelected = selectedVariable?.name == variable.name

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedVariable = if (isSelected) null else variable }
                            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    ) {
                        TableCell(
                            text = variable.name,
                            width = 150.dp,
                            textAlign = TextAlign.Start,
                            textColor = if (variable.name.startsWith("!")) Color(0xFFD4AC0D) else Color.Unspecified,
                            modifier = Modifier.background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface).zIndex(1f)
                        )

                        Box(modifier = Modifier.horizontalScroll(horizontalScrollState)) {
                            Row(modifier = Modifier.width(680.dp)) {
                                if (variable.type == "FRAME") {
                                    repeat(8) { index ->
                                        TableCell(text = values.getOrNull(index) ?: "0.000", width = 85.dp)
                                    }
                                } else {
                                    TableCell(text = variable.value, width = 680.dp, textAlign = TextAlign.Start)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditDialog != null) {
        VariableEditDialog(
            variable = showEditDialog!!,
            viewModel = viewModel,
            onDismiss = { showEditDialog = null },
            onSave = { updated ->
                viewModel?.updateVariable(showEditDialog!!.name, updated)
                showEditDialog = null
                selectedVariable = updated
            }
        )
    }

    if (showDuplicateDialog != null) {
        VariableDuplicateDialog(
            variable = showDuplicateDialog!!,
            viewModel = viewModel,
            onDismiss = { showDuplicateDialog = null },
            onConfirm = { newName ->
                viewModel?.duplicateVariable(showDuplicateDialog!!, newName)
                showDuplicateDialog = null
            }
        )
    }
    
    if (showCreateDialog) {
        VariableEditDialog(
            variable = RobotVariable(name = "", value = "0 0 0 0 0 0 0 0", type = "FRAME"),
            viewModel = viewModel,
            isNew = true,
            onDismiss = { showCreateDialog = false },
            onSave = { newVar ->
                viewModel?.duplicateVariable(newVar, newVar.name)
                showCreateDialog = false
            }
        )
    }
}

@Composable
fun VariableToolbar(
    selected: RobotVariable?,
    sortAscending: Boolean,
    onSortToggle: () -> Unit,
    onCreate: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onUpload: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onCreate) {
                Icon(Icons.Default.Add, "Criar", tint = Color(0xFF2E7D32))
            }
            VerticalDivider(modifier = Modifier.height(32.dp).align(Alignment.CenterVertically))
            
            IconButton(onClick = onSortToggle) {
                Icon(
                    imageVector = if (sortAscending) Icons.Default.SortByAlpha else Icons.Default.VerticalAlignBottom, 
                    contentDescription = "Ordenar"
                )
            }

            IconButton(onClick = onEdit, enabled = selected != null) {
                Icon(Icons.Default.Edit, "Editar")
            }
            IconButton(onClick = onDuplicate, enabled = selected != null) {
                Icon(Icons.Default.ContentCopy, "Duplicar")
            }
            IconButton(onClick = onUpload, enabled = selected != null) {
                Icon(Icons.Rounded.CloudUpload, "Enviar", tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onDelete, enabled = selected != null) {
                Icon(Icons.Default.Delete, "Excluir", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun TableCell(
    text: String,
    width: Dp,
    modifier: Modifier = Modifier,
    isHeader: Boolean = false,
    textAlign: TextAlign = TextAlign.Center,
    textColor: Color = Color.Unspecified
) {
    Box(
        modifier = modifier
            .width(width)
            .height(40.dp)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .padding(horizontal = 8.dp),
        contentAlignment = if (textAlign == TextAlign.Start) Alignment.CenterStart else Alignment.Center
    ) {
        Text(
            text = text,
            style = if (isHeader) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
            fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontFamily = if (isHeader) FontFamily.Monospace else FontFamily.Monospace,
            fontSize = if (isHeader) 12.sp else 11.sp,
            textAlign = textAlign
        )
    }
}

@Composable
fun VariableEditDialog(
    variable: RobotVariable,
    viewModel: RobotDashboardViewModel?,
    isNew: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (RobotVariable) -> Unit
) {
    var name by remember { mutableStateOf(variable.name) }
    var type by remember { mutableStateOf(variable.type) }
    
    val initialValues = remember(variable.value) { 
        val parts = variable.value.split(Regex("\\s+")).filter { it.isNotBlank() }
        List(8) { parts.getOrNull(it) ?: "0.000" }
    }
    val componentValues = remember { mutableStateListOf(*initialValues.toTypedArray()) }

    val nameError = if (viewModel != null) remember(name) { viewModel.validateVariableName(name, isNew) } else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Nova Variável" else "Editar: ${variable.name}") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome") },
                    isError = nameError != null,
                    supportingText = { nameError?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                if (type == "FRAME") {
                    Text("Componentes Espaciais", style = MaterialTheme.typography.labelMedium)
                    val labels = listOf("X", "Y", "Z", "O", "A", "T", "JT7", "JT8")
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        labels.chunked(2).forEachIndexed { rowIndex, pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEachIndexed { colIndex, label ->
                                    val index = rowIndex * 2 + colIndex
                                    OutlinedTextField(
                                        value = componentValues[index],
                                        onValueChange = { componentValues[index] = it },
                                        label = { Text(label) },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                }
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = componentValues[0],
                        onValueChange = { componentValues[0] = it },
                        label = { Text("Valor") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { 
                    val newValue = if (type == "FRAME") componentValues.joinToString(" ") else componentValues[0]
                    onSave(variable.copy(name = name, value = newValue, type = type))
                },
                enabled = nameError == null && name.isNotBlank()
            ) { Text("Salvar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
fun VariableDuplicateDialog(
    variable: RobotVariable,
    viewModel: RobotDashboardViewModel?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var newName by remember { mutableStateOf("${variable.name}_copy") }
    val error = if (viewModel != null) remember(newName) { viewModel.validateProgramName(newName) } else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Duplicar Variável") },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newValue ->
                    newName = newValue
                },
                label = { Text("Novo Nome") },
                isError = error != null,
                supportingText = { error?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(newName) }, enabled = error == null) { Text("Confirmar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
fun DashboardHome(
    robot: Robot?,
    backup: Backup?,
    onFeatureClick: (DashboardFeature) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Informações do Backup", 
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = backup?.backupName ?: "Arquivo desconhecido",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(12.dp))

                val date = remember(backup?.timestamp) {
                    if (backup != null) {
                        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(backup.timestamp))
                    } else "-"
                }
                
                val lineCount = remember(backup?.content) {
                    backup?.content?.lines()?.size ?: 0
                }

                StatusItem("Robô Origem", robot?.name ?: "-")
                StatusItem("Data Criação", date)
                StatusItem("Total de Linhas", lineCount.toString())
            }
        }

        Text("Navegação no Arquivo", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.heightIn(max = 800.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            userScrollEnabled = false
        ) {
            item {
                StatSquare(
                    title = "Programas",
                    count = backup?.programsCount ?: 0,
                    icon = Icons.Rounded.Code,
                    onClick = { onFeatureClick(DashboardFeature.Programs) }
                )
            }
            item {
                StatSquare(
                    title = "Variáveis",
                    count = backup?.variablesCount ?: 0,
                    icon = Icons.Rounded.Tune,
                    onClick = { onFeatureClick(DashboardFeature.Variables) }
                )
            }
            item {
                FeatureSquare(feature = DashboardFeature.FullCode) { onFeatureClick(DashboardFeature.FullCode) }
            }
            item {
                FeatureSquare(feature = DashboardFeature.DataBank) { onFeatureClick(DashboardFeature.DataBank) }
            }
        }
    }
}

@Composable
fun StatSquare(title: String, count: Int, icon: ImageVector, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(count.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun FeatureSquare(feature: DashboardFeature, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(feature.icon, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                feature.label, 
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun StatusItem(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun LogsPanel(logs: List<String>) {
    val scrollState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) scrollState.animateScrollToItem(0)
    }
    LazyColumn(
        state = scrollState,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).navigationBarsPadding(),
        contentPadding = PaddingValues(16.dp)
    ) {
        items(logs) { log ->
            Text(
                text = log,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = if (log.contains("sucesso", true)) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            )
        }
    }
}

@Composable
fun ProgramsPanel(
    programs: List<RobotProgram>, 
    onProgramClick: (RobotProgram) -> Unit,
    onUpload: (RobotProgram) -> Unit,
    onDuplicate: (RobotProgram) -> Unit,
    onShare: (RobotProgram) -> Unit,
    onDelete: (RobotProgram) -> Unit
) {
    val expandedSections = remember { mutableStateMapOf<String, Boolean>() }
    
    val groupedPrograms = remember(programs) {
        programs.groupBy { it.group }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        groupedPrograms.forEach { (groupName, programsInGroup) ->
            item {
                val isExpanded = expandedSections[groupName] ?: true
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expandedSections[groupName] = !isExpanded }
                ) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Grupo: $groupName",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            if (expandedSections[groupName] ?: true) {
                items(programsInGroup) { program ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 32.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
                            .clickable { onProgramClick(program) },
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        shape = MaterialTheme.shapes.small,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(program.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                Text(program.size, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row {
                                IconButton(onClick = { onProgramClick(program) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Rounded.Visibility, "Ver", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { onUpload(program) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Rounded.CloudUpload, "Enviar", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { onDuplicate(program) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.ContentCopy, "Duplicar", modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = { onShare(program) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Share, "Compartilhar", modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = { onDelete(program) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Delete, "Excluir", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RobotSelectionDialog(
    title: String,
    itemName: String,
    robots: List<Robot>,
    onSelect: (Robot) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("Escolha o destino para '$itemName'", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(robots) { r ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { onSelect(r) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.SmartToy, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(r.name, fontWeight = FontWeight.Bold)
                                    Text(r.ip, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
fun DuplicateProgramDialog(
    program: RobotProgram,
    viewModel: RobotDashboardViewModel?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var newName by remember { mutableStateOf("${program.name}_copy") }
    val error = if (viewModel != null) remember(newName) { viewModel.validateProgramName(newName) } else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Duplicar Programa") },
        text = {
            Column {
                Text("Digite o novo nome para o programa:", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newValue ->
                        if (newValue.length <= 15) { // Limite comum em nomes de programas AS
                            newName = newValue
                        }
                    },
                    label = { Text("Nome do Programa") },
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(newName) },
                enabled = error == null && newName.isNotBlank()
            ) { Text("Duplicar") }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) { Text("Cancelar") }
        }
    )
}
