package my.robots

import my.robots.feature.clients.ClientScreen
import my.robots.feature.clients.ClientsScreen
import my.robots.feature.clients.ClientsViewModelFactory
import my.robots.feature.clients.LineScreen
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import my.robots.core.common.ExternalAsFile
import my.robots.core.data.ControllerChecks
import my.robots.core.common.ascode.AsProgramBlocks
import my.robots.core.model.Manufacturer
import my.robots.feature.codeeditor.AsCodeViewer
import my.robots.feature.backup.BackupHistoryScreen
import my.robots.feature.backup.BackupViewModel
import my.robots.feature.backup.BackupViewModelFactory
import my.robots.feature.splash.SplashScreen
import my.robots.feature.dashboard.DashboardFeature
import my.robots.feature.dashboard.RobotDashboardScreen
import my.robots.feature.dashboard.RobotDashboardViewModel
import my.robots.feature.dashboard.RobotDashboardViewModelFactory
import my.robots.feature.project.MasterSlaveScreen
import my.robots.feature.project.MasterSlaveViewModelFactory
import my.robots.feature.project.ProjectScreen
import my.robots.feature.project.ProjectViewModel
import my.robots.feature.project.ProjectViewModelFactory
import my.robots.feature.robots.ConnectedRobotsViewModel
import my.robots.feature.robots.ManufacturerSettingsScreen
import my.robots.core.designsystem.LocalSearchTerms
import my.robots.feature.robots.ConnectedRobotsViewModelFactory
import my.robots.feature.robots.RobotListScreen
import my.robots.feature.robots.RobotViewModelFactory
import my.robots.feature.terminal.MultiRobotTerminalScreen
import my.robots.feature.terminal.MultiRobotTerminalViewModel
import my.robots.feature.terminal.MultiRobotTerminalViewModelFactory
import my.robots.feature.terminal.QuickCommandScreen
import my.robots.feature.terminal.QuickCommandViewModelFactory
import my.robots.core.designsystem.MyRobotsTheme
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Única tela (Activity) do app. Ela guarda a navegação entre todas as telas.
 *
 * Cada tela de verdade mora em um módulo :feature:*; aqui só ligamos uma na outra.
 *
 * Mapa das telas (rotas):
 * - splash ................ abertura animada
 * - robot_list ............ lista de robôs
 * - backup_list/{robô} .... histórico de backups do robô
 * - robot_dashboard/... ... painel do robô (terminal, programas, variáveis, Data Bank)
 * - code_viewer/{backup} .. editor do código AS completo
 * - program_viewer/... .... um programa só
 * - variable_viewer/... ... só as variáveis
 * - external_viewer ....... arquivo .as/.pg aberto de fora do app (texto vem da memória;
 *                            salvar pede o robô)
 * - project/{projeto} ..... tela de Projeto (cabine com os robôs e editor do layout)
 * - multi_terminal/... .... terminal geral de um projeto
 * - quick_commands/... .... biblioteca de comandos rápidos
 */
class MainActivity : ComponentActivity() {
    /**
     * Pedido de "abrir arquivo" ainda não tratado. Vem do onCreate (primeira abertura) ou do
     * onNewIntent (app já aberto). A tela observa e zera depois de tratar, então girar a tela
     * não reabre o mesmo arquivo e um segundo arquivo com o app aberto não é ignorado.
     */
    private val incomingIntent = MutableStateFlow<Intent?>(null)

    /**
     * Monta a tela: liga o modo tela cheia, pega as peças de MyRobotsApp,
     * trata arquivos abertos de fora do app e desenha o mapa de navegação.
     * (Desde a v1.2 não pede permissão de armazenamento: os arquivos ficam em
     * Documentos/MyRobots via MediaStore, ou na pasta escolhida pelo usuário.)
     */
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Só na primeira criação: ao recriar (girar a tela) o intent é o mesmo e já foi tratado.
        if (savedInstanceState == null) incomingIntent.value = intent
        
