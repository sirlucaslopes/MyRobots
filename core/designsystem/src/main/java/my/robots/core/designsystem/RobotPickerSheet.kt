package my.robots.core.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import my.robots.core.model.HeartbeatState
import my.robots.core.model.Robot

/**
 * Lista para escolher o robô de destino de um envio, no mesmo formato do popup "Robôs
 * Conectados": agrupada por projeto, com o LED de heartbeat e o estado de cada robô.
 *
 * Tocar num robô chama [onPick]. Quem usa esta lista conecta o robô (se preciso) e só envia
 * com ele pronto; enquanto isso, a linha de [connectingId] mostra "Conectando…", e a de
 * [failedId] mostra que não conectou.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RobotPickerSheet(
    title: String,
    itemName: String,
    robots: List<Robot>,
    connectedIds: Set<Int>,
    heartbeats: Map<Int, HeartbeatState>,
    connectingId: Int?,
    failedId: Int?,
    onPick: (Robot) -> Unit,
    onDismiss: () -> Unit
) {
    val byProject = robots.groupBy { it.project }.toSortedMap()
    ModalBottomSheet(onDismissRequest = { if (connectingId == null) onDismiss() }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Enviar '$itemName'. Tocar num robô desconectado conecta antes de enviar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                byProject.forEach { (project, list) ->
                    item(key = "p_$project") {
                        Text(
                            "Projeto: $project",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                        )
                    }
                    items(list.sortedBy { it.name }, key = { it.id }) { robot ->
                        val connected = robot.id in connectedIds
                        val heartbeat = heartbeats[robot.id] ?: HeartbeatState.DISCONNECTED
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = connectingId == null) { onPick(robot) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HeartbeatDot(state = heartbeat)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(robot.name, fontWeight = FontWeight.Bold)
                                Text(
                                    robot.ip + (robot.serialNumber?.let { " · Nº $it" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            when {
                                robot.id == connectingId -> {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Conectando…", style = MaterialTheme.typography.labelMedium)
                                }
                                robot.id == failedId -> Text(
                                    "Não conectou",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                                else -> Text(
                                    if (connected) heartbeat.label() else "Desconectado",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss, enabled = connectingId == null, modifier = Modifier.align(Alignment.End)) {
                Text("Cancelar")
            }
        }
    }
}
