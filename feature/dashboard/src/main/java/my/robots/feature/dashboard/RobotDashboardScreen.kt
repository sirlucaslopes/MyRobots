package my.robots.feature.dashboard

import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Link
import my.robots.core.designsystem.BarAction
import my.robots.core.designsystem.AppTopBar
import my.robots.core.designsystem.ActionTone
import my.robots.core.designsystem.FormDialog
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
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
import my.robots.core.designsystem.HeartbeatDot
import my.robots.core.designsystem.RobotPickerSheet
import my.robots.core.designsystem.SendProgress
import my.robots.core.designsystem.label
import my.robots.core.model.HeartbeatState
import my.robots.core.common.FileUtil
import my.robots.core.common.ascode.RobotErrorLogEntry
import my.robots.core.common.ascode.RobotErrorLogProgram
import my.robots.core.common.ascode.ControllerMemory
import my.robots.core.common.ascode.DailyUsage
import my.robots.core.common.ascode.RobotHealth
import my.robots.core.common.ascode.RobotInfo
import my.robots.core.common.ascode.RobotLogEntry
import my.robots.core.model.Backup
import my.robots.core.model.Manufacturer
import my.robots.core.model.QuickCommand
import my.robots.core.model.Robot
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Seções do painel do robô. "Terminal" é o terminal ao vivo; os logs de verdade do
 * controlador são ErrorLog, OperationLog e ProgramEditLog.
 * Cada uma tem o título e o ícone mostrados na tela.
 */
