package my.robots.ui.robot

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.robots.data.model.Manufacturer
import my.robots.data.model.Robot
import java.math.BigInteger
import java.net.InetAddress
import java.nio.ByteOrder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RobotListScreen(
    viewModel: RobotViewModel? = null,
    robotsList: List<Robot> = emptyList(),
    onRobotClick: (Robot) -> Unit = {},
    onTerminalClick: (Robot) -> Unit = {},
    onMultiTerminalClick: (String) -> Unit = {},
    onDeleteRobot: (Robot) -> Unit = {},
    onAddRobot: (name: String, ip: String, port: Int, project: String, manufacturer: Manufacturer, autoLogin: Boolean, loginUser: String, loginPassword: String) -> Unit = { _, _, _, _, _, _, _, _ -> },
    onUpdateRobot: (Robot) -> Unit = {}
) {
    val robots by if (viewModel != null) viewModel.robots.collectAsState() else remember { mutableStateOf(robotsList) }
    var showAddDialog by remember { mutableStateOf(false) }
    var robotToEdit by remember { mutableStateOf<Robot?>(null) }
    var robotToDelete by remember { mutableStateOf<Robot?>(null) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var sortAlphabetical by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    val wifiInfo = rememberWifiInfo(context)

    // Estado para controlar quais seções estão expandidas
    val expandedSections = remember { mutableStateMapOf<String, Boolean>() }

    // Lista de projetos existentes para sugestão no diálogo
    val existingProjects = remember(robots) {
        robots.map { it.project }.distinct()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("My Robots") },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { sortAlphabetical = !sortAlphabetical }) {
                            Icon(
                                imageVector = Icons.Rounded.SortByAlpha,
                                contentDescription = "Ordenar",
                                tint = if (sortAlphabetical) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clickable { showSettingsMenu = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Wifi,
                                contentDescription = "Wifi Status",
                                tint = if (wifiInfo.isConnected) MaterialTheme.colorScheme.primary 
                                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(20.dp)
                            )
                            if (!wifiInfo.isConnected) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Disconnected",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        Box {
                            IconButton(onClick = { showSettingsMenu = true }) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                            DropdownMenu(
                                expanded = showSettingsMenu,
                                onDismissRequest = { showSettingsMenu = false }
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    Text(
                                        text = if (wifiInfo.isConnected) wifiInfo.ssid else "Desconectado",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "IP: ${wifiInfo.ip}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Configurar Wifi") },
                                    onClick = {
                                        showSettingsMenu = false
                                        val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
                                        context.startActivity(intent)
                                    },
                                    leadingIcon = { Icon(Icons.Default.Wifi, null) }
                                )
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.padding(bottom = 16.dp, end = 8.dp) // Posicionado bem no fim
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Robot")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (robots.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No robots registered yet.")
                }
            } else {
                val groupedRobots = remember(robots, sortAlphabetical) {
                    val baseGroups = robots.groupBy { it.manufacturer }.toMutableMap()
                    
                    // Ordena Fabricantes
                    val sortedManufacturers = if (sortAlphabetical) {
                        baseGroups.keys.sortedBy { it.displayName }
                    } else {
                        baseGroups.keys.toList()
                    }

                    sortedManufacturers.associateWith { manufacturer ->
                        val robotsOfManufacturer = baseGroups[manufacturer] ?: emptyList()
                        val projectGroups = robotsOfManufacturer.groupBy { it.project }
                        
                        // Ordena Projetos e Robôs dentro de cada projeto
                        val sortedProjects = if (sortAlphabetical) {
                            projectGroups.keys.sorted()
                        } else {
                            projectGroups.keys.toList()
                        }

                        sortedProjects.associateWith { projectName ->
                            val robotsInProject = projectGroups[projectName] ?: emptyList()
                            if (sortAlphabetical) {
                                robotsInProject.sortedBy { it.name }
                            } else {
                                robotsInProject
                            }
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    groupedRobots.forEach { (manufacturer, projects) ->
                        item {
                            val manufacturerKey = manufacturer.name
                            val isExpanded = expandedSections[manufacturerKey] ?: true
                            
                            ManufacturerHeader(
                                manufacturer = manufacturer,
                                isExpanded = isExpanded,
                                onToggle = { expandedSections[manufacturerKey] = !isExpanded }
                            )
                        }

                        if (expandedSections[manufacturer.name] ?: true) {
                            projects.forEach { (project, robotsInProject) ->
                                item {
                                    val projectKey = "${manufacturer.name}_$project"
                                    val isProjectExpanded = expandedSections[projectKey] ?: true
                                    
                                    ProjectHeader(
                                        projectName = project,
                                        isExpanded = isProjectExpanded,
                                        onToggle = { expandedSections[projectKey] = !isProjectExpanded },
                                        onMultiTerminalClick = { onMultiTerminalClick(project) }
                                    )
                                }

                                if (expandedSections["${manufacturer.name}_$project"] ?: true) {
                                    items(robotsInProject) { robot ->
                                        RobotItem(
                                            robot = robot,
                                            onClick = { onRobotClick(robot) },
                                            onTerminalClick = { onTerminalClick(robot) },
                                            onEdit = { robotToEdit = robot },
                                            onDelete = { robotToDelete = robot }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showAddDialog) {
            RobotDialog(
                existingProjects = existingProjects,
                onDismiss = { showAddDialog = false },
                onConfirm = { name, ip, port, project, manufacturer, autoLogin, loginUser, loginPassword ->
                    if (viewModel != null) viewModel.addRobot(name, ip, port, project, manufacturer, autoLogin, loginUser, loginPassword)
                    else onAddRobot(name, ip, port, project, manufacturer, autoLogin, loginUser, loginPassword)
                    showAddDialog = false
                }
            )
        }

        if (robotToEdit != null) {
            RobotDialog(
                robot = robotToEdit,
                existingProjects = existingProjects,
                onDismiss = { robotToEdit = null },
                onConfirm = { name, ip, port, project, manufacturer, autoLogin, loginUser, loginPassword ->
                    val updated = robotToEdit!!.copy(
                        name = name, 
                        ip = ip, 
                        port = port, 
                        project = project, 
                        manufacturer = manufacturer,
                        autoLogin = autoLogin,
                        loginUser = loginUser,
                        loginPassword = loginPassword
                    )
                    if (viewModel != null) viewModel.updateRobot(updated)
                    else onUpdateRobot(updated)
                    robotToEdit = null
                }
            )
        }

        if (robotToDelete != null) {
            AlertDialog(
                onDismissRequest = { robotToDelete = null },
                title = { Text("Excluir Robô") },
                text = { Text("Tem certeza que deseja excluir o robô \"${robotToDelete?.name}\"? Esta ação não pode ser desfeita.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            robotToDelete?.let {
                                if (viewModel != null) viewModel.deleteRobot(it)
                                else onDeleteRobot(it)
                            }
                            robotToDelete = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Excluir")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { robotToDelete = null }) {
                        Text("Cancelar")
                    }
                }
            )
        }
    }
}

@Composable
fun ManufacturerHeader(
    manufacturer: Manufacturer,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = manufacturer.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
fun ProjectHeader(
    projectName: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onMultiTerminalClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
    ) {
        Row(
            modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Projeto: $projectName",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onMultiTerminalClick, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Terminal, 
                    contentDescription = "Terminal Geral", 
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun RobotItem(
    robot: Robot,
    onClick: () -> Unit,
    onTerminalClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 48.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = robot.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(text = "${robot.ip}:${robot.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row {
                IconButton(onClick = onTerminalClick, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Terminal, contentDescription = "Terminal", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

data class WifiInfoState(
    val ssid: String = "Desconectado",
    val ip: String = "0.0.0.0",
    val isConnected: Boolean = false
)

@Composable
fun rememberWifiInfo(context: Context): WifiInfoState {
    var wifiInfo by remember { mutableStateOf(WifiInfoState()) }
    
    LaunchedEffect(Unit) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        
        while(true) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            
            if (isWifi) {
                val ipAddress = wifiManager.connectionInfo.ipAddress
                val ipString = if (ipAddress != 0) {
                    val address = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
                        Integer.reverseBytes(ipAddress)
                    } else {
                        ipAddress
                    }
                    InetAddress.getByAddress(BigInteger.valueOf(address.toLong()).toByteArray()).hostAddress ?: "0.0.0.0"
                } else "0.0.0.0"

                var ssid = wifiManager.connectionInfo.ssid.removeSurrounding("\"")
                if (ssid == "<unknown ssid>") ssid = "Wifi Conectado"
                
                wifiInfo = WifiInfoState(ssid, ipString, true)
            } else {
                wifiInfo = WifiInfoState()
            }
            kotlinx.coroutines.delay(3000)
        }
    }
    
    return wifiInfo
}
