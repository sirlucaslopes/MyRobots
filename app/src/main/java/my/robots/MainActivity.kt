package my.robots

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
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
import my.robots.feature.robots.ConnectedRobotsViewModel
import my.robots.feature.robots.ConnectedRobotsViewModelFactory
import my.robots.feature.robots.RobotListScreen
import my.robots.feature.robots.RobotViewModelFactory
import my.robots.feature.terminal.MultiRobotTerminalScreen
import my.robots.feature.terminal.MultiRobotTerminalViewModel
import my.robots.feature.terminal.MultiRobotTerminalViewModelFactory
import my.robots.feature.terminal.QuickCommandScreen
import my.robots.feature.terminal.QuickCommandViewModelFactory
import my.robots.core.designsystem.MyRobotsTheme
import java.io.File
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
 * - multi_terminal/... .... terminal geral de um projeto
 * - quick_commands/... .... biblioteca de comandos rápidos
 */
class MainActivity : ComponentActivity() {
    /**
     * Monta a tela: liga o modo tela cheia, pega as peças de MyRobotsApp,
     * pede permissão de arquivos e desenha o mapa de navegação.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Pega as peças que foram criadas uma única vez em MyRobotsApp.
        val app = application as MyRobotsApp
        val repository = app.robotRepository
        val terminalManager = app.terminalManager

        // Daqui para baixo é a interface (Jetpack Compose).
        setContent {
            MyRobotsTheme {
                val scope = rememberCoroutineScope()
                val navController = rememberNavController()
                val context = LocalContext.current
                
                // Controla se o aviso de "Configuração Inicial" (permissão de arquivos) aparece.
                var showPermissionDialog by remember { mutableStateOf(false) }

                // Nome + texto do último arquivo .as/.pg aberto de fora do app, guardado só na
                // memória (nunca no banco) até o usuário escolher um robô para salvar de vez.
                // Assim a tela de visualização não depende de gravar e reler o texto gigante do
                // banco (SQLite tem um limite de tamanho por linha lida, o "CursorWindow") só
                // para mostrar um arquivo que talvez nem seja salvo.
                var pendingExternalFile by remember { mutableStateOf<Pair<String, String>?>(null) }
                
                // Pedido de permissão comum do Android (usado no Android 10 ou mais antigo).
                val requestPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    if (isGranted) {
                        checkAndCreateRootFolder()
                    }
                }

                // Abre a tela do Android de "acesso a todos os arquivos" (Android 11 ou mais novo).
                // Quando o usuário volta e a permissão foi dada, cria a pasta /MyRobots.
                val manageFilesLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) {
                    if (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true) {
                        checkAndCreateRootFolder()
                    }
                }

                // Ao abrir o app: confere se já temos a permissão e se a pasta /MyRobots existe.
                // Se faltar algo, mostra o aviso pedindo a permissão.
                LaunchedEffect(Unit) {
                    val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Environment.isExternalStorageManager()
                    } else {
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) == PackageManager.PERMISSION_GRANTED
                    }

                    val root = Environment.getExternalStorageDirectory()
                    val myRobotsDir = File(root, "MyRobots")

                    if (!hasPermission || !myRobotsDir.exists()) {
                        showPermissionDialog = true
                    }
                }

                // Aviso explicando por que o app precisa da pasta /MyRobots e pedindo a permissão.
                if (showPermissionDialog) {
                    AlertDialog(
                        onDismissRequest = { showPermissionDialog = false },
                        title = { Text("Configuração Inicial") },
                        text = { 
                            Text("O aplicativo precisa criar a pasta 'MyRobots' na raiz do seu dispositivo para centralizar e organizar os arquivos dos robôs.\n\nPara isso, é necessário conceder permissão de acesso ao armazenamento.") 
                        },
                        confirmButton = {
                            Button(onClick = {
                                showPermissionDialog = false
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    try {
                                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                        intent.data = Uri.parse("package:${context.packageName}")
                                        manageFilesLauncher.launch(intent)
                                    } catch (e: Exception) {
                                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                        manageFilesLauncher.launch(intent)
                                    }
                                } else {
                                    requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                }
                            }) {
                                Text("Conceder Permissão")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showPermissionDialog = false }) {
                                Text("Agora Não")
                            }
                        }
                    )
                }

                // Se o app foi aberto por um arquivo .as/.pg (vindo de outro app), lê o arquivo e
                // abre no visualizador (o texto fica só na memória até o usuário salvar).
                LaunchedEffect(intent) {
                    handleIntent(intent) { fileName, content ->
                        pendingExternalFile = fileName to content
                        navController.navigate("external_viewer")
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Mapa de navegação: cada composable(...) abaixo é uma tela.
                    NavHost(navController = navController, startDestination = "splash") {
                        // Tela 1: abertura animada. Ao terminar, vai para a lista de robôs (e some do histórico de voltar).
                        composable("splash") {
                            SplashScreen(
                                onAnimationFinished = {
                                    navController.navigate("robot_list") {
                                        popUpTo("splash") { inclusive = true }
                                    }
                                }
                            )
                        }

                        // Tela 2: lista de robôs.
                        // - Tocar no robô -> histórico de backups dele.
                        // - Ícone do terminal -> painel do robô já no terminal.
                        // - Ícone do terminal do projeto -> terminal geral (todos os robôs do projeto).
                        composable("robot_list") {
                            val connectedRobotsViewModel: ConnectedRobotsViewModel = viewModel(
                                factory = ConnectedRobotsViewModelFactory(repository, terminalManager)
                            )
                            RobotListScreen(
                                viewModel = viewModel(factory = RobotViewModelFactory(repository)),
                                connectedRobotsViewModel = connectedRobotsViewModel,
                                onRobotClick = { robot ->
                                    navController.navigate("backup_list/${robot.id}")
                                },
                                onTerminalClick = { robot ->
                                    navController.navigate("robot_dashboard/${robot.id}/-1?feature=Logs")
                                },
                                onMultiTerminalClick = { projectName ->
                                    val encodedProject = URLEncoder.encode(projectName, StandardCharsets.UTF_8.toString())
                                    navController.navigate("multi_terminal/$encodedProject")
                                }
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

                        // Histórico de backups de um robô.
                        // - Tocar no backup -> painel do robô analisando aquele backup.
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
                                    navController.navigate("robot_dashboard/${robotId}/${backup.id}")
                                },
                                onViewCode = { backup ->
                                    navController.navigate("code_viewer/${backup.id}")
                                },
                                onCreateBackup = {
                                    navController.navigate("robot_dashboard/${robotId}/-1?feature=Logs") {
                                        launchSingleTop = true
                                    }
                                }
                            )
                        }
                        
                        // Painel do robô. backupId = -1 significa "usar o backup mais recente".
                        // O parâmetro "feature" abre direto uma seção (ex.: Logs = terminal).
                        // Ao enviar algo para OUTRO robô, navega para o painel dele.
                        composable(
                            route = "robot_dashboard/{robotId}/{backupId}?feature={feature}",
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
                                factory = RobotDashboardViewModelFactory(repository, robotId, terminalManager, backupId)
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
                                    val lines = it.content.lines()
                                    val extracted = StringBuilder()
                                    var isReading = false
                                    for (line in lines) {
                                        if (line.trim().startsWith(".PROGRAM $programName", ignoreCase = true)) {
                                            isReading = true
                                        }
                                        if (isReading) {
                                            extracted.append(line).append("\n")
                                            if (line.trim().equals(".END", ignoreCase = true)) break
                                        }
                                    }
                                    programContent = extracted.toString()
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
                                            val oldLines = currentBackup.content.lines()
                                            val newBackupContent = StringBuilder()
                                            var skippingOld = false
                                            var replaced = false

                                            for (line in oldLines) {
                                                if (line.trim().startsWith(".PROGRAM $programName", ignoreCase = true)) {
                                                    skippingOld = true
                                                    if (!replaced) {
                                                        newBackupContent.append(newProgramContent).append("\n")
                                                        replaced = true
                                                    }
                                                }

                                                if (!skippingOld) {
                                                    newBackupContent.append(line).append("\n")
                                                }

                                                if (skippingOld && line.trim().equals(".END", ignoreCase = true)) {
                                                    skippingOld = false
                                                }
                                            }

                                            val updated = currentBackup.copy(
                                                content = newBackupContent.toString().trim(),
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
        }
    }

    /**
     * Cria a pasta /MyRobots na raiz do armazenamento, se ela ainda não existir.
     */
    private fun checkAndCreateRootFolder() {
        try {
            val root = Environment.getExternalStorageDirectory()
            val myRobotsDir = File(root, "MyRobots")
            if (!myRobotsDir.exists()) {
                myRobotsDir.mkdirs()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Chamado quando o app já está aberto e recebe um novo arquivo para abrir. Guarda o novo pedido.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    /**
     * Trata um arquivo .as/.pg enviado por outro app (ação VER ou EDITAR): só lê o nome e o
     * texto do arquivo e devolve pra quem chamou (onFileRead), sem gravar nada no banco —
     * o arquivo só vira um backup de verdade se o usuário escolher um robô para salvá-lo.
     */
    private suspend fun handleIntent(intent: Intent?, onFileRead: (fileName: String, content: String) -> Unit) {
        if (intent?.action == Intent.ACTION_VIEW || intent?.action == Intent.ACTION_EDIT) {
            val uri: Uri? = intent.data
            uri?.let {
                try {
                    val contentResolver = applicationContext.contentResolver
                    val fileName = my.robots.core.common.FileUtil.getFileName(applicationContext, it) ?: "file.as"

                    val content = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        contentResolver.openInputStream(it)?.bufferedReader()?.use { it.readText() }
                    } ?: ""

                    onFileRead(fileName, content)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}

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
