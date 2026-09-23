package my.robots.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Tela de configuração de rede: escolher IP automático (DHCP) ou fixo (Static).
 *
 * Com IP fixo aparecem os campos de IP, gateway e máscara.
 * ATENÇÃO: ainda não está ligada à navegação e o botão "Save" só fecha a tela,
 * sem salvar nada de verdade.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    var ipAssignment by remember { mutableStateOf("DHCP") }
    var staticIp by remember { mutableStateOf("192.168.1.100") }
    var gateway by remember { mutableStateOf("192.168.1.1") }
    var subnetMask by remember { mutableStateOf("255.255.255.0") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("IP Assignment", style = MaterialTheme.typography.titleMedium)
            
            val options = listOf("DHCP", "Static")
            Column(Modifier.selectableGroup()) {
                options.forEach { text ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .selectable(
                                selected = (text == ipAssignment),
                                onClick = { ipAssignment = text },
                                role = Role.RadioButton
                            )
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (text == ipAssignment),
                            onClick = null // null porque a linha inteira já é clicável (melhor para leitores de tela)
                        )
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
            }

            if (ipAssignment == "Static") {
                OutlinedTextField(
                    value = staticIp,
                    onValueChange = { staticIp = it },
                    label = { Text("Static IP Address") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = gateway,
                    onValueChange = { gateway = it },
                    label = { Text("Gateway") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = subnetMask,
                    onValueChange = { subnetMask = it },
                    label = { Text("Subnet Mask") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = { /* falta implementar: salvar as configurações de verdade */ onBack() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Settings")
            }
        }
    }
}
