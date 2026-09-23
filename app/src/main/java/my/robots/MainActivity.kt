package my.robots

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
 * - external_viewer/... ... arquivo aberto de fora do app
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

                // Se o app foi aberto por um arquivo .as/.pg (vindo de outro app), importa o arquivo
                // e abre no visualizador.
                LaunchedEffect(intent) {
                    handleIntent(intent, repository, navController)
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
                            RobotListScreen(
                                viewModel = viewModel(factory = RobotViewModelFactory(repository)),
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
                                        backup?.let { currentBackup ->
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
                                            
                                            scope.launch {
                                                val updated = currentBackup.copy(
                                                    content = newBackupContent.toString().trim(),
                                                    timestamp = System.currentTimeMillis()
                                                )
                                                repository.insertBackup(updated)
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        // Visualizador de arquivo aberto de fora do app (somente leitura, sem salvar).
                        composable(
                            route = "external_viewer/{backupId}",
                            arguments = listOf(navArgument("backupId") { type = NavType.IntType })
                        ) { backStackEntry ->
                            val backupId = backStackEntry.arguments?.getInt("backupId") ?: -1
                            var backup by remember { mutableStateOf<my.robots.core.model.Backup?>(null) }
                            
                            LaunchedEffect(backupId) {
                                backup = repository.getBackupById(backupId)
                            }
                            
                            backup?.let {
                                AsCodeViewer(
                                    fileName = it.fileName,
                                    content = it.content,
                                    onBack = { navController.popBackStack() }
                                )
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
                            
                            LaunchedEffect(backupId) {
                                backup = repository.getBackupById(backupId)
                            }
                            
                            backup?.let { currentBackup ->
                                AsCodeViewer(
                                    fileName = currentBackup.fileName,
                                    content = currentBackup.content,
                                    onBack = { navController.popBackStack() },
                                    onSave = { newFullContent ->
                                        scope.launch {
                                            val updated = currentBackup.copy(
                                                content = newFullContent,
                                                timestamp = System.currentTimeMillis()
                                            )
                                            repository.insertBackup(updated)
                                        }
                                    }
                                )
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
     * Trata um arquivo .as/.pg enviado por outro app (ação VER ou EDITAR).
     *
     * 1. Lê o nome e o texto do arquivo.
     * 2. Salva como backup temporário (sem robô: robotId = -1 e sem criar arquivo na pasta).
     * 3. Abre o visualizador externo desse backup.
     */
    private suspend fun handleIntent(intent: Intent?, repository: my.robots.core.data.RobotRepository, navController: androidx.navigation.NavController) {
        if (intent?.action == Intent.ACTION_VIEW || intent?.action == Intent.ACTION_EDIT) {
            val uri: Uri? = intent.data
            uri?.let {
                try {
                    val contentResolver = applicationContext.contentResolver
                    val fileName = my.robots.core.common.FileUtil.getFileName(applicationContext, it) ?: "file.as"
                    
                    val content = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        contentResolver.openInputStream(it)?.bufferedReader()?.use { it.readText() }
                    } ?: ""
                    
                    // guarda como backup temporário (sem robô dono)
                    val tempBackup = my.robots.core.model.Backup(
                        robotId = -1,
                        backupName = "Arquivo Externo",
                        fileName = fileName,
                        content = content,
                        timestamp = System.currentTimeMillis()
                    )
                    val backupId = repository.insertBackup(tempBackup, saveToFile = false)
                    
                    navController.navigate("external_viewer/$backupId")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