enum class DashboardFeature(val label: String, val icon: ImageVector) {
    Terminal("Terminal", Icons.AutoMirrored.Rounded.Article),
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
 * - mockRobot: dados falsos só para pré-visualização.
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
    mockRobot: Robot? = null
) {
    val robotState = if (viewModel != null) viewModel.robot.collectAsState() else remember { mutableStateOf(mockRobot) }
    val terminalOutputState = if (viewModel != null) viewModel.terminalOutput.collectAsState() else remember { mutableStateOf(emptyList<String>()) }
    val programsState = if (viewModel != null) viewModel.programs.collectAsState() else remember { mutableStateOf(emptyList<RobotProgram>()) }
    val variablesState = if (viewModel != null) viewModel.variables.collectAsState() else remember { mutableStateOf(emptyList<RobotVariable>()) }
    val dataBankEntriesState = if (viewModel != null) viewModel.dataBankEntries.collectAsState() else remember { mutableStateOf(emptyList<RobotDataBankEntry>()) }
    val latestBackupState = if (viewModel != null) viewModel.latestBackup.collectAsState() else remember { mutableStateOf(null) }
    val isNewestBackupState = if (viewModel != null) viewModel.isShowingNewestBackup.collectAsState() else remember { mutableStateOf(true) }
    val isLoadingState = if (viewModel != null) viewModel.isLoading.collectAsState() else remember { mutableStateOf(false) }
    val allRobotsState = if (viewModel != null) viewModel.allRobots.collectAsState() else remember { mutableStateOf(emptyList<Robot>()) }
    val quickCommandsState = if (viewModel != null) viewModel.quickCommands.collectAsState() else remember { mutableStateOf(emptyList<QuickCommand>()) }
    val lineCountState = if (viewModel != null) viewModel.lineCount.collectAsState() else remember { mutableStateOf(0) }
    val errorLogState = if (viewModel != null) viewModel.errorLog.collectAsState() else remember { mutableStateOf(emptyList<RobotErrorLogEntry>()) }
    val pickerConnectedState = if (viewModel != null) viewModel.connectedIds.collectAsState() else remember { mutableStateOf(emptySet<Int>()) }
    val pickerHeartbeatsState = if (viewModel != null) viewModel.heartbeats.collectAsState() else remember { mutableStateOf(emptyMap<Int, HeartbeatState>()) }
    val sendProgressState = if (viewModel != null) viewModel.sendProgress.collectAsState() else remember { mutableStateOf(emptyMap<Int, SendProgress>()) }
    val robotInfoState = if (viewModel != null) viewModel.robotInfo.collectAsState() else remember { mutableStateOf(RobotInfo()) }
    val dailyUsageState = if (viewModel != null) viewModel.dailyUsage.collectAsState() else remember { mutableStateOf(emptyList<DailyUsage>()) }
    val axisLast30State = if (viewModel != null) viewModel.axisMoveHoursLast30.collectAsState() else remember { mutableStateOf(emptyList<Double>()) }
    val memoryState = if (viewModel != null) viewModel.controllerMemory.collectAsState() else remember { mutableStateOf<ControllerMemory?>(null) }
    val isReadingMemoryState = if (viewModel != null) viewModel.isReadingMemory.collectAsState() else remember { mutableStateOf(false) }
    val homeConnectedState = if (viewModel != null) viewModel.isConnected.collectAsState() else remember { mutableStateOf(false) }
    val foreignBackupsState = if (viewModel != null) viewModel.foreignBackups.collectAsState() else remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    val operationLogState = if (viewModel != null) viewModel.operationLog.collectAsState() else remember { mutableStateOf(emptyList<RobotLogEntry>()) }
    val programEditLogState = if (viewModel != null) viewModel.programEditLog.collectAsState() else remember { mutableStateOf(emptyList<RobotLogEntry>()) }

    val robot by robotState
    val terminalOutput by terminalOutputState
    val programs by programsState
    val variables by variablesState
    val dataBankEntries by dataBankEntriesState
    val latestBackup by latestBackupState
    val isNewestBackup by isNewestBackupState
    val isLoading by isLoadingState
    val allRobots by allRobotsState
    val quickCommands by quickCommandsState
    val lineCount by lineCountState
    val errorLog by errorLogState
    val robotInfo by robotInfoState
    val pickerConnected by pickerConnectedState
    val pickerHeartbeats by pickerHeartbeatsState
    val sendProgress by sendProgressState
    val dailyUsage by dailyUsageState
    val axisLast30 by axisLast30State
    val controllerMemory by memoryState
    val isReadingMemory by isReadingMemoryState
    val homeConnected by homeConnectedState
    val foreignBackups by foreignBackupsState
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
    var variableToUpload by remember { mutableStateOf<List<RobotVariable>?>(null) }
    var dataBankToUpload by remember { mutableStateOf<List<RobotDataBankEntry>?>(null) }
    var programToDuplicate by remember { mutableStateOf<RobotProgram?>(null) }
    var variableToDelete by remember { mutableStateOf<List<RobotVariable>?>(null) }
    // Variáveis: nomes marcados e grupos (tipos) fechados.
    var selectedVarNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var collapsedVarGroups by rememberSaveable { mutableStateOf(listOf<String>()) }
    // Data Bank: números marcados (checkbox de cada cartão) e as janelas de edição em lote/exclusão.
    var selectedDbNums by remember { mutableStateOf<Set<String>>(emptySet()) }
    var dataBankToBulkEdit by remember { mutableStateOf<List<RobotDataBankEntry>?>(null) }
    var dataBankToDelete by remember { mutableStateOf<List<RobotDataBankEntry>?>(null) }

    // Posição de rolagem de cada lista. Fica aqui (fora da seção aberta) e é "saveable", então
    // volta onde estava ao trocar de seção ou ao voltar do editor.
    val programsListState = rememberLazyListState()
    val variablesListState = rememberLazyListState()
    val dataBankListState = rememberLazyListState()
    val errorLogListState = rememberLazyListState()
    val operationLogListState = rememberLazyListState()
    val editLogListState = rememberLazyListState()
    // a home (cartões do robô, uso, backup e atalhos) também volta na mesma altura
    val homeScrollState = rememberScrollState()
    var collapsedProgramGroups by rememberSaveable { mutableStateOf(listOf<String>()) }

    // Busca nos três logs do controlador (Erros, Operação, Edição).
    var isLogSearchActive by remember { mutableStateOf(false) }
    var logSearchQuery by remember { mutableStateOf("") }
    // lupa das listas (Programas, Variáveis, Data Bank): campo embaixo da barra do topo
    var listSearchOpen by rememberSaveable { mutableStateOf(false) }
    var listSearchQuery by rememberSaveable { mutableStateOf("") }
    val isLogFeature = activeFeature == DashboardFeature.ErrorLog ||
        activeFeature == DashboardFeature.OperationLog ||
        activeFeature == DashboardFeature.ProgramEditLog

    // Sai da seção Programas ou Data Bank -> esquece a seleção, para não reaparecer marcada.
    // Sai de um dos logs -> fecha e limpa a busca.
    LaunchedEffect(activeFeature) {
        if (activeFeature != DashboardFeature.Programs) {
            selectedProgramNames = emptySet()
        }
        if (activeFeature != DashboardFeature.DataBank) {
            selectedDbNums = emptySet()
        }
        if (activeFeature != DashboardFeature.Variables) {
            selectedVarNames = emptySet()
        }
        if (activeFeature != DashboardFeature.Programs && activeFeature != DashboardFeature.Variables &&
            activeFeature != DashboardFeature.DataBank) {
            listSearchOpen = false
            listSearchQuery = ""
        }
        if (!isLogFeature) {
            isLogSearchActive = false
            logSearchQuery = ""
        }
    }

    val listQuery = listSearchQuery.trim()
    val shownPrograms = remember(programs, listQuery) {
        if (listQuery.isEmpty()) programs
        else programs.filter { p ->
            p.name.contains(listQuery, true) || p.comment.contains(listQuery, true) || p.group.contains(listQuery, true)
        }
    }
    val shownVariables = remember(variables, listQuery) {
        if (listQuery.isEmpty()) variables
        else variables.filter { v -> v.name.contains(listQuery, true) || v.value.contains(listQuery, true) }
    }
    val shownDataBank = remember(dataBankEntries, listQuery) {
        if (listQuery.isEmpty()) dataBankEntries
        else dataBankEntries.filter { e ->
            "DB${e.num}".contains(listQuery, true) || e.num == listQuery || e.comment.contains(listQuery, true) ||
                listOf(e.frate, e.pattern, e.atomize, e.hvolt, e.speed, e.jspeed).any { it == listQuery }
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
    // voltar fecha a busca da lista antes de sair da seção
    BackHandler(enabled = listSearchOpen) {
        listSearchOpen = false
        listSearchQuery = ""
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                val isTerminalConnected by (viewModel?.isConnected?.collectAsState() ?: remember { mutableStateOf(false) })
                val robotName = robot?.name ?: ""
                val title = when (activeFeature) {
                    null -> robot?.name ?: "Painel"
                    DashboardFeature.Programs -> "Programas"
                    DashboardFeature.Variables -> "Variáveis"
                    DashboardFeature.DataBank -> "Data Bank"
                    else -> activeFeature!!.label
                }
                fun counted(total: Int, noun: String, marked: Int) =
                    listOf(robotName, "$total $noun", if (marked > 0) "$marked marcados" else "")
                        .filter { it.isNotBlank() }.joinToString(" · ")
                val subtitle = when (activeFeature) {
                    null -> robot?.project
                    DashboardFeature.Programs -> counted(programs.size, "programas", selectedProgramNames.size)
                    DashboardFeature.Variables -> counted(variables.size, "variáveis", selectedVarNames.size)
                    DashboardFeature.DataBank -> counted(dataBankEntries.size, "linhas", selectedDbNums.size)
                    DashboardFeature.Terminal -> listOf(robotName, if (isTerminalConnected) "conectado" else "desconectado")
                        .filter { it.isNotBlank() }.joinToString(" · ")
                    else -> robotName
                }

                // lupa das listas (Programas, Variáveis, Data Bank)
                val searchAction = BarAction(
                    Icons.Default.Search, "Pesquisar",
                    selected = listSearchOpen,
                    onClick = {
                        listSearchOpen = !listSearchOpen
                        if (!listSearchOpen) listSearchQuery = ""
                    }
                )
                fun markAllAction(allMarked: Boolean, onClick: () -> Unit) = BarAction(
                    if (allMarked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    if (allMarked) "Desmarcar" else "Marcar todos",
                    selected = allMarked,
                    onClick = onClick
                )

                val actions: List<BarAction> = when (activeFeature) {
                    DashboardFeature.Terminal -> listOf(
                        BarAction(
                            if (isTerminalConnected) Icons.Rounded.LinkOff else Icons.Rounded.Link,
                            if (isTerminalConnected) "Desconectar" else "Conectar",
                            tone = if (isTerminalConnected) ActionTone.Success else ActionTone.Primary,
                            onClick = { viewModel?.toggleConnection() }
                        ),
                        BarAction(Icons.Rounded.Folder, "Arquivos", onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW)
                                // pasta dos arquivos atual: Documentos/MyRobots ou a escolhida pelo usuário
                                val rootUri = viewModel?.filesFolderUri()
                                if (rootUri == null) {
                                    onViewBackups()
                                } else {
                                    intent.setDataAndType(rootUri, DocumentsContract.Document.MIME_TYPE_DIR)
                                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    context.startActivity(intent)
                                }
                            } catch (e: Exception) {
                                // se o gerenciador de arquivos não abrir, vai para o histórico de backups
                                onViewBackups()
                            }
                        }),
                        BarAction(Icons.Default.DeleteSweep, "Limpar", tone = ActionTone.Danger, onClick = { viewModel?.clearTerminal() })
                    )
                    DashboardFeature.Programs -> {
                        val allMarked = shownPrograms.isNotEmpty() && shownPrograms.all { it.name in selectedProgramNames }
                        val selectedPrograms = programs.filter { it.name in selectedProgramNames }
                        val has = selectedPrograms.isNotEmpty()
                        listOf(
                            searchAction,
                            // marca/desmarca os que aparecem (com a busca, só os achados)
                            markAllAction(allMarked) {
                                val names = shownPrograms.map { it.name }.toSet()
                                selectedProgramNames = if (allMarked) selectedProgramNames - names else selectedProgramNames + names
                            },
                            BarAction(Icons.Rounded.CloudUpload, "Enviar", enabled = has, tone = ActionTone.Primary,
                                onClick = { programsToUpload = selectedPrograms }),
                            BarAction(Icons.Default.Share, "Compartilhar", enabled = has, onClick = {
                                scope.launch {
                                    val content = viewModel?.packProgramsContent(selectedPrograms) ?: ""
                                    if (content.isNotBlank()) {
                                        val name = if (selectedPrograms.size == 1) "${selectedPrograms[0].name}.as"
                                            else "programas_${System.currentTimeMillis()}.as"
                                        shareTextFile(context, name, content, "Compartilhar Programas")
                                    }
                                }
                            }),
                            BarAction(Icons.Default.Delete, "Excluir", enabled = has, tone = ActionTone.Danger,
                                onClick = { programsToDelete = selectedPrograms })
                        )
                    }
                    DashboardFeature.Variables -> {
                        val allMarked = shownVariables.isNotEmpty() && shownVariables.all { it.name in selectedVarNames }
                        val selectedVars = variables.filter { it.name in selectedVarNames }
                        val has = selectedVars.isNotEmpty()
                        listOf(
                            searchAction,
                            markAllAction(allMarked) {
                                val names = shownVariables.map { it.name }.toSet()
                                selectedVarNames = if (allMarked) selectedVarNames - names else selectedVarNames + names
                            },
                            BarAction(Icons.Rounded.CloudUpload, "Enviar", enabled = has, tone = ActionTone.Primary,
                                onClick = { variableToUpload = selectedVars }),
                            BarAction(Icons.Default.Share, "Compartilhar", enabled = has, onClick = {
                                scope.launch {
                                    val vm = viewModel ?: return@launch
                                    val content = vm.variablesContent(selectedVars)
                                    if (content.isNotBlank()) {
                                        shareTextFile(context, vm.variablesFileName(selectedVars), content, "Compartilhar Variáveis")
                                    }
                                }
                            }),
                            BarAction(Icons.Default.Delete, "Excluir", enabled = has, tone = ActionTone.Danger,
                                onClick = { variableToDelete = selectedVars })
                        )
                    }
                    DashboardFeature.DataBank -> {
                        val allMarked = shownDataBank.isNotEmpty() && shownDataBank.all { it.num in selectedDbNums }
                        val selectedDb = dataBankEntries.filter { it.num in selectedDbNums }.sortedBy { it.num.toIntOrNull() ?: 0 }
                        val has = selectedDb.isNotEmpty()
                        listOf(
                            searchAction,
                            markAllAction(allMarked) {
                                val nums = shownDataBank.map { it.num }.toSet()
                                selectedDbNums = if (allMarked) selectedDbNums - nums else selectedDbNums + nums
                            },
                            // muda as colunas escolhidas em todas as linhas marcadas de uma vez
                            BarAction(Icons.Default.EditNote, "Editar", enabled = has, onClick = { dataBankToBulkEdit = selectedDb }),
                            BarAction(Icons.Rounded.CloudUpload, "Enviar", enabled = has, tone = ActionTone.Primary,
                                onClick = { dataBankToUpload = selectedDb }),
                            BarAction(Icons.Default.Share, "Compartilhar", enabled = has, onClick = {
                                viewModel?.let { vm ->
                                    shareTextFile(context, vm.dataBankFileName(selectedDb), vm.dataBankContent(selectedDb), "Compartilhar Data Bank")
                                }
                            }),
                            BarAction(Icons.Default.Delete, "Excluir", enabled = has, tone = ActionTone.Danger,
                                onClick = { dataBankToDelete = selectedDb })
                        )
                    }
                    else -> if (isLogFeature) listOf(
                        BarAction(Icons.Default.Search, "Pesquisar", selected = isLogSearchActive, onClick = {
                            isLogSearchActive = !isLogSearchActive
                            if (!isLogSearchActive) logSearchQuery = ""
                        })
                    ) else emptyList()
                }

                AppTopBar(
                    title = title,
                    subtitle = subtitle,
                    onBack = {
                        if (isLogFeature && isLogSearchActive) {
                            isLogSearchActive = false
                            logSearchQuery = ""
                        } else if (canGoBackToHome) {
                            activeFeature = null
                        } else {
                            onBack()
                        }
                    },
                    // itens pouco usados da página inicial do robô
                    menu = if (activeFeature == null) { close ->
                        DropdownMenuItem(
                            text = { Text("Ver arquivo completo") },
                            leadingIcon = { Icon(DashboardFeature.FullCode.icon, contentDescription = null) },
                            enabled = latestBackup != null,
                            onClick = {
                                close()
                                latestBackup?.let { onFullCodeClick(it) }
                            }
                        )
                    } else null,
                    actions = actions,
                    below = if (isLogFeature && isLogSearchActive) {
                        {
                            SearchFieldRow(
                                query = logSearchQuery,
                                onQuery = { logSearchQuery = it },
                                count = null,
                                onClose = { isLogSearchActive = false; logSearchQuery = "" }
                            )
                        }
                    } else null
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
                            isNewestBackup = isNewestBackup,
                            robotInfo = robotInfo,
                            errorLog = errorLog,
                            dailyUsage = dailyUsage,
                            axisMoveHoursLast30 = axisLast30,
                            foreignBackups = foreignBackups,
                            memory = controllerMemory,
                            isConnected = homeConnected,
                            heartbeat = pickerHeartbeats[robot?.id ?: -1] ?: HeartbeatState.DISCONNECTED,
                            onToggleConnection = { viewModel?.toggleConnection() },
                            scrollState = homeScrollState,
                            isReadingMemory = isReadingMemory,
                            onReadMemory = { viewModel?.readMemoryNow() },
                            lineCount = lineCount,
                            dataBankCount = dataBankEntries.size,
                            errorLogCount = errorLog.size,
                            operationLogCount = operationLog.size,
                            programEditLogCount = programEditLog.size,
                            onViewBackups = onViewBackups,
                            onFeatureClick = { selected ->
                                if (selected == DashboardFeature.FullCode) {
                                    if (latestBackup != null) onFullCodeClick(latestBackup!!)
                                } else {
                                    activeFeature = selected
                                    onFeatureClick(selected)
                                }
                            }
                        )
                        DashboardFeature.Terminal -> TerminalPanel(
                            output = terminalOutput,
                            quickCommands = quickCommands,
                            viewModel = viewModel,
                            onQuickCommandsClick = onQuickCommandsClick
                        )
                        DashboardFeature.Programs -> WithListSearch(
                            open = listSearchOpen, query = listSearchQuery, onQuery = { listSearchQuery = it },
                            shown = shownPrograms.size, total = programs.size,
                            onClose = { listSearchOpen = false; listSearchQuery = "" }
                        ) { ProgramsPanel(
                            programs = shownPrograms,
                            listState = programsListState,
                            collapsedGroups = collapsedProgramGroups.toSet(),
                            onToggleGroup = { g ->
                                collapsedProgramGroups = if (g in collapsedProgramGroups) collapsedProgramGroups - g else collapsedProgramGroups + g
                            },
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
                        ) }
                        DashboardFeature.Variables -> WithListSearch(
                            open = listSearchOpen, query = listSearchQuery, onQuery = { listSearchQuery = it },
                            shown = shownVariables.size, total = variables.size,
                            onClose = { listSearchOpen = false; listSearchQuery = "" }
                        ) { VariablesPanel(
                            variables = shownVariables,
                            selectedNames = selectedVarNames,
                            onToggleSelect = { name ->
                                selectedVarNames = if (name in selectedVarNames) selectedVarNames - name else selectedVarNames + name
                            },
                            collapsedGroups = collapsedVarGroups.toSet(),
                            onToggleGroup = { g ->
                                collapsedVarGroups = if (g in collapsedVarGroups) collapsedVarGroups - g else collapsedVarGroups + g
                            },
                            listState = variablesListState,
                            viewModel = viewModel
                        ) }
                        DashboardFeature.DataBank -> WithListSearch(
                            open = listSearchOpen, query = listSearchQuery, onQuery = { listSearchQuery = it },
                            shown = shownDataBank.size, total = dataBankEntries.size,
                            onClose = { listSearchOpen = false; listSearchQuery = "" }
                        ) { DataBankPanel(
                            entries = shownDataBank,
                            selectedNums = selectedDbNums,
                            onToggleSelect = { num ->
                                selectedDbNums = if (num in selectedDbNums) selectedDbNums - num else selectedDbNums + num
                            },
                            listState = dataBankListState,
                            viewModel = viewModel
                        ) }
                        DashboardFeature.FullCode -> { /* já tratado em onFeatureClick: abre o editor em outra tela */ }
                        DashboardFeature.ErrorLog -> ErrorLogPanel(
                            entries = filteredErrorLog,
                            listState = errorLogListState,
                            emptyHint = if (errorLog.isEmpty()) {
                                "Nenhum erro registrado. Esse log só existe em backups feitos com SAVE/FULL no robô."
                            } else {
                                "Nenhum resultado para \"$logSearchQuery\"."
                            }
                        )
                        DashboardFeature.OperationLog -> LogPanel(
                            entries = filteredOperationLog,
                            listState = operationLogListState,
                            emptyHint = if (operationLog.isEmpty()) {
                                "Nenhum registro de operação. Esse log só existe em backups feitos com SAVE/FULL no robô."
                            } else {
                                "Nenhum resultado para \"$logSearchQuery\"."
                            }
                        )
                        DashboardFeature.ProgramEditLog -> LogPanel(
                            entries = filteredProgramEditLog,
                            listState = editLogListState,
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
            val toDelete = variableToDelete!!
            AlertDialog(
                onDismissRequest = { variableToDelete = null },
                title = { Text("Excluir variáveis") },
                text = {
                    Text(
                        if (toDelete.size == 1) "Tem certeza que deseja excluir a variável \"${toDelete[0].name}\"?"
                        else "Tem certeza que deseja excluir ${toDelete.size} variáveis (${toDelete.joinToString { it.name }})?"
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel?.deleteVariables(toDelete)
                            selectedVarNames = selectedVarNames - toDelete.map { it.name }.toSet()
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
            val toDelete = dataBankToDelete!!
            AlertDialog(
                onDismissRequest = { dataBankToDelete = null },
                title = { Text("Excluir Data Bank") },
                text = {
                    Text(
                        if (toDelete.size == 1) "Tem certeza que deseja excluir o registro DB${toDelete[0].num}?"
                        else "Tem certeza que deseja excluir ${toDelete.size} registros (${toDelete.joinToString { "DB" + it.num }})?"
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel?.deleteDataBankEntries(toDelete)
                            selectedDbNums = selectedDbNums - toDelete.map { it.num }.toSet()
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

        if (dataBankToBulkEdit != null) {
            DataBankBulkEditDialog(
                entries = dataBankToBulkEdit!!,
                onDismiss = { dataBankToBulkEdit = null },
                onApply = { updated ->
                    viewModel?.updateDataBankEntries(updated)
                    dataBankToBulkEdit = null
                }
            )
        }

        // Listas para escolher os robôs de destino de um envio (agrupadas por projeto, com LED).
        // Marcam-se um ou mais robôs; cada um é conectado (se preciso), espera o login e recebe
        // o arquivo + LOAD. O andamento aparece robô por robô na própria lista.
        if (programsToUpload != null) {
            val toUpload = programsToUpload!!
            RobotPickerSheet(
                title = if (toUpload.size == 1) "Enviar programa" else "Enviar programas",
                itemName = if (toUpload.size == 1) toUpload[0].name else "${toUpload.size} programas selecionados",
                robots = allRobots,
                connectedIds = pickerConnected,
                heartbeats = pickerHeartbeats,
                progress = sendProgress,
                onSend = { targets -> viewModel?.sendProgramsToRobots(toUpload, targets) },
                onDismiss = {
                    programsToUpload = null
                    selectedProgramNames = emptySet()
                    viewModel?.clearSendProgress()
                }
            )
        }

        if (variableToUpload != null) {
            val toUpload = variableToUpload!!
            RobotPickerSheet(
                title = if (toUpload.size == 1) "Enviar variável" else "Enviar variáveis",
                itemName = if (toUpload.size == 1) toUpload[0].name else "${toUpload.size} variáveis selecionadas",
                robots = allRobots,
                connectedIds = pickerConnected,
                heartbeats = pickerHeartbeats,
                progress = sendProgress,
                onSend = { targets -> viewModel?.sendVariablesToRobots(toUpload, targets) },
                onDismiss = {
                    variableToUpload = null
                    selectedVarNames = emptySet()
                    viewModel?.clearSendProgress()
                }
            )
        }

        if (dataBankToUpload != null) {
            val toUpload = dataBankToUpload!!
            RobotPickerSheet(
                title = "Enviar Data Bank",
                itemName = if (toUpload.size == 1) "DB${toUpload[0].num}" else "${toUpload.size} registros selecionados",
                robots = allRobots,
                connectedIds = pickerConnected,
                heartbeats = pickerHeartbeats,
                progress = sendProgress,
                onSend = { targets -> viewModel?.sendDataBankEntriesToRobots(toUpload, targets) },
                onDismiss = {
                    dataBankToUpload = null
                    selectedDbNums = emptySet()
                    viewModel?.clearSendProgress()
                }
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
                    // só o Enter: o texto já foi enviado letra por letra. Com o campo vazio, manda um
                    // Enter em branco (responde perguntas como "Change? (If not, Press RETURN only.)")
                    viewModel?.sendCommand("")
                    commandText = ""
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
                    // vazio também envia: Enter em branco
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
 * Seção Data Bank: um cartão por linha da seção .sprdb, no mesmo estilo da seção Programas.
 *
 * Cada cartão tem a caixa de seleção, o número (DBn), o comentário e os seis valores (FRATE,
 * PATTERN, ATOMIZE, HVOLT, SPEED, JSPEED) em duas linhas, mais os botões editar e duplicar.
 * Tocar no cartão também abre a edição. As ações sobre os marcados (selecionar todos, editar
 * selecionados, enviar, compartilhar e excluir) ficam na barra do topo da tela. O "+" cria uma
 * linha nova. A lista vem em ordem de número.
 */
@Composable
fun DataBankPanel(
    entries: List<RobotDataBankEntry>,
    selectedNums: Set<String>,
    onToggleSelect: (String) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    viewModel: RobotDashboardViewModel?
) {
    var showEditDialog by remember { mutableStateOf<RobotDataBankEntry?>(null) }
    var showDuplicateDialog by remember { mutableStateOf<RobotDataBankEntry?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    val sortedEntries = remember(entries) { entries.sortedBy { it.num.toIntOrNull() ?: 0 } }

    Box(modifier = Modifier.fillMaxSize()) {
        if (sortedEntries.isEmpty()) {
            Text(
                "Nenhum registro no Data Bank deste backup. Toque em + para criar.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)
        ) {
            items(sortedEntries, key = { it.num }) { entry ->
                DataBankCard(
                    entry = entry,
                    isSelected = entry.num in selectedNums,
                    onToggleSelect = { onToggleSelect(entry.num) },
                    onEdit = { showEditDialog = entry },
                    onDuplicate = { showDuplicateDialog = entry }
                )
            }
        }
        FloatingActionButton(
            onClick = { showCreateDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp)
        ) {
            Icon(Icons.Default.Add, "Novo registro")
        }
    }

    if (showEditDialog != null) {
        DataBankEditDialog(
            entry = showEditDialog!!,
            onDismiss = { showEditDialog = null },
            onSave = { updated ->
                viewModel?.updateDataBankEntry(updated)
                showEditDialog = null
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

/** Nomes e valores das seis colunas de uma linha do Data Bank, na ordem do backup. */
private fun RobotDataBankEntry.columns() = listOf(
    "FRATE" to frate, "PATTERN" to pattern, "ATOMIZE" to atomize,
    "HVOLT" to hvolt, "SPEED" to speed, "JSPEED" to jspeed
)

/** Cartão de uma linha do Data Bank: seleção, DBn, comentário, os seis valores e as ações. */
@Composable
private fun DataBankCard(
    entry: RobotDataBankEntry,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onEdit),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("DB${entry.num}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    if (entry.comment.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            entry.comment.trim(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                entry.columns().chunked(3).forEach { row ->
                    Row {
                        row.forEach { (label, value) ->
                            Column(Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            Column {
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Edit, "Editar", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDuplicate, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.ContentCopy, "Duplicar", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/**
 * "Editar selecionados" do Data Bank: muda as colunas escolhidas em todas as linhas marcadas
 * de uma vez. Cada campo começa com o valor comum às linhas (ou vazio, com "vários valores",
 * quando elas diferem). Só os campos alterados são aplicados; os outros ficam como estão em
 * cada linha.
 */
@Composable
fun DataBankBulkEditDialog(
    entries: List<RobotDataBankEntry>,
    onDismiss: () -> Unit,
    onApply: (List<RobotDataBankEntry>) -> Unit
) {
    val labels = listOf("FRATE", "PATTERN", "ATOMIZE", "HVOLT", "SPEED", "JSPEED", "Comentário")
    fun values(e: RobotDataBankEntry) = listOf(e.frate, e.pattern, e.atomize, e.hvolt, e.speed, e.jspeed, e.comment)
    // valor comum a todas as linhas, ou null quando elas diferem
    val common = remember(entries) {
        labels.indices.map { i -> entries.map { values(it)[i] }.distinct().singleOrNull() }
    }
    val fields = remember(entries) { mutableStateListOf(*common.map { it ?: "" }.toTypedArray()) }
    val changed = labels.indices.filter { i -> fields[i] != (common[i] ?: "") && (fields[i].isNotBlank() || i == 6) }

    FormDialog(
        onDismiss = onDismiss,
        title = "Editar ${entries.size} registros",
        content = {
            Column(modifier = Modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    entries.joinToString { "DB" + it.num },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Altere só as colunas que quer mudar em todos. As outras ficam como estão em cada registro.",
                    style = MaterialTheme.typography.bodySmall
                )
                labels.indices.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { i ->
                            OutlinedTextField(
                                value = fields[i],
                                onValueChange = { fields[i] = it },
                                label = { Text(labels[i]) },
                                placeholder = { if (common[i] == null) Text("vários") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onApply(entries.map { e ->
                        var out = e
                        changed.forEach { i ->
                            val v = fields[i].trim()
                            out = when (i) {
                                0 -> out.copy(frate = v)
                                1 -> out.copy(pattern = v)
                                2 -> out.copy(atomize = v)
                                3 -> out.copy(hvolt = v)
                                4 -> out.copy(speed = v)
                                5 -> out.copy(jspeed = v)
                                else -> out.copy(comment = fields[i])
                            }
                        }
                        out
                    })
                },
                enabled = changed.isNotEmpty()
            ) { Text(if (changed.isEmpty()) "Aplicar" else "Aplicar ${changed.size} coluna(s)") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
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

    FormDialog(
        onDismiss = onDismiss,
        title = if (isNew) "Novo Registro" else "Editar Data Bank: ${entry.num}",
        content = {
            Column(modifier = Modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

    FormDialog(
        onDismiss = onDismiss,
        title = "Duplicar Registro",
        content = {
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

/** Nome de cada tipo de variável nos grupos da seção Variáveis. */
private fun variableGroupName(type: String) = when (type) {
    "FRAME" -> "Posições (TRANS)"
    "JOINTS" -> "Posições em juntas (JOINTS)"
    "TRANS" -> "TRANS"
    "REALS" -> "Reais (REALS)"
    "STRINGS" -> "Textos (STRINGS)"
    "INTEGER" -> "Inteiros (INTEGER)"
    "POS" -> "POS"
    else -> type
}

/**
 * Seção Variáveis: no mesmo estilo de Programas e Data Bank. As variáveis ficam agrupadas por
 * tipo (Posições, Reais, Textos...), cada grupo abre e fecha, e dentro dele em ordem de nome.
 *
 * Cada cartão tem a caixa de seleção, o nome (os que começam com "!" em amarelo-escuro) e o
 * valor: nas posições, X, Y, Z, O, A, T (e JT7/JT8 quando existem) em grade; nas outras, o valor
 * inteiro. Botões editar e duplicar; tocar no cartão também edita; "+" cria uma posição nova.
 * As ações sobre as marcadas (selecionar todas, enviar, compartilhar e excluir) ficam na barra
 * do topo da tela.
 */
@Composable
fun VariablesPanel(
    variables: List<RobotVariable>,
    selectedNames: Set<String>,
    onToggleSelect: (String) -> Unit,
    collapsedGroups: Set<String>,
    onToggleGroup: (String) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    viewModel: RobotDashboardViewModel?
) {
    var showEditDialog by remember { mutableStateOf<RobotVariable?>(null) }
    var showDuplicateDialog by remember { mutableStateOf<RobotVariable?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    val grouped = remember(variables) {
        variables.groupBy { variableGroupName(it.type) }
            .mapValues { (_, list) -> list.sortedBy { it.name.lowercase() } }
            .toSortedMap()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (variables.isEmpty()) {
            Text(
                "Nenhuma variável neste backup. Toque em + para criar.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            grouped.forEach { (group, list) ->
                item(key = "g_$group") {
                    val isExpanded = group !in collapsedGroups
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().clickable { onToggleGroup(group) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "$group · ${list.size}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                if (group !in collapsedGroups) {
                    items(list, key = { "v_${it.type}_${it.name}" }) { variable ->
                        VariableCard(
                            variable = variable,
                            isSelected = variable.name in selectedNames,
                            onToggleSelect = { onToggleSelect(variable.name) },
                            onEdit = { showEditDialog = variable },
                            onDuplicate = { showDuplicateDialog = variable }
                        )
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { showCreateDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp)
        ) {
            Icon(Icons.Default.Add, "Nova variável")
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
                viewModel?.createVariable(newVar)
                showCreateDialog = false
            }
        )
    }
}

/** Cartão de uma variável: seleção, nome, valor (grade nas posições) e as ações. */
@Composable
private fun VariableCard(
    variable: RobotVariable,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
            .clickable(onClick = onEdit),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    variable.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (variable.name.startsWith("!")) Color(0xFFD4AC0D) else Color.Unspecified,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (variable.type == "FRAME" || variable.type == "JOINTS") {
                    val values = remember(variable.value) {
                        variable.value.split(Regex("\\s+")).filter { it.isNotBlank() }
                    }
                    // um rótulo por valor: X..T e eixos extras, ou JT1..JTn nas posições em juntas
                    val labels = poseLabels(variable.type, maxOf(values.size, if (variable.type == "JOINTS") 1 else 6))
                    val shown = labels.indices.toList()
                    shown.chunked(3).forEach { row ->
                        Row {
                            row.forEach { i ->
                                Column(Modifier.weight(1f)) {
                                    Text(labels[i], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        values.getOrNull(i) ?: "0.000",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                } else {
                    Text(
                        variable.value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Column {
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Edit, "Editar", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDuplicate, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.ContentCopy, "Duplicar", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/**
 * Tipos de variável que o app sabe criar, com o prefixo do nome e a seção do backup
 * (AS Language Reference Manual, 3.4): posição em transformação (sem prefixo, .TRANS), posição
 * em juntas ("#", .JOINTS), real (sem prefixo, .REALS) e texto ("$", .STRINGS).
 */
private enum class NewVariableKind(val label: String, val type: String, val prefix: String, val hint: String) {
    TRANS("Posição (X, Y, Z, O, A, T)", "FRAME", "", "Pose em coordenadas cartesianas. Ex.: pick"),
    JOINTS("Posição em juntas (JT1…)", "JOINTS", "#", "Ângulo de cada eixo. O nome começa com #. Ex.: #pick"),
    REAL("Real (número)", "REALS", "", "Um número. Ex.: count = 10"),
    STRING("Texto", "STRINGS", "$", "Texto entre aspas. O nome começa com \$. Ex.: \$nome = \"R10\"")
}

/** Rótulos dos valores de uma posição: X..T (+ eixos extras) ou JT1..JTn. */
private fun poseLabels(type: String, count: Int): List<String> =
    if (type == "JOINTS") List(count) { "JT${it + 1}" }
    else listOf("X", "Y", "Z", "O", "A", "T").take(count) + List((count - 6).coerceAtLeast(0)) { "JT${it + 7}" }

/**
 * Janela para criar ou editar uma variável.
 *
 * Criando, primeiro se escolhe o tipo (posição, posição em juntas, real ou texto); o prefixo do
 * nome ("#" ou "$") é posto sozinho. Posições têm um campo por valor (X..T e eixos extras, ou
 * JT1..JTn, conforme os eixos do robô lidos do backup); real e texto têm um campo só. O texto é
 * gravado entre aspas.
 */
@Composable
fun VariableEditDialog(
    variable: RobotVariable,
    viewModel: RobotDashboardViewModel?,
    isNew: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (RobotVariable) -> Unit
) {
    val axes = viewModel?.robotInfo?.collectAsState()?.value?.axes ?: 6
    var kind by remember {
        mutableStateOf(
            when (variable.type) {
                "JOINTS" -> NewVariableKind.JOINTS
                "REALS" -> NewVariableKind.REAL
                "STRINGS" -> NewVariableKind.STRING
                else -> NewVariableKind.TRANS
            }
        )
    }
    val prefix = if (isNew) kind.prefix else ""
    var name by remember { mutableStateOf(if (isNew) "" else variable.name) }
    val fullName = if (isNew) prefix + name.removePrefix(prefix) else name

    val isPose = kind.type == "FRAME" || kind.type == "JOINTS"
    val existingParts = remember(variable.value) { variable.value.split(Regex("\\s+")).filter { it.isNotBlank() } }
    // quantos valores a posição tem: os que já existem, ou os eixos do robô numa posição nova
    val poseCount = if (!isNew && existingParts.isNotEmpty()) existingParts.size
    else if (kind.type == "JOINTS") axes else maxOf(6, axes)
    val poseValues = remember(kind) {
        mutableStateListOf(*Array(poseCount) { existingParts.getOrNull(it)?.takeIf { !isNew } ?: "0.000" })
    }
    var single by remember(kind) {
        mutableStateOf(
            when {
                isNew -> if (kind.type == "STRINGS") "" else "0"
                kind.type == "STRINGS" -> variable.value.trim().removeSurrounding("\"")
                else -> variable.value
            }
        )
    }

    val nameError = if (viewModel != null) remember(fullName) { viewModel.validateVariableName(fullName, isNew) } else null

    FormDialog(
        onDismiss = onDismiss,
        title = if (isNew) "Nova variável" else "Editar: ${variable.name}",
        content = {
            if (isNew) {
                Text("Tipo", style = MaterialTheme.typography.labelLarge)
                NewVariableKind.entries.forEach { k ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { kind = k },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = kind == k, onClick = { kind = k })
                        Column {
                            Text(k.label)
                            if (kind == k) {
                                Text(k.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            OutlinedTextField(
                value = if (isNew) name.removePrefix(prefix) else name,
                onValueChange = { name = it.removePrefix(prefix) },
                label = { Text("Nome") },
                prefix = if (prefix.isNotEmpty()) ({ Text(prefix) }) else null,
                isError = nameError != null && name.isNotEmpty(),
                supportingText = { if (name.isNotEmpty()) nameError?.let { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (isPose) {
                Text(
                    if (kind.type == "JOINTS") "Ângulos dos eixos (graus)" else "Coordenadas (mm e graus)",
                    style = MaterialTheme.typography.labelMedium
                )
                poseLabels(kind.type, poseValues.size).chunked(2).forEachIndexed { row, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEachIndexed { col, label ->
                            val i = row * 2 + col
                            OutlinedTextField(
                                value = poseValues[i],
                                onValueChange = { poseValues[i] = it },
                                label = { Text(label) },
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                OutlinedTextField(
                    value = single,
                    onValueChange = { single = it },
                    label = { Text(if (kind.type == "STRINGS") "Texto" else "Valor") },
                    singleLine = kind.type != "STRINGS",
                    keyboardOptions = if (kind.type == "REALS") androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                    ) else androidx.compose.foundation.text.KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val value = when {
                        isPose -> poseValues.joinToString(" ") { it.trim().ifEmpty { "0" } }
                        kind.type == "STRINGS" -> "\"" + single.replace("\"", "'") + "\""
                        else -> single.trim()
                    }
                    onSave(RobotVariable(name = fullName, value = value, type = kind.type))
                },
                enabled = nameError == null && name.isNotBlank() && (isPose || kind.type == "STRINGS" || single.isNotBlank())
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

    FormDialog(
        onDismiss = onDismiss,
        title = "Duplicar Variável",
        content = {
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
 * Página inicial do painel:
 * - [RobotInfoCard]: desenho do robô, modelo, eixos, horímetro etc. e o selo do status
 *   geral (tocar abre o que precisa de atenção), tudo lido do backup SAVE/FULL;
 * - [RobotUsageCard]: gráfico de horas em operação por dia, com todos os backups do robô;
 * - cartão do backup analisado (nome, data, linhas), com o atalho "Histórico de backups" e o
 *   aviso de quando ele não é o mais recente do robô (isNewestBackup = false);
 * - atalhos: Programas, Variáveis, Data Bank e os três logs do controlador (Erros, Operação,
 *   Edição), que só têm registros em backup SAVE/FULL.
 * O arquivo completo (Código AS) fica no menu "⋮" da barra do topo.
 */
@Composable
fun DashboardHome(
    robot: Robot?,
    backup: my.robots.core.model.BackupSummary?,
    isNewestBackup: Boolean = true,
    robotInfo: RobotInfo = RobotInfo(),
    errorLog: List<RobotErrorLogEntry> = emptyList(),
    dailyUsage: List<DailyUsage> = emptyList(),
    axisMoveHoursLast30: List<Double> = emptyList(),
    foreignBackups: List<Pair<String, String>> = emptyList(),
    memory: ControllerMemory? = null,
    isConnected: Boolean = false,
    heartbeat: HeartbeatState = HeartbeatState.DISCONNECTED,
    onToggleConnection: () -> Unit = {},
    isReadingMemory: Boolean = false,
    onReadMemory: () -> Unit = {},
    lineCount: Int,
    dataBankCount: Int,
    errorLogCount: Int,
    operationLogCount: Int,
    programEditLogCount: Int,
    onViewBackups: () -> Unit = {},
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    onFeatureClick: (DashboardFeature) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        val health = remember(robotInfo, errorLog, backup?.timestamp, foreignBackups) {
            backup?.let { RobotHealth.evaluate(robotInfo, errorLog, it.timestamp, foreignBackups) }
        }
        ConnectionRow(isConnected = isConnected, heartbeat = heartbeat, onToggle = onToggleConnection)

        RobotInfoCard(
            robotName = robot?.name ?: "-",
            registeredSerial = robot?.serialNumber,
            info = robotInfo,
            health = health,
            backupTimestamp = backup?.timestamp,
            axisMoveHoursLast30 = axisMoveHoursLast30,
            memory = memory,
            isConnected = isConnected,
            isReadingMemory = isReadingMemory,
            onReadMemory = onReadMemory,
            onOpenTerminal = { onFeatureClick(DashboardFeature.Terminal) },
            onOpenErrorLog = { onFeatureClick(DashboardFeature.ErrorLog) }
        )
        RobotUsageCard(days = dailyUsage, robotName = robot?.name ?: "robo")

        // Backup analisado: menor, abaixo das informações do robô.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Backup analisado",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = backup?.backupName ?: "Arquivo desconhecido",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                val date = remember(backup?.timestamp) {
                    if (backup != null) {
                        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(backup.timestamp))
                    } else "-"
                }

                StatusItem("Data", date)
                StatusItem("Total de linhas", lineCount.toString())

                if (!isNewestBackup) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Este não é o backup mais recente do robô (foi escolhido no histórico).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = onViewBackups, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Histórico de backups")
                }
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
fun LogPanel(
    entries: List<RobotLogEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    emptyHint: String
) {
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
        state = listState,
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
fun ErrorLogPanel(
    entries: List<RobotErrorLogEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    emptyHint: String
) {
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
        state = listState,
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
                AppTopBar(title = "Detalhe do erro", onBack = onDismiss)
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
    listState: androidx.compose.foundation.lazy.LazyListState,
    collapsedGroups: Set<String>,
    onToggleGroup: (String) -> Unit,
    selectedNames: Set<String>,
    onToggleSelect: (String) -> Unit,
    onProgramClick: (RobotProgram) -> Unit,
    onDuplicate: (RobotProgram) -> Unit
) {
    
    val groupedPrograms = remember(programs) {
        programs.groupBy { it.group }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        groupedPrograms.forEach { (groupName, programsInGroup) ->
            item {
                val isExpanded = groupName !in collapsedGroups
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleGroup(groupName) }
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

            if (groupName !in collapsedGroups) {
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
 * Grava o texto num arquivo temporário e abre o menu de compartilhar do Android (mesmo
 * mecanismo usado no histórico de backups). Usado por Programas e Data Bank.
 */
internal fun shareTextFile(
    context: Context,
    fileName: String,
    content: String,
    title: String,
    mimeType: String = "application/octet-stream"
) {
    try {
        // o nome pode vir do texto do backup: limpa para não sair de shared_backups
        val file = File(File(context.cacheDir, "shared_backups").apply { mkdirs() }, FileUtil.sanitizeFileName(fileName))
        file.writeText(content)
        val contentUri = FileProvider.getUriForFile(context, "my.robots.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title))
    } catch (e: Exception) {
        Toast.makeText(context, "Erro ao compartilhar: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

/**
 * Linha de conexão no alto da home do painel: LED de heartbeat, estado e o botão
 * Conectar/Desconectar. Ao conectar, o login dispara as checagens (série, relógio e memória).
 */
@Composable
private fun ConnectionRow(isConnected: Boolean, heartbeat: HeartbeatState, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HeartbeatDot(state = heartbeat)
        Spacer(Modifier.width(10.dp))
        Text(
            if (isConnected) "Conectado · ${heartbeat.label()}" else "Desconectado",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        if (isConnected) {
            OutlinedButton(onClick = onToggle) { Text("Desconectar") }
        } else {
            Button(onClick = onToggle) { Text("Conectar") }
        }
    }
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

    FormDialog(
        onDismiss = onDismiss,
        title = "Duplicar Programa",
        content = {
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

/**
 * Lupa das listas (Programas, Variáveis, Data Bank): quando aberta, um campo embaixo da barra
 * do topo filtra os cartões enquanto se digita e mostra "N de M". O conteúdo da seção vem
 * embaixo, inteiro.
 */
@Composable
private fun WithListSearch(
    open: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    shown: Int,
    total: Int,
    onClose: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        if (open) {
            SearchFieldRow(query = query, onQuery = onQuery, count = if (query.isBlank()) "$total" else "$shown de $total", onClose = onClose)
        }
        if (open && query.isNotBlank() && shown == 0) {
            Text(
                "Nada encontrado para \"$query\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

/**
 * Linha do campo de busca, embaixo da linha de ações (listas e logs): campo com a lupa, a
 * contagem ("N de M") e o X para fechar. Abre com o teclado no campo.
 */
@Composable
private fun SearchFieldRow(query: String, onQuery: (String) -> Unit, count: String?, onClose: () -> Unit) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Pesquisar...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.weight(1f).focusRequester(focus)
            )
            if (count != null) {
                Text(count, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp))
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Fechar busca") }
        }
    }
}
