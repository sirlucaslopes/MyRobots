package my.robots.feature.dashboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import my.robots.core.model.Backup
import my.robots.core.model.Manufacturer
import my.robots.core.model.QuickCommand
import my.robots.core.model.Robot
import my.robots.core.network.RobotStatusResponse
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Seções do painel do robô. "Logs" é o terminal (o nome ficou por histórico).
 * Cada uma tem o título e o ícone mostrados na tela.
 */
enum class DashboardFeature(val label: String, val icon: ImageVector) {
    Logs("Terminal", Icons.AutoMirrored.Rounded.Article),
    Programs("Programas", Icons.Rounded.Code),
    Variables("Variáveis", Icons.Rounded.Tune),
    FullCode("Código AS", Icons.Rounded.Description),
    DataBank("Data Bank", Icons.Rounded.Storage),
    ErrorLog("Log de Erros", Icons.Rounded.ErrorOutline),
    OperationLog("Log de Operação", Icons.Rounded.History),
    ProgramEditLog("Log de Edição", Icons.Default.Edit)
}

/**
 * Tela principal de UM robô.
 *
 * Tem uma "home" com as informações do backup e cartões de acesso rápido, e
 * as seções: Terminal, Programas, Variáveis, Código AS (abre em outra tela) e Data Bank.
 *
 * - viewModel: dados e ações (pode ser null em pré-visualização).
 * - initialFeature: seção que já abre direto (ex.: Terminal, vindo da lista).
 * - onProgramClick / onVariablesClick / onFullCodeClick: abrem o editor de código.
 * - onNavigateToRobot: vai para o painel de OUTRO robô (após enviar um item para ele).
 * - onQuickCommandsClick: abre a biblioteca de comandos rápidos.
 * - mockRobot / mockStatus: dados falsos só para pré-visualização.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RobotDashboardScreen(
    viewModel: RobotDashboardViewModel? = null,
    onViewBackups: () -> Unit = {},
    onProgramClick: (my.robots.core.model.BackupSummary, String) -> Unit = { _, _ -> },
    onVariablesClick: (my.robots.core.model.BackupSummary) -> Unit = {},
    onBack: () -> Unit = {},
    onFullCodeClick: (my.robots.core.model.BackupSummary) -> Unit = {},
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
    val lineCountState = if (viewModel != null) viewModel.lineCount.collectAsState() else remember { mutableStateOf(0) }
    val errorLogState = if (viewModel != null) viewModel.errorLog.collectAsState() else remember { mutableStateOf(emptyList<RobotErrorLogEntry>()) }
    val operationLogState = if (viewModel != null) viewModel.operationLog.collectAsState() else remember { mutableStateOf(emptyList<RobotLogEntry>()) }
    val programEditLogState = if (viewModel != null) viewModel.programEditLog.collectAsState() else remember { mutableStateOf(emptyList<RobotLogEntry>()) }

    val robot by robotState
    val terminalOutput by terminalOutputState
    val programs by programsState
    val variables by variablesState
    val dataBankEntries by dataBankEntriesState
    val latestBackup by latestBackupState
    val isLoading by isLoadingState
    val allRobots by allRobotsState
    val quickCommands by quickCommandsState
    val lineCount by lineCountState
    val errorLog by errorLogState
    val operationLog by operationLogState
    val programEditLog by programEditLogState

    // Seção aberta agora (null = home). Fica guardada mesmo se a tela for recriada.
    var activeFeature by rememberSaveable { mutableStateOf<DashboardFeature?>(initialFeature) }
    
    // Se chegar um pedido novo de seção (ex.: voltando com outros argumentos), abre essa seção.
    LaunchedEffect(initialFeature) {
        if (initialFeature != null) {
            activeFeature = initialFeature
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Estados para diálogos
    // Itens escolhidos para enviar, duplicar ou excluir (cada um abre uma janela).
    // Programas: nomes marcados na seção Programas (checkbox de cada linha).
    var selectedProgramNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var programsToUpload by remember { mutableStateOf<List<RobotProgram>?>(null) }
    var programsToDelete by remember { mutableStateOf<List<RobotProgram>?>(null) }
    var variableToUpload by remember { mutableStateOf<RobotVariable?>(null) }
    var dataBankToUpload by remember { mutableStateOf<List<RobotDataBankEntry>?>(null) }
    var programToDuplicate by remember { mutableStateOf<RobotProgram?>(null) }
    var variableToDelete by remember { mutableStateOf<RobotVariable?>(null) }
    var dataBankToDelete by remember { mutableStateOf<RobotDataBankEntry?>(null) }

    // Busca nos três logs do controlador (Erros, Operação, Edição).
    var isLogSearchActive by remember { mutableStateOf(false) }
    var logSearchQuery by remember { mutableStateOf("") }
    val isLogFeature = activeFeature == DashboardFeature.ErrorLog ||
        activeFeature == DashboardFeature.OperationLog ||
        activeFeature == DashboardFeature.ProgramEditLog

    // Sai da seção Programas -> esquece a seleção, para não reaparecer marcada da próxima vez.
    // Sai de um dos logs -> fecha e limpa a busca.
    LaunchedEffect(activeFeature) {
        if (activeFeature != DashboardFeature.Programs) {
            selectedProgramNames = emptySet()
        }
        if (!isLogFeature) {
            isLogSearchActive = false
            logSearchQuery = ""
        }
    }

    val filteredErrorLog = remember(errorLog, logSearchQuery) {
        if (logSearchQuery.isBlank()) errorLog else errorLog.filter { it.raw.contains(logSearchQuery, ignoreCase = true) }
    }
    val filteredOperationLog = remember(operationLog, logSearchQuery) {
        if (logSearchQuery.isBlank()) operationLog else operationLog.filter { it.raw.contains(logSearchQuery, ignoreCase = true) }
    }
    val filteredProgramEditLog = remember(programEditLog, logSearchQuery) {
        if (logSearchQuery.isBlank()) programEditLog else programEditLog.filter { it.raw.contains(logSearchQuery, ignoreCase = true) }
    }

    // Botão voltar: se estamos numa seção aberta pelo usuário, volta para a home do painel.
    // Se a seção já era a inicial (ex.: Terminal vindo da lista), o voltar sai da tela.
    val canGoBackToHome = activeFeature != null && activeFeature != initialFeature
    
    BackHandler(enabled = canGoBackToHome) {
        activeFeature = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        if (isLogFeature && isLogSearchActive) {
                            OutlinedTextField(
                                value = logSearchQuery,
                                onValueChange = { logSearchQuery = it },
                                modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
                                placeholder = { Text("Pesquisar...") },
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontSize = 16.sp)
                            )
                        } else {
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
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (isLogFeature && isLogSearchActive) {
                                isLogSearchActive = false
                                logSearchQuery = ""
                            } else if (canGoBackToHome) {
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
                                    // se o gerenciador de arquivos não abrir, vai para o histórico de backups
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
                        } else if (activeFeature == DashboardFeature.Programs) {
                            // Seleciona/desmarca todos os programas de uma vez.
                            val allSelected = programs.isNotEmpty() && selectedProgramNames.size == programs.size
                            IconButton(onClick = {
                                selectedProgramNames = if (allSelected) emptySet() else programs.map { it.name }.toSet()
                            }) {
                                Icon(
                                    imageVector = if (allSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                    contentDescription = if (allSelected) "Desmarcar Todos" else "Selecionar Todos"
                                )
                            }

                            val selectedPrograms = programs.filter { it.name in selectedProgramNames }
                            val hasSelection = selectedPrograms.isNotEmpty()
                            val disabledTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)

                            // Só os programas marcados são empacotados e enviados.
                            IconButton(onClick = { programsToUpload = selectedPrograms }, enabled = hasSelection) {
                                Icon(
                                    imageVector = Icons.Rounded.CloudUpload,
                                    contentDescription = "Enviar Selecionados",
                                    tint = if (hasSelection) MaterialTheme.colorScheme.primary else disabledTint
                                )
                            }

                            IconButton(
                                onClick = {
                                    scope.launch {
                                        val content = viewModel?.packProgramsContent(selectedPrograms) ?: ""
                                        if (content.isNotBlank()) {
                                            shareProgramsContent(context, selectedPrograms, content)
                                        }
                                    }
                                },
                                enabled = hasSelection
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Compartilhar Selecionados",
                                    tint = if (hasSelection) MaterialTheme.colorScheme.onSurface else disabledTint
                                )
                            }

                            IconButton(onClick = { programsToDelete = selectedPrograms }, enabled = hasSelection) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Excluir Selecionados",
                                    tint = if (hasSelection) MaterialTheme.colorScheme.error else disabledTint
                                )
                            }
                        } else if (isLogFeature) {
                            if (isLogSearchActive) {
                                IconButton(onClick = { isLogSearchActive = false; logSearchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Fechar Busca")
                                }
                            } else {
                                IconButton(onClick = { isLogSearchActive = true }) {
                                    Icon(Icons.Default.Search, contentDescription = "Pesquisar")
                                }
                            }
                        }
                    }
                )
            },
            // As bordas são zeradas aqui porque o terminal ajusta a parte de baixo (teclado)
            // sozinho.
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { padding ->
            // só o espaço da barra do topo é usado; o resto cada seção resolve
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
                            lineCount = lineCount,
                            dataBankCount = dataBankEntries.size,
                            errorLogCount = errorLog.size,
                            operationLogCount = operationLog.size,
                            programEditLogCount = programEditLog.size,
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
                            selectedNames = selectedProgramNames,
                            onToggleSelect = { name ->
                                selectedProgramNames = if (name in selectedProgramNames) {
                                    selectedProgramNames - name
                                } else {
                                    selectedProgramNames + name
                                }
                            },
                            onProgramClick = { prog ->
                                if (latestBackup != null) onProgramClick(latestBackup!!, prog.name)
                            },
                            onDuplicate = { prog -> programToDuplicate = prog }
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
                        DashboardFeature.FullCode -> { /* já tratado em onFeatureClick: abre o editor em outra tela */ }
                        DashboardFeature.ErrorLog -> ErrorLogPanel(
                            entries = filteredErrorLog,
                            emptyHint = if (errorLog.isEmpty()) {
                                "Nenhum erro registrado. Esse log só existe em backups feitos com SAVE/FULL no robô."
                            } else {
                                "Nenhum resultado para \"$logSearchQuery\"."
                            }
                        )
                        DashboardFeature.OperationLog -> LogPanel(
                            entries = filteredOperationLog,
                            emptyHint = if (operationLog.isEmpty()) {
                                "Nenhum registro de operação. Esse log só existe em backups feitos com SAVE/FULL no robô."
                            } else {
                                "Nenhum resultado para \"$logSearchQuery\"."
                            }
                        )
                        DashboardFeature.ProgramEditLog -> LogPanel(
                            entries = filteredProgramEditLog,
                            emptyHint = if (programEditLog.isEmpty()) {
                                "Nenhum registro de edição. Esse log só existe em backups feitos com SAVE/FULL no robô."
                            } else {
                                "Nenhum resultado para \"$logSearchQuery\"."
                            }
                        )
                    }
                }
                
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }

        // janelas de duplicar e de confirmar exclusão
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

        if (programsToDelete != null) {
            val toDelete = programsToDelete!!
            AlertDialog(
                onDismissRequest = { programsToDelete = null },
                title = { Text(if (toDelete.size == 1) "Excluir Programa" else "Excluir Programas") },
                text = {
                    val names = toDelete.joinToString(", ") { it.name }
                    Text("Tem certeza que deseja excluir ${if (toDelete.size == 1) "o programa" else "${toDelete.size} programas"} \"$names\"? Esta ação removerá o código do backup.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel?.deletePrograms(toDelete)
                            selectedProgramNames = emptySet()
                            programsToDelete = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Excluir") }
                },
                dismissButton = {
                    TextButton(onClick = { programsToDelete = null }) { Text("Cancelar") }
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

        // janelas para escolher o robô de destino ao enviar um item.
        // Se o destino é este mesmo robô, abre o terminal; se é outro, navega até o painel dele.
        if (programsToUpload != null) {
            val toUpload = programsToUpload!!
            RobotSelectionDialog(
                title = if (toUpload.size == 1) "Enviar Programa para qual Robô?" else "Enviar Programas para qual Robô?",
                itemName = if (toUpload.size == 1) toUpload[0].name else "${toUpload.size} programas selecionados",
                robots = allRobots,
                onSelect = { r ->
                    viewModel?.sendProgramsToRobot(toUpload, r)
                    val targetId = r.id
                    programsToUpload = null
                    selectedProgramNames = emptySet()
                    if (targetId == (robot?.id ?: -1)) {
                        activeFeature = DashboardFeature.Logs
                    } else {
                        onNavigateToRobot(targetId, -1, DashboardFeature.Logs)
                    }
                },
                onDismiss = { programsToUpload = null }
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

/**
 * Terminal do robô: caixa preta com texto verde (o que você enviou aparece em azul-claro).
 *
 * Cada letra digitada é enviada ao robô na hora, como num terminal de verdade;
 * apagar envia backspace. O Enter/enviar manda o fim de linha. O raio abre os comandos
 * rápidos e as setas enviam CIMA/BAIXO (histórico de comandos do robô).
 */
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
        // área do texto do terminal
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
                    // largura fixa e grande: linhas longas não quebram, dá para rolar de lado
                    modifier = Modifier.width(2000.dp) 
                ) {
                    items(output) { line ->
                        Text(
                            text = line,
                            // linhas que você enviou (começam com ">") em azul-claro; o resto em verde
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

        // campo de digitar e botões
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
                    // compara com o texto anterior: letras novas são enviadas uma a uma;
                    // se o texto diminuiu, envia um backspace para cada letra apagada
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
                        viewModel?.sendCommand("") // só o Enter: o texto já foi enviado letra por letra
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

/**
 * Seção Data Bank: tabela com as linhas da seção .sprdb do backup.
 *
 * A tabela tem uma coluna fixa à esquerda (checkbox, número e comentário) e as outras
 * colunas rolam para o lado. Dá para marcar várias linhas para enviar de uma vez.
 * A barra de cima tem: criar, ordenar, editar, duplicar, enviar e excluir.
 */
@Composable
fun DataBankPanel(
    entries: List<RobotDataBankEntry>,
    viewModel: RobotDashboardViewModel?,
    onUploadSelected: (List<RobotDataBankEntry>) -> Unit,
    onDelete: (RobotDataBankEntry) -> Unit
) {
    // Linha tocada (para editar/duplicar/excluir) e linhas marcadas com o checkbox (para enviar em lote).
    var selectedEntry by remember { mutableStateOf<RobotDataBankEntry?>(null) }
    val selectedEntries = remember { mutableStateListOf<String>() }
    
    var showEditDialog by remember { mutableStateOf<RobotDataBankEntry?>(null) }
    var showDuplicateDialog by remember { mutableStateOf<RobotDataBankEntry?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var sortAscending by remember { mutableStateOf(true) }

    // Lista ordenada pelo número (crescente ou decrescente).
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

    // Janela de edição da linha escolhida.
    // Janela de edição da variável escolhida.
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

    // Janela para pedir o número da cópia.
    // Janela para dar o nome da cópia.
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

    // Janela para criar uma linha nova.
    // Janela para criar uma variável nova (posição com 8 valores em zero).
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

/**
 * Barra de ferramentas do Data Bank: criar, ordenar, editar, duplicar, enviar e excluir.
 * Editar/duplicar/excluir só ligam com uma linha selecionada; "Enviar Seleção" aparece quando há checkboxes marcados.
 */
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

/**
 * Janela para criar ou editar uma linha do Data Bank.
 * Ao criar, o número é pedido e conferido (1 a 999, sem repetir). Ao editar, o número não muda.
 */
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

/**
 * Janela que pede o número da cópia de uma linha do Data Bank e confere se é válido.
 */
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

/**
 * Seção Variáveis: tabela com nome à esquerda (fixo) e os valores X, Y, Z, O, A, T, JT7, JT8 rolando de lado.
 * Variáveis de posição (FRAME) mostram um valor por coluna; as outras mostram o valor inteiro numa célula.
 * Nomes que começam com "!" aparecem em amarelo-escuro.
 */
@Composable
fun VariablesPanel(
    variables: List<RobotVariable>, 
    viewModel: RobotDashboardViewModel?,
    onUpload: (RobotVariable) -> Unit,
    onDelete: (RobotVariable) -> Unit
) {
    // Variável tocada e estados das janelas de editar/duplicar/criar.
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
                    // separa o valor em pedaços (um por coluna: X, Y, Z...)
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

/**
 * Barra de ferramentas das variáveis: criar, ordenar, editar, duplicar, enviar e excluir.
 */
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

/**
 * Uma célula das tabelas: caixa com borda e texto de uma linha em fonte de terminal. O cabeçalho fica em negrito.
 */
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

/**
 * Janela para criar ou editar uma variável.
 * Posição (FRAME): 8 campos (X, Y, Z, O, A, T, JT7, JT8). Outros tipos: um campo só.
 */
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

/**
 * Janela que pede o nome da cópia da variável e confere se o nome é válido.
 */
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

/**
 * Página inicial do painel: cartão com as informações do backup (nome, robô, data e total
 * de linhas) e os atalhos: Programas, Variáveis, Data Bank, Código AS e os três logs do
 * controlador (Erros, Operação, Edição) — esses três só têm registros quando o backup foi
 * feito com SAVE/FULL no robô; sem isso, aparecem zerados.
 */
@Composable
fun DashboardHome(
    robot: Robot?,
    backup: my.robots.core.model.BackupSummary?,
    lineCount: Int,
    dataBankCount: Int,
    errorLogCount: Int,
    operationLogCount: Int,
    programEditLogCount: Int,
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

                StatusItem("Robô Origem", robot?.name ?: "-")
                StatusItem("Data Criação", date)
                StatusItem("Total de Linhas", lineCount.toString())
            }
        }

        Text("Navegação no Arquivo", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.heightIn(max = 1400.dp),
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
                StatSquare(
                    title = "Data Bank",
                    count = dataBankCount,
                    icon = Icons.Rounded.Storage,
                    onClick = { onFeatureClick(DashboardFeature.DataBank) }
                )
            }
            item {
                FeatureSquare(feature = DashboardFeature.FullCode) { onFeatureClick(DashboardFeature.FullCode) }
            }
            item {
                StatSquare(
                    title = "Log de Erros",
                    count = errorLogCount,
                    icon = Icons.Rounded.ErrorOutline,
                    onClick = { onFeatureClick(DashboardFeature.ErrorLog) }
                )
            }
            item {
                StatSquare(
                    title = "Log de Operação",
                    count = operationLogCount,
                    icon = Icons.Rounded.History,
                    onClick = { onFeatureClick(DashboardFeature.OperationLog) }
                )
            }
            item {
                StatSquare(
                    title = "Log de Edição",
                    count = programEditLogCount,
                    icon = Icons.Default.Edit,
                    onClick = { onFeatureClick(DashboardFeature.ProgramEditLog) }
                )
            }
        }
    }
}

/**
 * Lista genérica de log (usada para Log de Erros, Operação e Edição): cada entrada vira um
 * cartão com o texto cru daquela linha (ou várias, no caso do ERRLOG). Sem entradas, mostra
 * o aviso de que esse log só existe em backups SAVE/FULL.
 */
@Composable
fun LogPanel(entries: List<RobotLogEntry>, emptyHint: String) {
    if (entries.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = emptyHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(entries) { entry -> LogEntryCard(entry) }
    }
}

/**
 * Um registro de log: mostra o texto exatamente como está no backup, em fonte de terminal.
 * Usado pelo Log de Operação e de Edição (uma linha por entrada) e, dentro do detalhe do
 * Log de Erros, como "Ver texto original" (várias linhas por entrada).
 */
@Composable
fun LogEntryCard(entry: RobotLogEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(
            text = entry.raw,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.padding(12.dp)
        )
    }
}

/**
 * Lista do Log de Erros: cada linha mostra só código + mensagem + data/hora (o que importa
 * para escanear rápido). Tocar abre o detalhe completo (`ErrorLogDetailDialog`).
 */
@Composable
fun ErrorLogPanel(entries: List<RobotErrorLogEntry>, emptyHint: String) {
    var selectedEntry by remember { mutableStateOf<RobotErrorLogEntry?>(null) }

    if (entries.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = emptyHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(entries) { entry ->
            ErrorLogSummaryCard(entry = entry, onClick = { selectedEntry = entry })
        }
    }

    if (selectedEntry != null) {
        ErrorLogDetailDialog(entry = selectedEntry!!, onDismiss = { selectedEntry = null })
    }
}

/**
 * Cartão resumido de um erro: código + mensagem e a data/hora. Tocar abre o detalhe.
 */
@Composable
fun ErrorLogSummaryCard(entry: RobotErrorLogEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (entry.errorCode.isNotBlank()) "(${entry.errorCode}) ${entry.errorMessage}" else "Erro sem código",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(entry.timestamp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Detalhe completo de um erro, em tela cheia e dividido por seções: estado no momento do
 * erro (sinal/velocidade/modo), programas em execução em cada robô/PC, a sequência de
 * operações que levou ao erro e as poses (atual/comando/final). "Ver texto original" mostra
 * o texto cru da entrada, para o caso de algum formato não ter batido com o parser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ErrorLogDetailDialog(entry: RobotErrorLogEntry, onDismiss: () -> Unit) {
    var showRaw by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("Detalhe do Erro") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Voltar")
                        }
                    }
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = if (entry.errorCode.isNotBlank()) "(${entry.errorCode}) ${entry.errorMessage}" else "Erro sem código",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                entry.timestamp,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }

                    Column {
                        Text("Estado no Momento do Erro", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(4.dp))
                        StatusItem("Sinal", entry.signal.ifBlank { "-" })
                        StatusItem("Velocidade", entry.speed.ifBlank { "-" })
                        StatusItem("Modo", entry.mode.ifBlank { "-" })
                    }

                    if (entry.programs.isNotEmpty()) {
                        Column {
                            Text("Programas em Execução", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(8.dp))
                            entry.programs.forEach { p -> ErrorLogProgramRow(p) }
                        }
                    }

                    if (entry.operations.isNotEmpty()) {
                        Column {
                            Text("Sequência de Operações", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(8.dp))
                            entry.operations.forEach { op ->
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                    Text(
                                        text = op.timestamp,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(140.dp)
                                    )
                                    Text(op.description, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }

                    if (entry.currentPose.isNotEmpty() || entry.commandPose.isNotEmpty() || entry.endPose.isNotEmpty()) {
                        Column {
                            Text("Poses (JT1-JT7)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(8.dp))
                            PoseRow("Atual", entry.currentPose)
                            PoseRow("Comando", entry.commandPose)
                            PoseRow("Final", entry.endPose)
                        }
                    }

                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { showRaw = !showRaw },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Ver Texto Original",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(if (showRaw) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                        }
                        if (showRaw) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LogEntryCard(entry = RobotLogEntry(index = entry.index, timestamp = entry.timestamp, raw = entry.raw))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Uma linha de "Programas em Execução" no detalhe do erro: lugar/programa à esquerda,
 * step e status à direita (vermelho quando parado).
 */
@Composable
fun ErrorLogProgramRow(program: RobotErrorLogProgram) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(10.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(program.place, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(program.program, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Step ${program.step}", style = MaterialTheme.typography.bodySmall)
                Text(
                    text = program.status,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (program.status.equals("STOP", ignoreCase = true)) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
        }
    }
}

/**
 * Uma linha de pose (Atual/Comando/Final) com os valores JT1-JT7 lado a lado, rolando
 * horizontalmente. "Sem dados" quando o backup não trouxe valores para essa pose.
 */
@Composable
fun PoseRow(label: String, values: List<String>) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        if (values.isEmpty()) {
            Text("Sem dados", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val jtLabels = listOf("JT1", "JT2", "JT3", "JT4", "JT5", "JT6", "JT7")
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                values.forEachIndexed { index, value ->
                    Column(modifier = Modifier.width(70.dp).padding(end = 4.dp)) {
                        Text(
                            text = jtLabels.getOrElse(index) { "V${index + 1}" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

/**
 * Cartão quadrado com ícone, título e um número (ex.: quantidade de programas). Tocar abre a seção.
 */
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

/**
 * Cartão quadrado com ícone e título de uma seção. Tocar abre a seção.
 */
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

/**
 * Uma linha de informação: nome à esquerda e valor em negrito à direita.
 */
@Composable
fun StatusItem(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

/**
 * Lista simples de logs. NÃO É USADA hoje (o terminal usa o TerminalPanel).
 * Fica aqui caso se queira mostrar os logs de teste.
 */
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

/**
 * Seção Programas: lista agrupada por grupo (cada grupo abre e fecha).
 *
 * Cada linha tem uma caixa de seleção; enviar, compartilhar e excluir agora ficam na
 * barra do topo da tela (ver o `actions` do `RobotDashboardScreen`) e operam só sobre os
 * programas marcados — selecionar nenhum desabilita esses três botões. Tocar na linha
 * (fora da caixa) ainda abre o programa no editor; "Duplicar" continua por linha, pois é
 * uma ação de um programa só.
 */
@Composable
fun ProgramsPanel(
    programs: List<RobotProgram>,
    selectedNames: Set<String>,
    onToggleSelect: (String) -> Unit,
    onProgramClick: (RobotProgram) -> Unit,
    onDuplicate: (RobotProgram) -> Unit
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
                    val isSelected = program.name in selectedNames
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 32.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
                            .clickable { onProgramClick(program) },
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        shape = MaterialTheme.shapes.small,
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect(program.name) })
                            Column(modifier = Modifier.weight(1f)) {
                                Text(program.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                if (program.comment.isNotBlank()) {
                                    Text(
                                        text = program.comment,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = buildString {
                                        append(program.size)
                                        append(" · ${program.lineCount} linhas")
                                        if (program.modifiedAt.isNotBlank()) append(" · ${program.modifiedAt}")
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { onProgramClick(program) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Rounded.Visibility, "Ver", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { onDuplicate(program) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.ContentCopy, "Duplicar", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Junta o texto dos programas selecionados num arquivo temporário e abre o menu de
 * compartilhar do Android (mesmo mecanismo usado no histórico de backups).
 */
private fun shareProgramsContent(context: Context, programs: List<RobotProgram>, content: String) {
    try {
        val fileName = if (programs.size == 1) {
            "${programs[0].name}.as"
        } else {
            "programas_${System.currentTimeMillis()}.as"
        }
        val cacheDir = File(context.cacheDir, "shared_backups")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val file = File(cacheDir, fileName)
        file.writeText(content)

        val contentUri = FileProvider.getUriForFile(context, "my.robots.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Compartilhar Programas"))
    } catch (e: Exception) {
        Toast.makeText(context, "Erro ao compartilhar: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

/**
 * Janela para escolher qual robô vai receber o item enviado (lista de todos os robôs).
 */
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

/**
 * Janela que pede o nome da cópia do programa e confere se é válido.
 * O nome tem no máximo 15 caracteres.
 */
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
                        if (newValue.length <= 15) { // nomes de programa AS costumam ter até 15 caracteres
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
