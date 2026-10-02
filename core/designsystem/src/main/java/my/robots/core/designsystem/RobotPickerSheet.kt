package my.robots.core.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import my.robots.core.model.HeartbeatState
import my.robots.core.model.Robot

/** Etapa do envio para um robô. */
enum class SendState { CONNECTING, SENDING, DONE, FAILED }

/** Andamento do envio para um robô, com uma mensagem curta ("LOAD concluído", "não conectou"...). */
data class SendProgress(val state: SendState, val message: String = "") {
    val finished: Boolean get() = state == SendState.DONE || state == SendState.FAILED
}

/**
 * Lista para escolher os robôs de destino de um envio, no mesmo formato do popup "Robôs
 * Conectados": agrupada por projeto, com o LED de heartbeat, o estado e a série de cada robô.
 *
 * Marca-se um ou mais robôs e toca-se em "Enviar". Quem usa esta lista conecta cada robô (se
 * preciso), espera o login e só então envia; o andamento de cada um chega em [progress] e
 * aparece na própria linha. Terminado o envio, o botão vira "Fechar".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RobotPickerSheet(
    title: String,
    itemName: String,
    robots: List<Robot>,
    connectedIds: Set<Int>,
    heartbeats: Map<Int, HeartbeatState>,
    progress: Map<Int, SendProgress>,
    onSend: (List<Robot>) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(setOf<Int>()) }
    val running = progress.isNotEmpty() && progress.values.any { !it.finished }
    val finished = progress.isNotEmpty() && !running
    val byProject = robots.groupBy { it.project }.toSortedMap()

    // abre inteira: o botão "Enviar" fica no fim e precisa aparecer sem arrastar
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { if (!running) onDismiss() }, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Enviar '$itemName'. Marque os robôs; os desconectados são conectados antes do envio.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                byProject.forEach { (project, list) ->
                    item(key = "p_$project") {
                        val ids = list.map { it.id }.toSet()
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            Text(
                                "Projeto: $project",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { selected = if (selected.containsAll(ids)) selected - ids else selected + ids },
                                enabled = progress.isEmpty()
                            ) { Text(if (selected.containsAll(ids)) "Desmarcar todos" else "Marcar todos") }
                        }
                    }
                    items(list.sortedBy { it.name }, key = { it.id }) { robot ->
                        val isChecked = robot.id in selected
                        val heartbeat = heartbeats[robot.id] ?: HeartbeatState.DISCONNECTED
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = progress.isEmpty()) {
                                    selected = if (isChecked) selected - robot.id else selected + robot.id
                                }
                                .padding(end = 8.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { selected = if (it) selected + robot.id else selected - robot.id },
                                enabled = progress.isEmpty()
                            )
                            HeartbeatDot(state = heartbeat)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(robot.name, fontWeight = FontWeight.Bold)
                                Text(
                                    robot.ip + (robot.serialNumber?.let { " · Nº $it" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            ProgressLabel(
                                progress = progress[robot.id],
                                idleText = if (robot.id in connectedIds) heartbeat.label() else "Desconectado"
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (finished) {
                    val ok = progress.values.count { it.state == SendState.DONE }
                    Text(
                        "$ok de ${progress.size} enviados",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = onDismiss) { Text("Fechar") }
                } else {
                    TextButton(onClick = onDismiss, enabled = !running) { Text("Cancelar") }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { onSend(robots.filter { it.id in selected }) },
                        enabled = selected.isNotEmpty() && !running
                    ) {
                        Text(
                            when {
                                running -> "Enviando…"
                                selected.size == 1 -> "Enviar para 1 robô"
                                else -> "Enviar para ${selected.size} robôs"
                            }
                        )
                    }
                }
            }
        }
    }
}

/** Andamento do envio na linha do robô; sem envio, mostra o estado da conexão. */
@Composable
private fun ProgressLabel(progress: SendProgress?, idleText: String) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(max = 150.dp)) {
        when (progress?.state) {
            null -> Text(idleText, style = MaterialTheme.typography.labelMedium, color = muted)
            SendState.CONNECTING, SendState.SENDING -> {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (progress.state == SendState.CONNECTING) "Conectando…" else "Enviando…",
                    style = MaterialTheme.typography.labelMedium
                )
            }
            SendState.DONE -> {
                Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF43A047), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(progress.message.ifBlank { "Enviado" }, style = MaterialTheme.typography.labelMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            SendState.FAILED -> {
                Icon(Icons.Rounded.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(progress.message.ifBlank { "Falhou" }, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
