package my.robots.feature.robots

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.DeviceHub
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.robots.core.designsystem.AppTopBar
import my.robots.core.designsystem.HeartbeatDot
import my.robots.core.designsystem.HeartbeatLegend
import my.robots.core.designsystem.SuccessGreen
import my.robots.core.designsystem.label
import my.robots.core.model.HeartbeatState
import my.robots.core.model.Manufacturer
import my.robots.core.model.Robot
import java.math.BigInteger
import java.net.InetAddress
import java.nio.ByteOrder

/**
 * Tela inicial: a lista dos robôs cadastrados.
 *
 * Os robôs são agrupados em FABRICANTE > PROJETO > ROBÔ, e cada grupo pode ser
 * aberto ou fechado. Na barra do topo há: robôs conectados (popup para conectar
 * vários robôs de uma vez, com heartbeat), ordenar A-Z, o ícone do Wifi (nome da
 * rede e IP do celular) e a engrenagem (configurações de Wifi do Android e "Pasta dos
 * arquivos", onde se escolhe onde os backups são gravados).
 * O botão "+" cadastra um robô novo.
 *
 * - onRobotClick: tocar no robô (abre o painel dele).
 * - onTerminalClick: ícone de terminal do robô.
 * - onOpenProject: ícone do projeto (abre a tela de Projeto, com a cabine).
 * - connectedRobotsViewModel: conexão e pulso de cada robô (o botão Conectar de cada cartão e o
 *   "Conectar todos" de cada projeto). Se vier null (ex.: pré-visualização), só mostra.
 * Os parâmetros onAddRobot/onUpdateRobot/onDeleteRobot/robotsList só são usados
 * quando não há ViewModel (por exemplo, em pré-visualização).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RobotListScreen(
    viewModel: RobotViewModel? = null,
    connectedRobotsViewModel: ConnectedRobotsViewModel? = null,
    robotsList: List<Robot> = emptyList(),
    onRobotClick: (Robot) -> Unit = {},
    onTerminalClick: (Robot) -> Unit = {},
    onOpenProject: (String) -> Unit = {},
    onOpenManufacturers: () -> Unit = {},
    onDeleteRobot: (Robot) -> Unit = {},
    onAddRobot: (name: String, ip: String, port: Int, project: String, manufacturer: Manufacturer, autoLogin: Boolean, loginUser: String, loginPassword: String) -> Unit = { _, _, _, _, _, _, _, _ -> },
    onUpdateRobot: (Robot) -> Unit = {}
) {
    val robots by if (viewModel != null) viewModel.robots.collectAsState() else remember { mutableStateOf(robotsList) }
    var showAddDialog by remember { mutableStateOf(false) }
    var robotToEdit by remember { mutableStateOf<Robot?>(null) }
    var robotToDelete by remember { mutableStateOf<Robot?>(null) }
    var sortAlphabetical by rememberSaveable { mutableStateOf(false) }
    // conexão e pulso de cada robô (antes ficavam num popup à parte, "Robôs Conectados")
    val connectedIds by (connectedRobotsViewModel?.connectedIds?.collectAsState() ?: remember { mutableStateOf(emptySet<Int>()) })
    val heartbeats by (connectedRobotsViewModel?.heartbeats?.collectAsState() ?: remember { mutableStateOf(emptyMap<Int, HeartbeatState>()) })
    val attempts by (connectedRobotsViewModel?.attempts?.collectAsState() ?: remember { mutableStateOf(emptyMap<Int, String>()) })
    var showStorageDialog by remember { mutableStateOf(false) }

    // Seletor de pastas do Android (SAF) para a janela "Pasta dos arquivos".
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel?.chooseStorageFolder(uri)
    }
    
    val context = LocalContext.current
    val wifiInfo = rememberWifiInfo(context)

    // Grupos (fabricante ou projeto) fechados e a posição da lista. Os dois são "saveable": ao
    // voltar do painel de um robô, a lista reaparece com os mesmos grupos e no mesmo lugar.
    var collapsedSections by rememberSaveable { mutableStateOf(listOf<String>()) }
    fun toggleSection(key: String) {
        collapsedSections = if (key in collapsedSections) collapsedSections - key else collapsedSections + key
    }
    val listState = rememberLazyListState()

    // Nomes de projeto que já existem, para sugerir no cadastro de um robô novo.
    val existingProjects = remember(robots) {
        robots.map { it.project }.distinct()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            val connectedCount = robots.count { it.id in connectedIds }
            AppTopBar(
                title = "My Robots",
                subtitle = listOf(
                    "$connectedCount de ${robots.size} conectados",
                    if (wifiInfo.isConnected) "${wifiInfo.ssid} · ${wifiInfo.ip}" else "sem Wi-Fi"
                ).joinToString(" · "),
                menu = { close ->
                    DropdownMenuItem(
                        text = { Text("Ordenar A-Z") },
                        leadingIcon = { Icon(Icons.Rounded.SortByAlpha, null) },
                        trailingIcon = { if (sortAlphabetical) Icon(Icons.Default.Check, "Ligado") },
                        onClick = { close(); sortAlphabetical = !sortAlphabetical }
                    )
                    DropdownMenuItem(
                        text = { Text("Fabricantes: pesquisa e comandos") },
                        leadingIcon = { Icon(Icons.Default.Tune, null) },
                        onClick = { close(); onOpenManufacturers() }
                    )
                    HorizontalDivider()
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text = if (wifiInfo.isConnected) wifiInfo.ssid else "Wi-Fi desconectado",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "IP do celular: ${wifiInfo.ip}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Configurar Wi-Fi") },
                        onClick = {
                            close()
                            context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                        },
                        leadingIcon = { Icon(Icons.Default.Wifi, null) }
                    )
                    if (viewModel != null) {
                        DropdownMenuItem(
                            text = { Text("Pasta dos arquivos") },
                            onClick = {
                                close()
                                viewModel.refreshStorage()
                                showStorageDialog = true
                            },
                            leadingIcon = { Icon(Icons.Default.Folder, null) }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.padding(bottom = 16.dp, end = 8.dp) // afasta o botão da borda de baixo
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
                // Monta a árvore para mostrar na tela: Fabricante -> Projeto -> lista de robôs.
                // Com a ordenação A-Z ligada, ordena cada nível por nome.
                val groupedRobots = remember(robots, sortAlphabetical) {
                    val baseGroups = robots.groupBy { it.manufacturer }.toMutableMap()
                    
                    // nível 1: fabricantes
                    val sortedManufacturers = if (sortAlphabetical) {
                        baseGroups.keys.sortedBy { it.displayName }
                    } else {
                        baseGroups.keys.toList()
                    }

                    sortedManufacturers.associateWith { manufacturer ->
                        val robotsOfManufacturer = baseGroups[manufacturer] ?: emptyList()
                        val projectGroups = robotsOfManufacturer.groupBy { it.project }
                        
                        // nível 2 e 3: projetos e, dentro de cada um, os robôs
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
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    item(key = "legenda") {
                        HeartbeatLegend(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                    groupedRobots.forEach { (manufacturer, projects) ->
                        item {
                            val manufacturerKey = manufacturer.name
                            val isExpanded = manufacturerKey !in collapsedSections
                            
                            ManufacturerHeader(
                                manufacturer = manufacturer,
                                isExpanded = isExpanded,
                                onToggle = { toggleSection(manufacturerKey) }
                            )
                        }

                        if (manufacturer.name !in collapsedSections) {
                            projects.forEach { (project, robotsInProject) ->
                                item {
                                    val projectKey = "${manufacturer.name}_$project"
                                    val isProjectExpanded = projectKey !in collapsedSections
                                    
                                    val connectedInProject = robotsInProject.count { it.id in connectedIds }
                                    ProjectHeader(
                                        projectName = project,
                                        isExpanded = isProjectExpanded,
                                        connected = connectedInProject,
                                        total = robotsInProject.size,
                                        onToggle = { toggleSection(projectKey) },
                                        onOpenProject = { onOpenProject(project) },
                                        onToggleAll = {
                                            if (connectedInProject == robotsInProject.size) connectedRobotsViewModel?.disconnectProject(robotsInProject)
                                            else connectedRobotsViewModel?.connectProject(robotsInProject)
                                        }
                                    )
                                }

                                if ("${manufacturer.name}_$project" !in collapsedSections) {
                                    items(robotsInProject) { robot ->
                                        val isConnected = robot.id in connectedIds
                                        RobotItem(
                                            robot = robot,
                                            isConnected = isConnected,
                                            heartbeat = heartbeats[robot.id] ?: HeartbeatState.DISCONNECTED,
                                            attempt = if (isConnected) null else attempts[robot.id],
                                            onToggleConnect = {
                                                if (isConnected) connectedRobotsViewModel?.disconnect(robot)
                                                else connectedRobotsViewModel?.connect(robot)
                                            },
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

        // Janela de cadastro de um robô novo.
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

        // Janela de edição do robô escolhido.
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

        // Pergunta de confirmação antes de excluir o robô.
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

        // Janela "Pasta dos arquivos": onde os backups são gravados (padrão ou pasta escolhida).
        if (showStorageDialog && viewModel != null) {
            val location by viewModel.storageLocation.collectAsState()
            val busy by viewModel.storageBusy.collectAsState()
            val message by viewModel.storageMessage.collectAsState()
            StorageFolderDialog(
                location = location,
                isBusy = busy,
                message = message,
                onChooseFolder = { folderPicker.launch(null) },
                onUseDefault = { viewModel.useDefaultStorage() },
                onDismiss = { showStorageDialog = false }
            )
        }

    }
}

/**
 * Faixa com o nome do fabricante. Tocar nela abre ou fecha o grupo.
 */
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

