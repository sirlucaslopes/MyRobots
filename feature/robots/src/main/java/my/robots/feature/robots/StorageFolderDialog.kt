package my.robots.feature.robots

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import my.robots.core.data.storage.StorageLocation

/**
 * Janela "Pasta dos arquivos": mostra onde os backups (.as) estão sendo gravados e deixa
 * escolher outra pasta (seletor de pastas do Android) ou voltar para a pasta padrão.
 *
 * - Pasta padrão (Documentos/MyRobots): não pede permissão nenhuma, mas o app só enxerga os
 *   arquivos que ele mesmo gravou ali.
 * - Pasta escolhida: o app enxerga tudo o que está nela, inclusive arquivos copiados pelo PC.
 *   Escolher a pasta /MyRobots antiga traz de volta os arquivos da v1.1.
 */
@Composable
fun StorageFolderDialog(
    location: StorageLocation,
    isBusy: Boolean,
    message: String?,
    onChooseFolder: () -> Unit,
    onUseDefault: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pasta dos arquivos") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (location) {
                    StorageLocation.Default -> {
                        Text("Documentos/MyRobots (pasta padrão)", fontWeight = FontWeight.Bold)
                        Text(
                            "Aqui o app só enxerga os arquivos que ele mesmo gravou. Para ver também " +
                                "arquivos copiados pelo PC, ou os da pasta /MyRobots antiga, escolha a pasta abaixo.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    is StorageLocation.Folder -> {
                        Text(folderLabel(location.uri), fontWeight = FontWeight.Bold)
                        if (location.available) {
                            Text(
                                "Pasta escolhida por você. O app enxerga todos os arquivos .as dela.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            Text(
                                "Esta pasta não está mais disponível (foi apagada ou a permissão foi " +
                                    "retirada). Enquanto isso os arquivos vão para Documentos/MyRobots. " +
                                    "Escolha a pasta de novo.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                if (isBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (message != null) {
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Button(onClick = onChooseFolder, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
                    Text("Escolher pasta")
                }
                if (location is StorageLocation.Folder) {
                    OutlinedButton(onClick = onUseDefault, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
                        Text("Usar a pasta padrão")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fechar") }
        }
    )
}

/**
 * Nome legível da pasta escolhida (ex.: "Documents/MyRobots"), a partir da Uri do seletor.
 */
private fun folderLabel(treeUri: Uri): String = try {
    DocumentsContract.getTreeDocumentId(treeUri).substringAfter(':').ifEmpty { "Armazenamento interno" }
} catch (e: Exception) {
    treeUri.lastPathSegment ?: "Pasta escolhida"
}
