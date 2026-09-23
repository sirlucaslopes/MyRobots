package my.robots.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import my.robots.core.model.WifiConfig

/**
 * Tela que lista as redes Wifi configuradas, com botão "+" para adicionar.
 * ATENÇÃO: ainda não está ligada à navegação nem ao banco de dados.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiSettingsScreen(
    onBack: () -> Unit,
    configs: List<WifiConfig> = emptyList(),
    onAddConfig: (WifiConfig) -> Unit = {},
    onDeleteConfig: (WifiConfig) -> Unit = {}
) {
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configurações Wifi") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Config")
            }
        }
    ) { padding ->
        if (configs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Nenhuma rede wifi configurada.")
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(configs) { config ->
                    WifiConfigItem(
                        config = config,
                        onDelete = { onDeleteConfig(config) }
                    )
                }
            }
        }

        if (showAddDialog) {
            WifiConfigDialog(
                onDismiss = { showAddDialog = false },
                onConfirm = { onAddConfig(it) }
            )
        }
    }
}

/**
 * Cartão de uma rede Wifi: nome (SSID), se usa IP fixo ou DHCP e o botão de excluir.
 */
@Composable
fun WifiConfigItem(config: WifiConfig, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Wifi, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = config.ssid, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (config.isStaticIp) {
                    Text(text = "IP Estático: ${config.ipAddress}", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(text = "DHCP", style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * Janela para cadastrar uma rede Wifi: SSID, senha e, opcionalmente, IP fixo
 * (IP, gateway e máscara). Devolve o WifiConfig pelo onConfirm.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiConfigDialog(onDismiss: () -> Unit, onConfirm: (WifiConfig) -> Unit) {
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isStaticIp by remember { mutableStateOf(false) }
    var ipAddress by remember { mutableStateOf("") }
    var gateway by remember { mutableStateOf("") }
    var mask by remember { mutableStateOf("255.255.255.0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nova Configuração Wifi") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = ssid, onValueChange = { ssid = it }, label = { Text("SSID (Nome da Rede)") })
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Senha") })
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isStaticIp, onCheckedChange = { isStaticIp = it })
                    Text("IP Estático")
                }

                if (isStaticIp) {
                    OutlinedTextField(value = ipAddress, onValueChange = { ipAddress = it }, label = { Text("Endereço IP") })
                    OutlinedTextField(value = gateway, onValueChange = { gateway = it }, label = { Text("Gateway") })
                    OutlinedTextField(value = mask, onValueChange = { mask = it }, label = { Text("Máscara") })
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(WifiConfig(
                    ssid = ssid,
                    password = password,
                    isStaticIp = isStaticIp,
                    ipAddress = if (isStaticIp) ipAddress else null,
                    gateway = if (isStaticIp) gateway else null,
                    mask = if (isStaticIp) mask else null
                ))
                onDismiss()
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}
