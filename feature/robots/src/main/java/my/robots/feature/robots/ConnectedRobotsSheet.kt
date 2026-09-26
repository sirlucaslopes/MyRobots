package my.robots.feature.robots

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.robots.core.model.Robot
import my.robots.core.designsystem.HeartbeatDot
import my.robots.core.designsystem.label
import my.robots.core.model.HeartbeatState

/**
 * Popup "Robôs Conectados": lista os robôs agrupados por projeto, com o heartbeat de cada
 * um e o botão de conectar/desconectar ali mesmo. Dá para conectar em mais de um robô ao
 * mesmo tempo, e cada projeto tem um atalho para conectar/desconectar todos de uma vez.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectedRobotsSheet(
    viewModel: ConnectedRobotsViewModel,
    onDismiss: () -> Unit
) {
    val robotsByProject by viewModel.robotsByProject.collectAsState()
    val connectedIds by viewModel.connectedIds.collectAsState()
    val heartbeats by viewModel.heartbeats.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Text(
                text = "Robôs Conectados",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (robotsByProject.isEmpty()) {
                Text(
                    text = "Nenhum robô cadastrado.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                    robotsByProject.forEach { (project, robots) ->
                        item(key = "header_$project") {
                            val allConnected = robots.isNotEmpty() && robots.all { it.id in connectedIds }
                            ConnectedProjectHeader(
                                projectName = project,
                                allConnected = allConnected,
                                onConnectAll = { viewModel.connectProject(project) },
                                onDisconnectAll = { viewModel.disconnectProject(project) }
                            )
                        }
                        items(robots, key = { it.id }) { robot ->
                            ConnectedRobotRow(
                                robot = robot,
                                isConnected = robot.id in connectedIds,
                                heartbeat = heartbeats[robot.id] ?: HeartbeatState.DISCONNECTED,
                                onToggleConnect = {
                                    if (robot.id in connectedIds) viewModel.disconnect(robot)
                                    else viewModel.connect(robot)
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
 * Faixa do projeto no popup: nome e um atalho para conectar (ou desconectar, se todos
 * já estiverem conectados) todos os robôs dele de uma vez.
 */
@Composable
fun ConnectedProjectHeader(
    projectName: String,
    allConnected: Boolean,
    onConnectAll: () -> Unit,
    onDisconnectAll: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Projeto: $projectName",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = if (allConnected) onDisconnectAll else onConnectAll) {
                Text(if (allConnected) "Desconectar Todos" else "Conectar Todos", fontSize = 12.sp)
            }
        }
    }
}

/**
 * Linha de um robô no popup: bolinha de heartbeat, nome/IP, texto do status e o
 * botão de conectar/desconectar.
 */
@Composable
fun ConnectedRobotRow(
    robot: Robot,
    isConnected: Boolean,
    heartbeat: HeartbeatState,
    onToggleConnect: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeartbeatDot(state = heartbeat)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = robot.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(
                text = "${robot.ip}:${robot.port} · ${heartbeat.label()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = onToggleConnect,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isConnected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.height(32.dp)
        ) {
            Text(if (isConnected) "Desconectar" else "Conectar", fontSize = 12.sp)
        }
    }
}
