package my.robots.feature.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import my.robots.core.designsystem.FormDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Enviar backups" (ações em grupo): o último backup de cada robô marcado vai num .zip só, um
 * arquivo separado por robô. O destino é escolhido uma vez: **Compartilhar** (WhatsApp, e-mail…)
 * ou **Salvar no aparelho** (uma pasta).
 * - rows: robôs e o último backup de cada um (null = ainda carregando);
 * - busy: texto enquanto o .zip é montado (null = parado).
 */
@Composable
internal fun ExportBackupsDialog(
    rows: List<ProjectViewModel.ExportRow>?,
    busy: String?,
    onShare: (Set<Int>) -> Unit,
    onSave: (Set<Int>) -> Unit,
    onDismiss: () -> Unit
) {
    val withBackup = rows.orEmpty().filter { it.backup != null }
    var chosen by remember { mutableStateOf(emptySet<Int>()) }
    // começa com todos os que têm backup marcados
    LaunchedEffect(rows) { chosen = withBackup.map { it.robot.id }.toSet() }
    val fmt = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }
    val ok = chosen.isNotEmpty() && busy == null

    FormDialog(
        title = "Enviar backups",
        onDismiss = { if (busy == null) onDismiss() },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onSave(chosen) }, enabled = ok) {
                    Icon(Icons.Rounded.SaveAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Salvar")
                }
                Button(onClick = { onShare(chosen) }, enabled = ok) {
                    Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Compartilhar")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = busy == null) { Text("Cancelar") } }
    ) {
        Text(
            "O último backup de cada robô marcado vai num arquivo .zip, um arquivo separado por robô. " +
                "Compartilhar abre o WhatsApp, e-mail…; Salvar pergunta a pasta. Nada é pedido ao robô: " +
                "para backups de agora, use antes o Backup de todos.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (rows == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            return@FormDialog
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Robôs (${chosen.size} de ${withBackup.size})", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { chosen = if (chosen.size == withBackup.size) emptySet() else withBackup.map { it.robot.id }.toSet() }) {
                Text(if (chosen.size == withBackup.size) "Nenhum" else "Todos")
            }
        }
        rows.forEach { row ->
            val b = row.backup
            if (b == null) {
                CheckRow(checked = false, text = row.robot.name, supporting = "sem backup", onToggle = {})
            } else {
                CheckRow(
                    checked = row.robot.id in chosen,
                    text = row.robot.name,
                    supporting = fmt.format(Date(b.timestamp)),
                    onToggle = { chosen = if (row.robot.id in chosen) chosen - row.robot.id else chosen + row.robot.id }
                )
            }
        }
        if (busy != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(busy, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
