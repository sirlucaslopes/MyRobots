package my.robots.ui.robot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import my.robots.data.model.Manufacturer
import my.robots.data.model.Robot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RobotDialog(
    robot: Robot? = null,
    existingProjects: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (name: String, ip: String, port: Int, project: String, manufacturer: Manufacturer, autoLogin: Boolean, loginUser: String, loginPassword: String) -> Unit
) {
    var name by remember { mutableStateOf(robot?.name ?: "") }
    var ip by remember { mutableStateOf(robot?.ip ?: "") }
    var port by remember { mutableStateOf(robot?.port?.toString() ?: "23") }
    var project by remember { mutableStateOf(robot?.project ?: "") }
    var manufacturer by remember { mutableStateOf(robot?.manufacturer ?: Manufacturer.KAWASAKI) }
    var autoLogin by remember { mutableStateOf(robot?.autoLogin ?: false) }
    var loginUser by remember { mutableStateOf(robot?.loginUser ?: "as") }
    var loginPassword by remember { mutableStateOf(robot?.loginPassword ?: "") }
    
    var expandedManufacturer by remember { mutableStateOf(false) }
    var expandedProject by remember { mutableStateOf(false) }
    
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    // Filtra projetos únicos e remove vazios para a lista de sugestões
    val projectSuggestions = remember(existingProjects) {
        existingProjects.filter { it.isNotBlank() }.distinct().sorted()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .imePadding()
                    .padding(horizontal = 24.dp, vertical = 32.dp)
                    .fillMaxWidth(0.95f)
                    .wrapContentHeight(),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        text = if (robot == null) "Cadastrar Robô" else "Editar Robô",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Seção de Identificação
                    Text("Identificação", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))

                    ExposedDropdownMenuBox(
                        expanded = expandedManufacturer,
                        onExpandedChange = { expandedManufacturer = !expandedManufacturer },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = manufacturer.displayName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Fabricante") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedManufacturer) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = expandedManufacturer,
                            onDismissRequest = { expandedManufacturer = false }
                        ) {
                            Manufacturer.entries.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m.displayName) },
                                    onClick = {
                                        manufacturer = m
                                        expandedManufacturer = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Campo de Projeto com Sugestões (Dropdown Editável)
                    ExposedDropdownMenuBox(
                        expanded = expandedProject && projectSuggestions.isNotEmpty(),
                        onExpandedChange = { expandedProject = it },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = project,
                            onValueChange = { 
                                project = it
                                expandedProject = true
                            },
                            label = { Text("Projeto") },
                            placeholder = { Text("Ex: Célula 01") },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            singleLine = true,
                            trailingIcon = {
                                if (projectSuggestions.isNotEmpty()) {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedProject)
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                        )
                        
                        if (projectSuggestions.isNotEmpty()) {
                            // Filtra as sugestões com base no que o usuário está digitando
                            val filteredSuggestions = projectSuggestions.filter { 
                                it.contains(project, ignoreCase = true) 
                            }
                            
                            if (filteredSuggestions.isNotEmpty()) {
                                ExposedDropdownMenu(
                                    expanded = expandedProject,
                                    onDismissRequest = { expandedProject = false }
                                ) {
                                    filteredSuggestions.forEach { suggestion ->
                                        DropdownMenuItem(
                                            text = { Text(suggestion) },
                                            onClick = {
                                                project = suggestion
                                                expandedProject = false
                                                focusManager.moveFocus(FocusDirection.Down)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = name,
                        onValueChange = { input -> 
                            // Permite apenas letras, números e sublinhado (_)
                            name = input.filter { it.isLetterOrDigit() || it == '_' }
                        },
                        label = { Text("Nome do Robô") },
                        placeholder = { Text("Ex: Kawasaki_R1") },
                        supportingText = { Text("Apenas letras, números e _") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Seção de Conexão
                    Text("Conexão de Rede", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = ip,
                            onValueChange = { ip = it },
                            label = { Text("IP") },
                            placeholder = { Text("192.168.1.10") },
                            modifier = Modifier.weight(2f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                        )
                        
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it },
                            label = { Text("Porta") },
                            placeholder = { Text("23") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Seção de Autenticação (Login Automático)
                    Text("Acesso ao Terminal", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { autoLogin = !autoLogin }
                            ) {
                                Checkbox(checked = autoLogin, onCheckedChange = { autoLogin = it })
                                Text("Ativar Login Automático", style = MaterialTheme.typography.bodyMedium)
                            }

                            if (autoLogin) {
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = loginUser,
                                    onValueChange = { loginUser = it },
                                    label = { Text("Usuário (Login)") },
                                    leadingIcon = { Icon(Icons.Default.Person, null) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = loginPassword,
                                    onValueChange = { loginPassword = it },
                                    label = { Text("Senha (Password)") },
                                    leadingIcon = { Icon(Icons.Default.Lock, null) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = {
                                        if (name.isNotBlank() && ip.isNotBlank()) {
                                            val portInt = port.toIntOrNull() ?: 23
                                            onConfirm(name, ip, portInt, project.ifBlank { "Padrão" }, manufacturer, autoLogin, loginUser, loginPassword)
                                        }
                                    })
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancelar")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val portInt = port.toIntOrNull() ?: 23
                                onConfirm(name, ip, portInt, project.ifBlank { "Padrão" }, manufacturer, autoLogin, loginUser, loginPassword)
                            },
                            enabled = name.isNotBlank() && ip.isNotBlank()
                        ) {
                            Text("Confirmar")
                        }
                    }
                }
            }
        }
    }
}
