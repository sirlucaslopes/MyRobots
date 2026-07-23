package my.robots.ui.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.robots.data.model.BackupSummary
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupHistoryScreen(
    viewModel: BackupViewModel,
    onBack: () -> Unit,
    onViewDashboard: (BackupSummary) -> Unit,
    onViewCode: (BackupSummary) -> Unit,
    onCreateBackup: () -> Unit
) {
    val backups by viewModel.backups.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isDescending by viewModel.isDescending.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var showCreateOptions by remember { mutableStateOf(false) }
    var showShareOptions by remember { mutableStateOf<BackupSummary?>(null) }
    var showDuplicateDialog by remember { mutableStateOf<BackupSummary?>(null) }
    var backupToDelete by remember { mutableStateOf<BackupSummary?>(null) }
    val sheetState = rememberModalBottomSheetState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            uri?.let {
                scope.launch {
                    val contentResolver = context.contentResolver
                    val fileName = withContext(Dispatchers.IO) {
                        contentResolver.query(it, null, null, null, null)?.use { cursor ->
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIndex != -1 && cursor.moveToFirst()) {
                                cursor.getString(nameIndex)
                            } else null
                        }
                    } ?: "imported_backup.as"

                    withContext(Dispatchers.IO) {
                        try {
                            contentResolver.openInputStream(it)?.use { inputStream ->
                                val content = inputStream.bufferedReader().use { reader -> reader.readText() }
                                withContext(Dispatchers.Main) {
                                    viewModel.importBackup(fileName, content)
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Erro ao importar: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
    )

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
        onResult = { uri: Uri? ->
            uri?.let { targetUri ->
                showShareOptions?.let { backupSummary ->
                    scope.launch(Dispatchers.IO) {
                        try {
                            val fullBackup = viewModel.getFullBackup(backupSummary.id)
                            if (fullBackup != null) {
                                context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                                    outputStream.write(fullBackup.content.toByteArray())
                                }
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Arquivo salvo com sucesso!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Erro ao salvar arquivo: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
            showShareOptions = null
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Histórico de Backups") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { 
                        viewModel.syncBackupsWithFileSystem()
                        Toast.makeText(context, "Sincronizando com a pasta raiz...", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = "Sincronizar Arquivos",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = { viewModel.toggleSortOrder() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = "Ordenar",
                            tint = if (isDescending) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateOptions = true }) {
                Icon(Icons.Default.Add, contentDescription = "Criar Backup")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = viewModel::updateSearchQuery,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Buscar backups...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
            }

            if (backups.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Nenhum backup encontrado.")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Toque no robô acima para ler a pasta raiz",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(backups, key = { it.id }) { backup ->
                        BackupItem(
                            backup = backup,
                            onClick = { onViewDashboard(backup) },
                            onViewCode = { onViewCode(backup) },
                            onDelete = { backupToDelete = backup },
                            onShare = { showShareOptions = backup },
                            onDuplicate = { showDuplicateDialog = backup }
                        )
                    }
                }
            }
        }

        if (showCreateOptions) {
            ModalBottomSheet(
                onDismissRequest = { showCreateOptions = false },
                sheetState = sheetState
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp)
                ) {
                    Text(
                        text = "Criar Novo Backup",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(16.dp)
                    )
                    ListItem(
                        headlineContent = { Text("Baixar do Robô Conectado") },
                        supportingContent = { Text("Abre o terminal para comandos AS") },
                        leadingContent = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
                        modifier = Modifier.clickable {
                            showCreateOptions = false
                            onCreateBackup()
                        }
                    )
                    ListItem(
                        headlineContent = { Text("Importar da Memória Interna") },
                        supportingContent = { Text("Seleciona um arquivo .as no dispositivo") },
                        leadingContent = { Icon(Icons.Default.FileOpen, contentDescription = null) },
                        modifier = Modifier.clickable {
                            showCreateOptions = false
                            filePickerLauncher.launch(arrayOf("*/*"))
                        }
                    )
                }
            }
        }

        if (showShareOptions != null) {
            ModalBottomSheet(onDismissRequest = { showShareOptions = null }) {
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
                    Text("Opções de Exportação", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                    
                    ListItem(
                        headlineContent = { Text("Exportar para pasta específica") },
                        supportingContent = { Text("Escolha onde salvar o arquivo .as") },
                        leadingContent = { Icon(Icons.Default.SaveAlt, null) },
                        modifier = Modifier.clickable {
                            val backup = showShareOptions!!
                            saveFileLauncher.launch(backup.fileName)
                        }
                    )
                    
                    ListItem(
                        headlineContent = { Text("Compartilhar Arquivo") },
                        supportingContent = { Text("Enviar arquivo .as via WhatsApp, Email, etc.") },
                        leadingContent = { Icon(Icons.Default.Share, null) },
                        modifier = Modifier.clickable {
                            val backupSummary = showShareOptions!!
                            scope.launch {
                                try {
                                    val fullBackup = viewModel.getFullBackup(backupSummary.id)
                                    if (fullBackup != null) {
                                        val cacheDir = File(context.cacheDir, "shared_backups")
                                        if (!cacheDir.exists()) cacheDir.mkdirs()
                                        
                                        val file = File(cacheDir, fullBackup.fileName)
                                        file.writeText(fullBackup.content)
                                        
                                        val contentUri = FileProvider.getUriForFile(
                                            context,
                                            "my.robots.fileprovider",
                                            file
                                        )
                                        
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "application/octet-stream"
                                            putExtra(Intent.EXTRA_STREAM, contentUri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(intent, "Compartilhar Arquivo"))
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Erro ao preparar arquivo: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                                showShareOptions = null
                            }
                        }
                    )
                }
            }
        }

        if (showDuplicateDialog != null) {
            var newName by remember { mutableStateOf(showDuplicateDialog!!.backupName + " (Cópia)") }
            AlertDialog(
                onDismissRequest = { showDuplicateDialog = null },
                title = { Text("Duplicar Backup") },
                text = {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Novo nome") },
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        viewModel.duplicateBackup(showDuplicateDialog!!.id, newName)
                        showDuplicateDialog = null
                    }) { Text("Confirmar") }
                },
                dismissButton = {
                    TextButton(onClick = { showDuplicateDialog = null }) { Text("Cancelar") }
                }
            )
        }

        if (backupToDelete != null) {
            AlertDialog(
                onDismissRequest = { backupToDelete = null },
                title = { Text("Excluir Backup") },
                text = { Text("Deseja realmente excluir \u0027${backupToDelete!!.backupName}\u0027? Esta ação também removerá o arquivo físico.") },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.deleteBackup(backupToDelete!!.id, backupToDelete!!.fileName)
                            backupToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Excluir") }
                },
                dismissButton = {
                    TextButton(onClick = { backupToDelete = null }) { Text("Cancelar") }
                }
            )
        }
    }
}

@Composable
fun BackupItem(
    backup: BackupSummary,
    onClick: () -> Unit,
    onViewCode: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onDuplicate: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = backup.backupName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(backup.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onViewCode, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Code, contentDescription = "Ver Código", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDuplicate, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Duplicar", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onShare, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Share, contentDescription = "Compartilhar", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Excluir", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
