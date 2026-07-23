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
import my.robots.data.model.Manufacturer
import my.robots.ui.backup.AsCodeViewer
import my.robots.ui.backup.BackupHistoryScreen
import my.robots.ui.backup.BackupViewModel
import my.robots.ui.backup.BackupViewModelFactory
import my.robots.ui.robot.*
import my.robots.ui.theme.MyRobotsTheme
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        val app = application as MyRobotsApp
        val repository = app.robotRepository
        val terminalManager = app.terminalManager

        setContent {
            MyRobotsTheme {
                val scope = rememberCoroutineScope()
                val navController = rememberNavController()
                val context = LocalContext.current
                
                var showPermissionDialog by remember { mutableStateOf(false) }
                
                val requestPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    if (isGranted) {
                        checkAndCreateRootFolder()
                    }
                }

                val manageFilesLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) {
                    if (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true) {
                        checkAndCreateRootFolder()
                    }
                }

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

                LaunchedEffect(intent) {
                    handleIntent(intent, repository, navController)
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NavHost(navController = navController, startDestination = "robot_list") {
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
                            
                            var backup by remember { mutableStateOf<my.robots.data.model.Backup?>(null) }
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
                                        // Voltando para o Dashboard com a feature de Programas ativa
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

                        composable(
                            route = "external_viewer/{fileName}/{content}",
                            arguments = listOf(
                                navArgument("fileName") { type = NavType.StringType },
                                navArgument("content") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val fileName = backStackEntry.arguments?.getString("fileName") ?: "file.as"
                            val encodedContent = backStackEntry.arguments?.getString("content") ?: ""
                            val content = URLDecoder.decode(encodedContent, StandardCharsets.UTF_8.toString())
                            
                            AsCodeViewer(
                                fileName = fileName,
                                content = content,
                                onBack = { navController.popBackStack() }
                            )
                        }

                        composable(
                            route = "variable_viewer/{backupId}",
                            arguments = listOf(navArgument("backupId") { type = NavType.IntType })
                        ) { backStackEntry ->
                            val backupId = backStackEntry.arguments?.getInt("backupId") ?: return@composable
                            var backup by remember { mutableStateOf<my.robots.data.model.Backup?>(null) }
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

                        composable(
                            route = "code_viewer/{backupId}",
                            arguments = listOf(navArgument("backupId") { type = NavType.IntType })
                        ) { backStackEntry ->
                            val backupId = backStackEntry.arguments?.getInt("backupId") ?: return@composable
                            var backup by remember { mutableStateOf<my.robots.data.model.Backup?>(null) }
                            
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun handleIntent(intent: Intent?, repository: my.robots.data.repository.RobotRepository, navController: androidx.navigation.NavController) {
        if (intent?.action == Intent.ACTION_VIEW || intent?.action == Intent.ACTION_EDIT) {
            val uri: Uri? = intent.data
            uri?.let {
                try {
                    val contentResolver = applicationContext.contentResolver
                    val fileName = my.robots.utils.FileUtil.getFileName(applicationContext, it) ?: "file.as"
                    val content = contentResolver.openInputStream(it)?.bufferedReader()?.use { it.readText() } ?: ""
                    
                    val encodedContent = URLEncoder.encode(content, StandardCharsets.UTF_8.toString())
                    navController.navigate("external_viewer/$fileName/$encodedContent")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