        // Pega as peças que foram criadas uma única vez em MyRobotsApp.
        val app = application as MyRobotsApp
        val repository = app.robotRepository
        val terminalManager = app.terminalManager
        val controllerChecks = app.controllerChecks

        // Daqui para baixo é a interface (Jetpack Compose).
        setContent {
            MyRobotsTheme {
                val scope = rememberCoroutineScope()
                val navController = rememberNavController()
                val context = LocalContext.current
                
                // Nome + texto do último arquivo .as/.pg aberto de fora do app, guardado só na
                // memória (nunca no banco) até o usuário escolher um robô para salvar de vez.
                // Assim a tela de visualização não depende de gravar e reler o texto gigante do
                // banco (SQLite tem um limite de tamanho por linha lida, o "CursorWindow") só
                // para mostrar um arquivo que talvez nem seja salvo.
                var pendingExternalFile by remember { mutableStateOf<Pair<String, String>?>(null) }
                
                // Se o app foi aberto por um arquivo .as/.pg (vindo de outro app), lê o arquivo e
                // abre no visualizador (o texto fica só na memória até o usuário salvar).
                // Arquivo recusado (tipo, tamanho, não é texto) só mostra o motivo.
                LaunchedEffect(Unit) {
                    incomingIntent.collect { newIntent ->
                        if (newIntent != null) {
                            incomingIntent.value = null
                            handleIntent(
                                newIntent,
                                onRejected = { reason -> Toast.makeText(context, reason, Toast.LENGTH_LONG).show() }
                            ) { fileName, content ->
                                pendingExternalFile = fileName to content
                                navController.navigate("external_viewer")
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Mapa de navegação: cada composable(...) abaixo é uma tela.
                    // termos da pesquisa rápida do editor (a linguagem do editor é a AS, da Kawasaki)
                    val searchTerms by app.manufacturerSettings.searchTerms(Manufacturer.KAWASAKI).collectAsState()
                    CompositionLocalProvider(LocalSearchTerms provides searchTerms) {
                    NavHost(navController = navController, startDestination = "splash") {
                        // Tela 1: abertura animada. Ao terminar, vai para a tela de Clientes (e some do histórico de voltar).
                        composable("splash") {
                            SplashScreen(
                                onAnimationFinished = {
                                    navController.navigate("clients") {
                                        popUpTo("splash") { inclusive = true }
                                    }
                                }
                            )
                        }

                        // Estação = projeto: abre a tela de Projeto de hoje.
                        fun openStation(name: String) {
                            navController.navigate("project/${URLEncoder.encode(name, StandardCharsets.UTF_8.toString())}")
                        }
                        val clientsFactory = ClientsViewModelFactory(repository, terminalManager)

                        // Tela inicial (v1.3): Clientes → Cliente → Linha → Estação → Robô.
                        composable("clients") {
                            ClientsScreen(
                                viewModel = viewModel(factory = clientsFactory),
                                onOpenClient = { navController.navigate("client/$it") },
                                onOpenLine = { navController.navigate("line/$it") },
                                onOpenStation = ::openStation,
                                onOpenOldList = { navController.navigate("robot_list") },
                                onOpenManufacturers = { navController.navigate("manufacturers") },
                                onOpenMasterSlave = { navController.navigate("master_slave") }
                            )
                        }

                        // Um cliente: as linhas dele.
                        composable(
                            route = "client/{clientId}",
                            arguments = listOf(navArgument("clientId") { type = NavType.LongType })
                        ) { entry ->
                            ClientScreen(
                                viewModel = viewModel(factory = clientsFactory),
                                clientId = entry.arguments?.getLong("clientId") ?: 0L,
                                onBack = { navController.popBackStack() },
                                onOpenLine = { navController.navigate("line/$it") },
                                onOpenStation = ::openStation
                            )
                        }

                        // Uma linha: as estações na ordem do processo. Transferir abre a tela de
                        // Projeto do escravo já na transferência daquele mestre.
                        composable(
                            route = "line/{lineId}",
                            arguments = listOf(navArgument("lineId") { type = NavType.LongType })
                        ) { entry ->
                            LineScreen(
                                viewModel = viewModel(factory = clientsFactory),
                                lineId = entry.arguments?.getLong("lineId") ?: 0L,
                                onBack = { navController.popBackStack() },
                                onOpenStation = ::openStation,
                                onTransfer = { master, slave ->
                                    val enc = { n: String -> URLEncoder.encode(n, StandardCharsets.UTF_8.toString()) }
                                    navController.navigate("project/${enc(slave)}?transferFrom=${enc(master)}")
                                }
                            )
                        }

                        // Lista de robôs (a tela inicial até a v1.2). Fica no ⋮ Configurações da tela
                        // de Clientes como "Lista de robôs (antiga)" enquanto a nova é testada.
                        // - Tocar no robô -> painel dele, com o backup mais recente (-1).
                        // - Ícone do terminal -> painel do robô já no terminal.
                        // - Ícone do projeto -> tela de Projeto (a cabine).
                        composable("robot_list") {
                            val connectedRobotsViewModel: ConnectedRobotsViewModel = viewModel(
                                factory = ConnectedRobotsViewModelFactory(repository, terminalManager)
                            )
                            RobotListScreen(
                                viewModel = viewModel(factory = RobotViewModelFactory(repository)),
                                connectedRobotsViewModel = connectedRobotsViewModel,
                                onRobotClick = { robot ->
                                    navController.navigate("robot_dashboard/${robot.id}/-1")
                                },
                                onTerminalClick = { robot ->
                                    navController.navigate("robot_dashboard/${robot.id}/-1?feature=Terminal")
                                },
                                onOpenProject = { projectName ->
                                    val encodedProject = URLEncoder.encode(projectName, StandardCharsets.UTF_8.toString())
                                    navController.navigate("project/$encodedProject")
                                },
                                onOpenManufacturers = { navController.navigate("manufacturers") },
                                onOpenMasterSlave = { navController.navigate("master_slave") }
                            )
                        }

                        // Mestre / Escravo: todas as configurações de transferência entre projetos.
                        composable("master_slave") {
                            MasterSlaveScreen(
                                viewModel = viewModel(factory = MasterSlaveViewModelFactory(repository, app.masterSlaveOptions)),
                                onBack = { navController.popBackStack() }
                            )
                        }

                        // Fabricantes: termos da pesquisa rápida do editor e comandos padrão.
                        composable("manufacturers") {
                            ManufacturerSettingsScreen(
                                settings = app.manufacturerSettings,
                                onBack = { navController.popBackStack() }
                            )
                        }

                        // Tela de Projeto: a cabine com os robôs.
                        // - Segurar um robô -> painel dele.
                        // - "Terminal Geral" / "Modo avançado" -> terminal geral do projeto.
                        // - Renomear -> troca esta tela pela do nome novo.
                        composable(
                            route = "project/{projectName}?transferFrom={transferFrom}",
                            arguments = listOf(
                                navArgument("projectName") { type = NavType.StringType },
                                navArgument("transferFrom") { type = NavType.StringType; nullable = true; defaultValue = null }
                            )
                        ) { backStackEntry ->
                            val encodedProject = backStackEntry.arguments?.getString("projectName") ?: ""
                            val projectName = URLDecoder.decode(encodedProject, StandardCharsets.UTF_8.toString())
                            // vindo do "Transferir" da tela da Linha: o projeto mestre (abre a transferência)
                            val transferFrom = backStackEntry.arguments?.getString("transferFrom")
                                ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.toString()) }
                            val projectViewModel: ProjectViewModel = viewModel(
                                factory = ProjectViewModelFactory(repository, terminalManager, app.projectOperations, app.masterSlaveOptions, projectName)
                            )
                            ProjectScreen(
                                viewModel = projectViewModel,
                                onBack = { navController.popBackStack() },
                                onOpenRobot = { robot -> navController.navigate("robot_dashboard/${robot.id}/-1") },
                                onOpenTerminal = { navController.navigate("multi_terminal/$encodedProject") },
                                onOpenRobotTerminal = { robot -> navController.navigate("robot_dashboard/${robot.id}/-1?feature=Terminal") },
                                onOpenMasterSlave = { navController.navigate("master_slave") },
                                onRenamed = { newName ->
                                    val encodedNew = URLEncoder.encode(newName, StandardCharsets.UTF_8.toString())
                                    navController.navigate("project/$encodedNew") {
                                        popUpTo("project/{projectName}?transferFrom={transferFrom}") { inclusive = true }
                                    }
                                },
                                autoTransferFrom = transferFrom
                            )
                        }

                        // Terminal geral: manda o mesmo comando para todos os robôs de um projeto.
                        composable(
                            route = "multi_terminal/{projectName}",
                            arguments = listOf(navArgument("projectName") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val encodedProject = backStackEntry.arguments?.getString("projectName") ?: ""
                            val projectName = URLDecoder.decode(encodedProject, StandardCharsets.UTF_8.toString())
                            
                            val multiViewModel: MultiRobotTerminalViewModel = viewModel(
                                factory = MultiRobotTerminalViewModelFactory(repository, terminalManager, projectName)
                            )
                            
                            MultiRobotTerminalScreen(
                                projectName = projectName,
                                viewModel = multiViewModel,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        
                        // Biblioteca de comandos rápidos da marca do robô.
                        // Se a marca vier inválida, usa Kawasaki.
                        composable(
                            route = "quick_commands/{manufacturer}/{robotId}",
                            arguments = listOf(
                                navArgument("manufacturer") { type = NavType.StringType },
                                navArgument("robotId") { type = NavType.IntType }
                            )
                        ) { backStackEntry ->
                            val manufacturerName = backStackEntry.arguments?.getString("manufacturer") ?: ""
                            val robotId = backStackEntry.arguments?.getInt("robotId") ?: -1
                            val manufacturer = try { Manufacturer.valueOf(manufacturerName) } catch(e: Exception) { Manufacturer.KAWASAKI }
                            
                            QuickCommandScreen(
                                viewModel = viewModel(factory = QuickCommandViewModelFactory(repository, terminalManager, manufacturer, robotId)),
                                onBack = { navController.popBackStack() }
                            )
                        }

                        // Histórico de backups de um robô (aberto pelo painel).
                        // - Tocar no backup -> painel do robô analisando aquele backup (substitui o painel anterior).
                        // - Ícone de código -> editor do texto completo.
                        // - Botão "criar" -> painel do robô no terminal, para baixar um backup do robô.
                        composable(
                            route = "backup_list/{robotId}",
                            arguments = listOf(navArgument("robotId") { type = NavType.IntType })
                        ) { backStackEntry ->
                            val robotId = backStackEntry.arguments?.getInt("robotId") ?: return@composable
                            val backupViewModel: BackupViewModel = viewModel(
                                factory = BackupViewModelFactory(repository, robotId)
                            )
                            BackupHistoryScreen(
                                viewModel = backupViewModel,
                                onBack = { navController.popBackStack() },
                                onViewDashboard = { backup ->
                                    // troca o painel que abriu o histórico (não empilha um painel sobre outro)
                                    navController.navigate("robot_dashboard/${robotId}/${backup.id}") {
                                        popUpTo(DASHBOARD_ROUTE) { inclusive = true }
                                    }
                                },
                                onViewCode = { backup ->
                                    navController.navigate("code_viewer/${backup.id}")
                                },
                                onCreateBackup = {
                                    navController.navigate("robot_dashboard/${robotId}/-1?feature=Terminal") {
                                        popUpTo(DASHBOARD_ROUTE) { inclusive = true }
                                    }
                                }
                            )
                        }
                        
                        // Painel do robô. backupId = -1 significa "usar o backup mais recente".
                        // O parâmetro "feature" abre direto uma seção (ex.: Terminal).
                        // Ao enviar algo para OUTRO robô, navega para o painel dele.
                        composable(
                            route = DASHBOARD_ROUTE,
                            arguments = listOf(
                                navArgument("robotId") { type = NavType.IntType },
                                navArgument("backupId") { type = NavType.IntType },
                                navArgument("feature") { 
                                    type = NavType.StringType
                                    nullable = true
                                    defaultValue = null
                                }
                            )
                        ) { backStackEntry ->
                            val robotId = backStackEntry.arguments?.getInt("robotId") ?: return@composable
                            val backupId = backStackEntry.arguments?.getInt("backupId") ?: return@composable
                            val featureName = backStackEntry.arguments?.getString("feature")
                            val initialFeature = featureName?.let { 
                                try { DashboardFeature.valueOf(it) } catch(e: Exception) { null } 
                            }
                            
                            val dashboardViewModel: RobotDashboardViewModel = viewModel(
                                factory = RobotDashboardViewModelFactory(repository, robotId, terminalManager, controllerChecks, backupId)
                            )
                            
                            RobotDashboardScreen(
                                viewModel = dashboardViewModel,
                                initialFeature = initialFeature,
                                onViewBackups = {
                                    navController.navigate("backup_list/${robotId}")
                                },
                                onProgramClick = { backup, programName ->
                                    val encodedName = URLEncoder.encode(programName, StandardCharsets.UTF_8.toString())
                                    navController.navigate("program_viewer/${backup.id}/$encodedName")
                                },
                                onVariablesClick = { backup ->
                                    navController.navigate("variable_viewer/${backup.id}")
                                },
                                onFullCodeClick = { backup ->
                                    navController.navigate("code_viewer/${backup.id}")
                                },
                                onQuickCommandsClick = {
                                    val robot = dashboardViewModel.robot.value
                                    robot?.let {
                                        navController.navigate("quick_commands/${it.manufacturer.name}/${it.id}")
                                    }
                                },
                                onNavigateToRobot = { targetRobotId, targetBackupId, feature ->
                                    val featureParam = feature?.name?.let { "?feature=$it" } ?: ""
                                    navController.navigate("robot_dashboard/$targetRobotId/$targetBackupId$featureParam") {
                                        popUpTo("robot_list") { inclusive = false }
                                        launchSingleTop = true
                                    }
                                },
                                onBack = { 
                                    navController.popBackStack() 
                                }
                            )
                        }

                        // Visualizador de UM programa. Pega do backup só o trecho entre ".PROGRAM nome" e ".END".
                        // Ao salvar, troca esse trecho dentro do backup inteiro e grava de novo.
                        composable(
                            route = "program_viewer/{backupId}/{programName}",
                            arguments = listOf(
                                navArgument("backupId") { type = NavType.IntType },
                                navArgument("programName") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val backupId = backStackEntry.arguments?.getInt("backupId") ?: return@composable
                            val encodedName = backStackEntry.arguments?.getString("programName") ?: ""
                            val programName = URLDecoder.decode(encodedName, StandardCharsets.UTF_8.toString())
                            
                            var backup by remember { mutableStateOf<my.robots.core.model.Backup?>(null) }
                            var programContent by remember { mutableStateOf("") }
                            
                            LaunchedEffect(backupId) {
                                backup = repository.getBackupById(backupId)
                                backup?.let {
                                    // nome exato: "pg1" não pode abrir o "pg10"
                                    programContent = AsProgramBlocks.extract(it.content, programName) ?: ""
                                }
                            }
                            
                            if (programContent.isNotEmpty()) {
                                AsCodeViewer(
                                    fileName = "$programName.as",
                                    content = programContent,
                                    onBack = { 
                                        // volta para o painel do robô
                                        backup?.let {
                                            navController.popBackStack()
                                        } ?: navController.popBackStack()
                                    },
                                    onSave = { newProgramContent ->
                                        val currentBackup = backup
                                        if (currentBackup != null) {
                                            // troca só o bloco deste programa (nome exato), o resto do backup fica igual
                                            val newBackupContent = AsProgramBlocks.replace(
                                                currentBackup.content, programName, newProgramContent
                                            )

                                            val updated = currentBackup.copy(
                                                content = newBackupContent.trim(),
                                                timestamp = System.currentTimeMillis()
                                            )
                                            repository.insertBackup(updated)
                                            backup = updated
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                )
                            }
                        }

                        // Visualizador de arquivo aberto de fora do app (.as/.pg). O texto vem direto
                        // da memória (pendingExternalFile) — não precisa gravar e reler do banco só
                        // para mostrar, o que evita o limite de tamanho de linha do SQLite num backup
                        // grande. Ele ainda não tem um robô dono: ao tocar em salvar, pede para
                        // escolher em qual robô guardar antes de gravar de vez.
                        composable(route = "external_viewer") {
                            val fileState = pendingExternalFile
                            if (fileState == null) {
                                // nada pendente (ex.: a Activity foi recriada) -> não tem o que mostrar
                                LaunchedEffect(Unit) { navController.popBackStack() }
                            } else {
                                val (initialFileName, initialContent) = fileState
                                var savedBackup by remember { mutableStateOf<my.robots.core.model.Backup?>(null) }
                                var showRobotPicker by remember { mutableStateOf(false) }
                                var pendingSaveContent by remember { mutableStateOf("") }
                                var pendingSaveDeferred by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
                                val allRobots by produceState(initialValue = emptyList<my.robots.core.model.Robot>()) {
                                    repository.allRobots.collect { value = it }
                                }

                                AsCodeViewer(
                                    fileName = savedBackup?.fileName ?: initialFileName,
                                    content = initialContent,
                                    onBack = {
                                        pendingExternalFile = null
                                        navController.popBackStack()
                                    },
                                    isNewFile = savedBackup == null,
                                    onSave = { newContent ->
                                        val current = savedBackup
                                        if (current == null) {
                                            // ainda sem robô dono: pede a escolha antes de gravar de vez
                                            pendingSaveContent = newContent
                                            val deferred = CompletableDeferred<Boolean>()
                                            pendingSaveDeferred = deferred
                                            showRobotPicker = true
                                            deferred.await()
                                        } else {
                                            val updated = current.copy(
                                                content = newContent,
                                                timestamp = System.currentTimeMillis()
                                            )
                                            repository.insertBackup(updated)
                                            savedBackup = updated
                                            true
                                        }
                                    }
                                )

                                if (showRobotPicker) {
                                    RobotPickerDialog(
                                        robots = allRobots,
                                        onDismiss = {
                                            showRobotPicker = false
                                            pendingSaveDeferred?.complete(false)
                                            pendingSaveDeferred = null
                                        },
                                        onRobotSelected = { robot ->
                                            scope.launch {
                                                val newBackup = my.robots.core.model.Backup(
                                                    robotId = robot.id,
                                                    backupName = "Importado: $initialFileName",
                                                    fileName = initialFileName,
                                                    content = pendingSaveContent,
                                                    timestamp = System.currentTimeMillis()
                                                )
                                                val id = repository.insertBackup(newBackup)
                                                savedBackup = newBackup.copy(id = id)
                                                Toast.makeText(context, "Salvo em ${robot.name}", Toast.LENGTH_SHORT).show()
                                                showRobotPicker = false
                                                pendingSaveDeferred?.complete(true)
                                                pendingSaveDeferred = null
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        // Visualizador só das variáveis. Junta do backup as seções .TRANS, .REALS e .STRINGS
                        // (cada uma vai até o seu ".END").
                        composable(
                            route = "variable_viewer/{backupId}",
                            arguments = listOf(navArgument("backupId") { type = NavType.IntType })
                        ) { backStackEntry ->
                            val backupId = backStackEntry.arguments?.getInt("backupId") ?: return@composable
                            var backup by remember { mutableStateOf<my.robots.core.model.Backup?>(null) }
                            var varsContent by remember { mutableStateOf("") }
                            
                            LaunchedEffect(backupId) {
                                backup = repository.getBackupById(backupId)
                                backup?.let {
                                    val lines = it.content.lines()
                                    val extracted = StringBuilder()
                                    var isReading = false
                                    for (line in lines) {
                                        val trimmed = line.trim()
                                        if (trimmed.equals(".TRANS", ignoreCase = true) || 
                                            trimmed.equals(".REALS", ignoreCase = true) ||
                                            trimmed.equals(".STRINGS", ignoreCase = true)) {
                                            isReading = true
                                        }
                                        if (isReading) {
                                            extracted.append(line).append("\n")
                                            if (trimmed.equals(".END", ignoreCase = true)) isReading = false
                                        }
                                    }
                                    varsContent = extracted.toString()
                                }
                            }
                            
                            if (varsContent.isNotEmpty()) {
                                AsCodeViewer(
                                    fileName = "Variables",
                                    content = varsContent,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }

                        // Editor do código completo do backup. Ao salvar, grava o texto inteiro de novo.
                        composable(
                            route = "code_viewer/{backupId}",
                            arguments = listOf(navArgument("backupId") { type = NavType.IntType })
                        ) { backStackEntry ->
                            val backupId = backStackEntry.arguments?.getInt("backupId") ?: return@composable
                            var backup by remember { mutableStateOf<my.robots.core.model.Backup?>(null) }
                            var isLoadingBackup by remember { mutableStateOf(true) }

                            LaunchedEffect(backupId) {
                                isLoadingBackup = true
                                backup = repository.getBackupById(backupId)
                                isLoadingBackup = false
                            }

                            when {
                                backup != null -> {
                                    val currentBackup = backup!!
                                    AsCodeViewer(
                                        fileName = currentBackup.fileName,
                                        content = currentBackup.content,
                                        onBack = { navController.popBackStack() },
                                        onSave = { newFullContent ->
                                            val updated = currentBackup.copy(
                                                content = newFullContent,
                                                timestamp = System.currentTimeMillis()
                                            )
                                            repository.insertBackup(updated)
                                            backup = updated
                                            true
                                        }
                                    )
                                }
                                isLoadingBackup -> {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator()
                                    }
                                }
                                else -> {
                                    // getBackupById devolveu null: backup não existe mais ou a leitura falhou
                                    Column(
                                        modifier = Modifier.fillMaxSize().padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text("Não foi possível abrir este backup.")
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Button(onClick = { navController.popBackStack() }) { Text("Voltar") }
                                    }
                                }
                            }
                        }
                    }
                    }
                }

                // Perguntas das checagens do login (relógio errado, série diferente), por cima de
                // qualquer tela.
                ControllerCheckDialogs(controllerChecks)
            }
        }
    }

    /**
     * Chamado quando o app já está aberto e recebe um novo arquivo para abrir. Guarda o novo
     * pedido; a tela trata com as mesmas conferências da primeira abertura.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingIntent.value = intent
    }

    /**
     * Trata um arquivo .as/.pg enviado por outro app (ação VER ou EDITAR): só lê o nome e o
     * texto do arquivo e devolve pra quem chamou (onFileRead), sem gravar nada no banco —
     * o arquivo só vira um backup de verdade se o usuário escolher um robô para salvá-lo.
     *
     * A leitura passa por ExternalAsFile: só content://, só .as/.pg, até 20 MB e só texto.
     * Qualquer recusa vai para onRejected com o motivo.
     */
    private suspend fun handleIntent(
        intent: Intent,
        onRejected: (reason: String) -> Unit,
        onFileRead: (fileName: String, content: String) -> Unit
    ) {
        if (intent.action != Intent.ACTION_VIEW && intent.action != Intent.ACTION_EDIT) return
        val uri: Uri = intent.data ?: return
        val result = withContext(Dispatchers.IO) {
            ExternalAsFile.read(applicationContext, uri, requireAsExtension = true)
        }
        when (result) {
            is ExternalAsFile.Result.Ok -> onFileRead(result.fileName, result.content)
            is ExternalAsFile.Result.Rejected -> onRejected(result.reason)
        }
    }
}

/**
 * Rota do painel do robô. backupId = -1 usa o backup mais recente; feature abre uma seção.
 */
private const val DASHBOARD_ROUTE = "robot_dashboard/{robotId}/{backupId}?feature={feature}"

/**
 * Janela para escolher em qual robô cadastrado salvar um arquivo (.as/.pg) aberto de
 * fora do app. Tocar num robô já confirma a escolha (não precisa de botão "OK" à parte).
 */
@Composable
private fun RobotPickerDialog(
    robots: List<my.robots.core.model.Robot>,
    onDismiss: () -> Unit,
    onRobotSelected: (my.robots.core.model.Robot) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Salvar em Qual Robô?") },
        text = {
            if (robots.isEmpty()) {
                Text("Nenhum robô cadastrado ainda. Cadastre um robô antes de salvar este arquivo.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(robots, key = { it.id }) { robot ->
                        ListItem(
                            headlineContent = { Text(robot.name) },
                            supportingContent = { Text(robot.manufacturer.displayName) },
                            leadingContent = {
                                Icon(Icons.Default.SmartToy, contentDescription = null)
                            },
                            modifier = Modifier.clickable { onRobotSelected(robot) }
                        )
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
 * Perguntas que as checagens do login (`ControllerChecks`) fazem ao usuário, uma de cada vez:
 * - série do controlador diferente da cadastrada (pode ser o robô errado);
 * - relógio do robô diferente do celular: corrigir com a hora do celular ou deixar como está.
 */
@Composable
private fun ControllerCheckDialogs(checks: ControllerChecks) {
    val mismatches by checks.serialMismatches.collectAsState()
    val clockIssues by checks.clockIssues.collectAsState()
    val questions by checks.questions.collectAsState()
    val mismatch = mismatches.firstOrNull()
    val clock = clockIssues.firstOrNull()
    val question = questions.firstOrNull()

    if (question != null) {
        // pergunta no meio de um SAVE/LOAD: o controlador fica parado até a resposta, então
        // não dá para fechar a janela sem escolher
        AlertDialog(
            onDismissRequest = {},
            title = { Text("${question.robotName} está esperando uma resposta") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "O controlador parou no meio da transferência" +
                            (question.question.fileName?.let { " de $it" } ?: "") + " e perguntou:"
                    )
                    Text(
                        question.question.text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    )
                    Text(
                        "Enquanto não houver resposta, o robô não aceita outro comando. O envio será marcado como falha " +
                            "em qualquer caso; confira o programa no robô depois.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    question.question.options.forEach { (key, label) ->
                        OutlinedButton(onClick = { checks.answerQuestion(question, key) }, modifier = Modifier.fillMaxWidth()) {
                            Text("$key: $label")
                        }
                    }
                }
            },
            confirmButton = {}
        )
    } else if (mismatch != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Série diferente no ${mismatch.robotName}") },
            text = {
                Text(
                    "O controlador em ${mismatch.ip} tem a série ${mismatch.found}, mas o ${mismatch.robotName} " +
                        "está cadastrado com a série ${mismatch.registered}.\n\nPode ser o robô errado (IP trocado). " +
                        "Se o controlador foi trocado, atualize o cadastro."
                )
            },
            confirmButton = { TextButton(onClick = { checks.acceptSerial(mismatch) }) { Text("Atualizar para ${mismatch.found}") } },
            dismissButton = { TextButton(onClick = { checks.dismissSerial(mismatch) }) { Text("Manter ${mismatch.registered}") } }
        )
    } else if (clock != null) {
        val fmt = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
        val minutes = kotlin.math.abs(clock.offsetSeconds) / 60
        val diff = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
        val direction = if (clock.offsetSeconds > 0) "adiantado" else "atrasado"
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Relógio do ${clock.robotName}") },
            text = {
                Text(
                    (if (clock.fixFailed) "O controlador não aceitou a correção.\n\n" else "") +
                        "O relógio do robô está $diff $direction.\n\n" +
                        "Robô: ${clock.robotTime.format(fmt)}\nCelular: ${clock.phoneTime.format(fmt)}\n\n" +
                        "Corrigir com a hora do celular?"
                )
            },
            confirmButton = { TextButton(onClick = { checks.fixClock(clock) }) { Text(if (clock.fixFailed) "Tentar de novo" else "Corrigir") } },
            dismissButton = { TextButton(onClick = { checks.dismissClock(clock) }) { Text("Agora não") } }
        )
    }
}