/**
 * Faixa com o nome do projeto e quantos robôs dele estão conectados. Tocar nela abre ou fecha
 * o grupo; o botão conecta (ou desconecta, se todos já estiverem) os robôs do projeto; o ícone
 * abre a tela de Projeto (a cabine).
 */
@Composable
fun ProjectHeader(
    projectName: String,
    isExpanded: Boolean,
    connected: Int,
    total: Int,
    onToggle: () -> Unit,
    onOpenProject: () -> Unit,
    onToggleAll: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
    ) {
        Row(
            modifier = Modifier.padding(start = 32.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = projectName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    text = "$connected de $total conectados",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (connected > 0) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onToggleAll, enabled = total > 0) {
                Text(if (total > 0 && connected == total) "Desconectar todos" else "Conectar todos", fontSize = 12.sp)
            }
            IconButton(onClick = onOpenProject, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Rounded.GridView,
                    contentDescription = "Abrir projeto",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Cartão de um robô: LED de pulso, nome, IP, série e estado; o botão de conectar (verde quando
 * conectado: toca para desconectar), o terminal e um ⋮ com editar e excluir. Tocar no cartão
 * abre o painel do robô.
 */
@Composable
fun RobotItem(
    robot: Robot,
    isConnected: Boolean,
    heartbeat: HeartbeatState,
    attempt: String? = null,
    onToggleConnect: () -> Unit,
    onClick: () -> Unit,
    onTerminalClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 2.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HeartbeatDot(state = heartbeat)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(text = robot.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                    robot.serialNumber?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Nº $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }
                }
                Text(
                    text = "${robot.ip}:${robot.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                // tentativa em andamento ("Conectando…") ou o motivo da falha, no lugar do status
                Text(
                    text = attempt ?: heartbeat.label(),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    color = when {
                        attempt?.startsWith("Não") == true -> MaterialTheme.colorScheme.error
                        attempt != null -> MaterialTheme.colorScheme.primary
                        heartbeat == HeartbeatState.ALIVE -> SuccessGreen
                        heartbeat == HeartbeatState.STALE -> Color(0xFFFFA000)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            val connecting = attempt == "Conectando…"
            Button(
                onClick = onToggleConnect,
                enabled = !connecting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected) SuccessGreen else MaterialTheme.colorScheme.primary
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(34.dp)
            ) {
                if (connecting) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (isConnected) "Desconectar" else "Conectar", fontSize = 12.sp)
            }
            IconButton(onClick = onTerminalClick, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.Terminal, contentDescription = "Terminal", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Mais opções do robô", modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Editar") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menuOpen = false; onEdit() }
                    )
                    DropdownMenuItem(
                        text = { Text("Excluir", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}

/**
 * Situação do Wifi do celular: nome da rede (SSID), IP do celular e se está conectado.
 */
data class WifiInfoState(
    val ssid: String = "Desconectado",
    val ip: String = "0.0.0.0",
    val isConnected: Boolean = false
)

/**
 * Vigia o Wifi do celular e devolve o estado atualizado a cada 3 segundos.
 * Serve para o usuário conferir se está na mesma rede do robô.
 */
@Composable
fun rememberWifiInfo(context: Context): WifiInfoState {
    var wifiInfo by remember { mutableStateOf(WifiInfoState()) }
    
    LaunchedEffect(Unit) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        
        // repete para sempre (enquanto a tela existir): lê o Wifi, guarda e espera 3 segundos
        while(true) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            
            if (isWifi) {
                val ipAddress = wifiManager.connectionInfo.ipAddress
                val ipString = if (ipAddress != 0) {
                    // O Android entrega o IP como um número inteiro (ordem invertida em alguns aparelhos).
                    // Aqui ele é ajeitado e vira texto, como 192.168.1.10.
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
