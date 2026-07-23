package my.robots.ui.robot

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.robots.data.model.Backup
import my.robots.data.model.QuickCommand
import my.robots.data.model.Robot
import my.robots.data.remote.KawasakiTerminalManager
import my.robots.data.remote.RobotStatusResponse
import my.robots.data.repository.RobotRepository
import my.robots.utils.FileUtil

data class RobotProgram(
    val name: String,
    val size: String = "0 KB",
    val group: String = "Geral"
)

data class RobotVariable(
    val name: String,
    val value: String = "0.000",
    val type: String = "TRANS"
)

data class RobotDataBankEntry(
    val num: String,
    val comment: String = "",
    val frate: String = "0",
    val pattern: String = "0",
    val atomize: String = "0",
    val hvolt: String = "0",
    val speed: String = "0",
    val jspeed: String = "0"
)

class RobotDashboardViewModel(
    private val repository: RobotRepository,
    private val robotId: Int,
    private val terminalManager: KawasakiTerminalManager,
    private val initialBackupId: Int? = null
) : ViewModel() {

    private val _robot = MutableStateFlow<Robot?>(null)
    val robot: StateFlow<Robot?> = _robot.asStateFlow()

    private val _status = MutableStateFlow<RobotStatusResponse?>(null)
    val status: StateFlow<RobotStatusResponse?> = _status.asStateFlow()

    private val _latestBackup = MutableStateFlow<Backup?>(null)
    val latestBackup: StateFlow<Backup?> = _latestBackup.asStateFlow()

    private val _programs = MutableStateFlow<List<RobotProgram>>(emptyList())
    val programs: StateFlow<List<RobotProgram>> = _programs.asStateFlow()

    private val _variables = MutableStateFlow<List<RobotVariable>>(emptyList())
    val variables: StateFlow<List<RobotVariable>> = _variables.asStateFlow()

    private val _dataBankEntries = MutableStateFlow<List<RobotDataBankEntry>>(emptyList())
    val dataBankEntries: StateFlow<List<RobotDataBankEntry>> = _dataBankEntries.asStateFlow()

    private val _dataBankContent = MutableStateFlow<String>("")
    val dataBankContent: StateFlow<String> = _dataBankContent.asStateFlow()

    val isConnected: StateFlow<Boolean> = terminalManager.getConnectionStatus(robotId)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val logs: StateFlow<List<String>> = repository.getRobotLogs(robotId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val quickCommands: StateFlow<List<QuickCommand>> = repository.getQuickCommands(robotId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val terminalOutput: StateFlow<List<String>> = terminalManager.getHistory(robotId)

    val allRobots: StateFlow<List<Robot>> = repository.allRobots
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadRobot()
        refreshStatus()
        observeBackup()
        checkPendingTransfers()
    }

    private fun loadRobot() {
        viewModelScope.launch {
            _robot.value = repository.getRobotById(robotId)
        }
    }

    private fun checkPendingTransfers() {
        viewModelScope.launch {
            val pending = terminalManager.getPendingTransfer(robotId)
            if (pending != null) {
                _isLoading.value = true
                
                // Garantir conexão
                if (!isConnected.value) {
                    val r = repository.getRobotById(robotId)
                    if (r != null) {
                        terminalManager.connect(r)
                        var timeout = 0
                        while (!terminalManager.getConnectionStatus(robotId).value && timeout < 20) {
                            delay(500)
                            timeout++
                        }
                    }
                }
                
                if (isConnected.value) {
                    // Salvar o arquivo no sistema de arquivos do robô de destino
                    repository.saveFileToRobotFolder(robotId, pending.fileName, pending.content)
                    
                    delay(1500)
                    terminalManager.sendCommand(robotId, "LOAD ${pending.fileName}")
                    terminalManager.clearPendingTransfer(robotId)
                }
                _isLoading.value = false
            }
        }
    }

    fun connectToRobot() {
        val r = _robot.value ?: return
        terminalManager.connect(r)
    }

    fun disconnectFromRobot() {
        terminalManager.disconnect(robotId, clearHistory = true)
    }

    fun sendChar(char: String) {
        terminalManager.sendChar(robotId, char)
    }

    fun sendCommand(command: String) {
        terminalManager.sendCommand(robotId, command)
    }

    fun clearTerminal() {
        terminalManager.clearLog(robotId)
    }

    fun sendProgramToRobot(program: RobotProgram, targetRobot: Robot) {
        viewModelScope.launch {
            _isLoading.value = true
            val currentBackup = _latestBackup.value ?: return@launch
            
            val programContent = withContext(Dispatchers.Default) {
                val lines = currentBackup.content.lines()
                val extracted = StringBuilder()
                var isReading = false
                for (line in lines) {
                    if (line.trim().startsWith(".PROGRAM ${program.name}", ignoreCase = true)) {
                        isReading = true
                    }
                    if (isReading) {
                        extracted.append(line).append("\n")
                        if (line.trim().equals(".END", ignoreCase = true)) break
                    }
                }
                extracted.toString()
            }
            
            if (programContent.isNotBlank()) {
                val sanitizedName = FileUtil.sanitizeFileName(program.name).replace(".as", "")
                val fileName = "transfer_$sanitizedName.as"
                
                if (targetRobot.id == robotId) {
                    repository.saveFileToRobotFolder(robotId, fileName, programContent)
                    delay(500)
                    terminalManager.sendCommand(robotId, "LOAD $fileName")
                } else {
                    terminalManager.setPendingTransfer(targetRobot.id, fileName, programContent)
                }
            }
            _isLoading.value = false
        }
    }

    fun sendVariableToRobot(variable: RobotVariable, targetRobot: Robot) {
        viewModelScope.launch {
            _isLoading.value = true
            val currentBackup = _latestBackup.value ?: return@launch
            
            val varContent = withContext(Dispatchers.Default) {
                val lines = currentBackup.content.lines()
                val extracted = StringBuilder()
                var isReading = false
                var found = false
                
                val sectionHeader = when(variable.type) {
                    "REALS" -> ".REALS"
                    "STRINGS" -> ".STRINGS"
                    "INTEGER" -> ".INTEGER"
                    else -> ".TRANS"
                }

                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.equals(sectionHeader, ignoreCase = true)) {
                        isReading = true
                        extracted.append(line).append("\n")
                        continue
                    }
                    
                    if (isReading) {
                        if (trimmed.startsWith("${variable.name} =", ignoreCase = true) || 
                            trimmed.startsWith("${variable.name}=", ignoreCase = true) ||
                            trimmed.startsWith("${variable.name} ", ignoreCase = true)) {
                            extracted.append(line).append("\n")
                            found = true
                        }
                        
                        if (trimmed.equals(".END", ignoreCase = true)) {
                            extracted.append(line).append("\n")
                            isReading = false
                            if (found) break
                        }
                    }
                }
                if (found) extracted.toString() else ""
            }
            
            if (varContent.isNotBlank()) {
                val sanitizedName = FileUtil.sanitizeFileName(variable.name).replace(".as", "")
                val fileName = "var_$sanitizedName.as"
                
                if (targetRobot.id == robotId) {
                    repository.saveFileToRobotFolder(robotId, fileName, varContent)
                    delay(500)
                    terminalManager.sendCommand(robotId, "LOAD $fileName")
                } else {
                    terminalManager.setPendingTransfer(targetRobot.id, fileName, varContent)
                }
            }
            _isLoading.value = false
        }
    }

    fun sendDataBankToRobot(entry: RobotDataBankEntry, targetRobot: Robot) {
        sendDataBankEntriesToRobot(listOf(entry), targetRobot)
    }

    fun sendDataBankEntriesToRobot(entries: List<RobotDataBankEntry>, targetRobot: Robot) {
        viewModelScope.launch {
            _isLoading.value = true
            val stringBuilder = StringBuilder(".sprdb\n")
            entries.forEach { entry ->
                stringBuilder.append("  DB${entry.num} ${entry.frate} ${entry.pattern} ${entry.atomize} ${entry.hvolt} ${entry.speed} ${entry.jspeed} \"${entry.comment}\"\n")
            }
            stringBuilder.append(".END")
            val fullContent = stringBuilder.toString()
            
            val fileName = if (entries.size == 1) "db_${entries[0].num}.as" else "db_batch_${System.currentTimeMillis()}.as"
            
            if (targetRobot.id == robotId) {
                repository.saveFileToRobotFolder(robotId, fileName, fullContent)
                delay(500)
                terminalManager.sendCommand(robotId, "LOAD $fileName")
            } else {
                terminalManager.setPendingTransfer(targetRobot.id, fileName, fullContent)
            }
            _isLoading.value = false
        }
    }

    private fun observeBackup() {
        viewModelScope.launch {
            repository.getBackupsForRobotFull(robotId).collect { backups ->
                val target = if (initialBackupId != null && initialBackupId != -1) {
                    backups.find { it.id == initialBackupId }
                } else {
                    backups.sortedByDescending { it.timestamp }.firstOrNull()
                }

                if (target != null) {
                    val current = _latestBackup.value
                    if (current == null || current.id != target.id || current.timestamp != target.timestamp || current.content != target.content) {
                        
                        if (initialBackupId != null && initialBackupId != -1) {
                            _status.value = RobotStatusResponse(
                                status = "Modo Backup",
                                availableMemory = target.memoryUsage,
                                programsCount = target.programsCount,
                                variablesCount = target.variablesCount,
                                framesCount = 0,
                                message = "Analisando: ${target.backupName}"
                            )
                        }

                        updateStateFromContent(target.content)
                        _latestBackup.value = target
                    }
                }
            }
        }
    }

    private suspend fun updateStateFromContent(content: String) {
        withContext(Dispatchers.Default) {
            _programs.value = parsePrograms(content)
            _variables.value = parseVariables(content)
            val dbContent = parseDataBankRaw(content)
            _dataBankContent.value = dbContent
            _dataBankEntries.value = parseDataBankEntries(dbContent)
        }
    }

    private fun parsePrograms(content: String): List<RobotProgram> {
        val allLines = content.lines()
        val programToGroupMap = mutableMapOf<String, String>()
        val groupIndexToName = mutableMapOf<String, String>()
        val pendingMappings = mutableListOf<Pair<String, String>>()
        
        // Passo 1: Extrair grupos e mapeamentos de todo o conteúdo do arquivo
        for (line in allLines) {
            val trimmed = line.trim()
            if (trimmed.startsWith(";")) {
                val commentLine = trimmed.substring(1).trim()
                
                // Ignorar linhas de comentário que começam com ponto (variáveis ou metadados de sistema)
                if (commentLine.startsWith(".")) continue

                if (commentLine.startsWith("Group:", ignoreCase = true)) {
                    // Formato esperado: Group:PaintPrograms:1
                    val parts = commentLine.split(":")
                    if (parts.size >= 3) {
                        val groupName = parts[1].trim()
                        val groupIndex = parts[2].trim()
                        if (groupName.isNotEmpty() && groupIndex.isNotEmpty() && groupIndex.all { it.isDigit() }) {
                            groupIndexToName[groupIndex] = groupName
                        }
                    }
                } else if (commentLine.contains(":")) {
                    // Formato esperado: 1:pg77:F
                    val parts = commentLine.split(":")
                    if (parts.size >= 2) {
                        val groupIndex = parts[0].trim()
                        val progName = parts[1].trim()

                        // Ignorar se o nome do programa começar com ponto (variável)
                        if (progName.startsWith(".")) continue

                        // Considerar apenas se o índice for numérico (ex: "1") e não vazio
                        if (groupIndex.isNotEmpty() && groupIndex.all { it.isDigit() } && progName.isNotEmpty()) {
                            pendingMappings.add(groupIndex to progName)
                        }
                    }
                }
            }
        }

        // Resolver mapeamentos (agora temos todos os grupos coletados)
        for ((index, name) in pendingMappings) {
            val groupName = groupIndexToName[index]
            if (groupName != null) {
                programToGroupMap[name.lowercase()] = groupName
            }
        }

        // Passo 2: Parsear todos os programas reais (.PROGRAM)
        val programsList = mutableListOf<RobotProgram>()
        var currentProgramName: String? = null
        var currentProgramContent = StringBuilder()
        var isReadingProgram = false

        for (line in allLines) {
            val trimmedLine = line.trim()
            if (trimmedLine.startsWith(".PROGRAM", ignoreCase = true)) {
                currentProgramName = trimmedLine.substringAfter(".PROGRAM").substringBefore("(").trim()
                if (currentProgramName.isEmpty()) currentProgramName = "Untitled"
                currentProgramContent = StringBuilder()
                currentProgramContent.append(line).append("\n")
                isReadingProgram = true
            } else if (isReadingProgram) {
                currentProgramContent.append(line).append("\n")
                if (trimmedLine.equals(".END", ignoreCase = true)) {
                    val name = currentProgramName ?: "Untitled"
                    
                    // Filtramos o programa de comentários e nomes internos (que começam com ponto)
                    if (!name.contains("comment___", ignoreCase = true) && !name.startsWith(".")) {
                        val group = programToGroupMap[name.lowercase()] ?: "Geral"
                        val sizeInKb = (currentProgramContent.length / 1024).coerceAtLeast(1)
                        programsList.add(RobotProgram(
                            name = name,
                            size = "$sizeInKb KB",
                            group = group
                        ))
                    }
                    isReadingProgram = false
                    currentProgramName = null
                }
            }
        }
        return programsList
    }

    private fun parseVariables(content: String): List<RobotVariable> {
        val variables = mutableListOf<RobotVariable>()
        val lines = content.lines()
        var currentSection = ""

        for (line in lines) {
            val trimmedLine = line.trim()
            
            if (trimmedLine.startsWith(".") && !trimmedLine.equals(".END", ignoreCase = true)) {
                val upper = trimmedLine.uppercase()
                if (upper in listOf(".TRANS", ".REALS", ".STRINGS", ".INTEGER", ".POS")) {
                    currentSection = upper
                } else {
                    currentSection = ""
                }
                continue
            }
            
            if (currentSection.isNotEmpty()) {
                if (trimmedLine.equals(".END", ignoreCase = true)) {
                    currentSection = ""
                    continue
                }
                
                if (trimmedLine.isEmpty() || trimmedLine.startsWith(";")) continue

                val type = currentSection.removePrefix(".")
                
                if (trimmedLine.contains("=")) {
                    val name = trimmedLine.substringBefore("=").trim()
                    val valuesPart = trimmedLine.substringAfter("=").trim()
                    variables.add(RobotVariable(name = name, value = valuesPart, type = type))
                } else {
                    val parts = trimmedLine.split(Regex("\\s+")).filter { it.isNotBlank() }
                    if (parts.size >= 2) {
                        val name = parts[0]
                        val valuesPart = parts.drop(1).joinToString(" ")
                        variables.add(RobotVariable(name = name, value = valuesPart, type = "FRAME"))
                    }
                }
            }
        }
        return variables
    }

    private fun parseDataBankRaw(content: String): String {
        val lines = content.lines()
        val extracted = StringBuilder()
        var isReading = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.equals(".sprdb", ignoreCase = true)) {
                isReading = true
            }
            if (isReading) {
                extracted.append(line).append("\n")
                if (trimmed.equals(".END", ignoreCase = true)) break
            }
        }
        return extracted.toString()
    }

    private fun parseDataBankEntries(rawContent: String): List<RobotDataBankEntry> {
        val entries = mutableListOf<RobotDataBankEntry>()
        val lines = rawContent.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith(".") || trimmed.startsWith(";") || trimmed.isEmpty()) continue
            
            // Formato esperado: DB1 28 10 50 -1 -1 -1 "comment"
            // Extrair comentário primeiro (tudo entre as primeiras e últimas aspas)
            val firstQuote = trimmed.indexOf('"')
            val lastQuote = trimmed.lastIndexOf('"')
            val comment = if (firstQuote != -1 && lastQuote > firstQuote) {
                trimmed.substring(firstQuote + 1, lastQuote).trim()
            } else ""
            
            // Remover a parte do comentário para dar split seguro nos valores numéricos
            val contentWithoutComment = if (firstQuote != -1) trimmed.substring(0, firstQuote) else trimmed
            val parts = contentWithoutComment.split(Regex("\\s+")).filter { it.isNotBlank() }
            
            if (parts.isNotEmpty()) {
                entries.add(RobotDataBankEntry(
                    num = parts.getOrNull(0)?.removePrefix("DB") ?: "",
                    comment = comment,
                    frate = parts.getOrNull(1) ?: "0",
                    pattern = parts.getOrNull(2) ?: "0",
                    atomize = parts.getOrNull(3) ?: "0",
                    hvolt = parts.getOrNull(4) ?: "0",
                    speed = parts.getOrNull(5) ?: "0",
                    jspeed = parts.getOrNull(6) ?: "0"
                ))
            }
        }
        return entries
    }

    fun refreshStatus() {
        if (initialBackupId == null || initialBackupId == -1) {
            viewModelScope.launch {
                try {
                    _status.value = repository.getRobotStatus(robotId)
                } catch (e: Exception) { }
            }
        }
    }

    fun toggleConnection() {
        if (isConnected.value) disconnectFromRobot() else connectToRobot()
    }

    fun validateProgramName(name: String): String? {
        if (name.isBlank()) return "O nome não pode ser vazio"
        if (name.first().isDigit()) return "O nome não pode começar com um número"
        if (name.contains(" ")) return "O nome não pode conter espaços"
        val specialChars = "!@#$%^&*()+-=[]{}|;':\",/<>?"
        if (name.any { it in specialChars }) return "O nome não pode conter caracteres especiais"
        if (_programs.value.any { it.name.equals(name, ignoreCase = true) }) return "Já existe um programa com este nome"
        return null
    }

    fun validateVariableName(name: String, isNew: Boolean = true): String? {
        if (name.isBlank()) return "O nome não pode ser vazio"
        if (name.first().isDigit()) return "O nome não pode começar com um número"
        if (name.contains(" ")) return "O nome não pode conter espaços"
        if (isNew && _variables.value.any { it.name.equals(name, ignoreCase = true) }) return "Já existe uma variável com este nome"
        return null
    }

    fun validateDataBankNum(num: String): String? {
        if (num.isBlank()) return "O número não pode ser vazio"
        val n = num.toIntOrNull() ?: return "Deve ser um número"
        if (n < 1 || n > 999) return "O número deve estar entre 1 e 999"
        if (_dataBankEntries.value.any { it.num == num }) return "Já existe um registro com este número"
        return null
    }

    fun duplicateProgram(oldProgram: RobotProgram, newName: String) {
        val currentBackup = _latestBackup.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            val newContent = withContext(Dispatchers.Default) {
                val lines = currentBackup.content.lines()
                val programContent = StringBuilder()
                var isReading = false
                for (line in lines) {
                    if (line.trim().startsWith(".PROGRAM ${oldProgram.name}", ignoreCase = true)) isReading = true
                    if (isReading) {
                        programContent.append(line).append("\n")
                        if (line.trim().equals(".END", ignoreCase = true)) break
                    }
                }
                val duplicate = programContent.toString().replaceFirst(
                    ".PROGRAM ${oldProgram.name}", 
                    ".PROGRAM $newName",
                    ignoreCase = true
                )
                currentBackup.content + "\n" + duplicate
            }
            saveBackupContent(newContent)
        }
    }

    fun duplicateVariable(variable: RobotVariable, newName: String) {
        val currentBackup = _latestBackup.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            val newContent = withContext(Dispatchers.Default) {
                val lines = currentBackup.content.lines().toMutableList()
                val separator = if (variable.type == "FRAME") " " else " = "
                val newVarLine = "$newName$separator${variable.value}"
                
                var insertIndex = -1
                for (i in lines.indices) {
                    if (lines[i].trim().equals(".TRANS", ignoreCase = true)) {
                        insertIndex = i + 1
                    }
                    if (insertIndex != -1 && lines[i].trim().equals(".END", ignoreCase = true)) {
                        insertIndex = i
                        break
                    }
                }
                
                if (insertIndex != -1) {
                    lines.add(insertIndex, newVarLine)
                } else {
                    lines.add(".TRANS")
                    lines.add(newVarLine)
                    lines.add(".END")
                }
                lines.joinToString("\n")
            }
            saveBackupContent(newContent)
        }
    }

    fun duplicateDataBankEntry(entry: RobotDataBankEntry, newNum: String) {
        val currentBackup = _latestBackup.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            val fullContent = withContext(Dispatchers.Default) {
                val lines = currentBackup.content.lines().toMutableList()
                val newEntryLine = "  DB$newNum ${entry.frate} ${entry.pattern} ${entry.atomize} ${entry.hvolt} ${entry.speed} ${entry.jspeed} \"${entry.comment}\""
                
                var insertIndex = -1
                for (i in lines.indices) {
                    if (lines[i].trim().equals(".sprdb", ignoreCase = true)) {
                        insertIndex = i + 1
                    }
                    if (insertIndex != -1 && lines[i].trim().equals(".END", ignoreCase = true)) {
                        insertIndex = i
                        break
                    }
                }
                
                if (insertIndex != -1) {
                    lines.add(insertIndex, newEntryLine)
                }
                lines.joinToString("\n")
            }
            saveBackupContent(fullContent)
        }
    }

    fun updateVariable(oldName: String, updated: RobotVariable) {
        val currentBackup = _latestBackup.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            val newContent = withContext(Dispatchers.Default) {
                val lines = currentBackup.content.lines()
                val result = StringBuilder()
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("$oldName =") || 
                        trimmed.startsWith("$oldName=") ||
                        trimmed.startsWith("$oldName ")) {
                        val separator = if (updated.type == "FRAME") " " else " = "
                        result.append("${updated.name}$separator${updated.value}").append("\n")
                    } else {
                        result.append(line).append("\n")
                    }
                }
                result.toString()
            }
            saveBackupContent(newContent)
        }
    }

    fun deleteProgram(program: RobotProgram) {
        val currentBackup = _latestBackup.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            
            // Se estiver conectado, deletar no robô também
            if (isConnected.value) {
                terminalManager.deleteProgram(robotId, program.name)
            }

            val newContent = withContext(Dispatchers.Default) {
                val lines = currentBackup.content.lines()
                val result = StringBuilder()
                var isReading = false
                for (line in lines) {
                    if (line.trim().startsWith(".PROGRAM ${program.name}", ignoreCase = true)) {
                        isReading = true
                        continue
                    }
                    if (isReading) {
                        if (line.trim().equals(".END", ignoreCase = true)) isReading = false
                        continue
                    }
                    result.append(line).append("\n")
                }
                result.toString()
            }
            saveBackupContent(newContent)
        }
    }

    fun deleteVariable(variable: RobotVariable) {
        val currentBackup = _latestBackup.value ?: return
        viewModelScope.launch {
            _isLoading.value = true

            // Se estiver conectado, deletar no robô também
            if (isConnected.value) {
                terminalManager.deleteVariable(robotId, variable.name, variable.type)
            }

            val newContent = withContext(Dispatchers.Default) {
                val lines = currentBackup.content.lines()
                val result = StringBuilder()
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("${variable.name} =") || 
                        trimmed.startsWith("${variable.name}=") ||
                        trimmed.startsWith("${variable.name} ")) {
                        continue
                    }
                    result.append(line).append("\n")
                }
                result.toString()
            }
            saveBackupContent(newContent)
        }
    }

    fun deleteDataBankEntry(entry: RobotDataBankEntry) {
        val currentBackup = _latestBackup.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            val fullContent = withContext(Dispatchers.Default) {
                val lines = currentBackup.content.lines()
                val result = StringBuilder()
                var inSprdb = false
                
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.equals(".sprdb", ignoreCase = true)) {
                        inSprdb = true
                        result.append(line).append("\n")
                        continue
                    }
                    
                    if (inSprdb) {
                        val parts = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
                        val numPart = parts.getOrNull(0)?.removePrefix("DB")
                        
                        if (trimmed.equals(".END", ignoreCase = true)) {
                            inSprdb = false
                        } else if (numPart == entry.num) {
                            continue
                        }
                    }
                    result.append(line).append("\n")
                }
                result.toString().trim()
            }
            saveBackupContent(fullContent)
        }
    }

    fun saveBackupContent(content: String) {
        val currentBackup = _latestBackup.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            val updated = currentBackup.copy(
                content = content,
                timestamp = System.currentTimeMillis()
            )
            repository.insertBackup(updated)
            // A observação em observeBackup cuidará de atualizar o estado UI e _latestBackup
            _isLoading.value = false
        }
    }

    fun updateDataBank(newContent: String) {
        val currentBackup = _latestBackup.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            val fullContent = withContext(Dispatchers.Default) {
                val lines = currentBackup.content.lines()
                val result = StringBuilder()
                var isSkipping = false
                var replaced = false
                
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.equals(".sprdb", ignoreCase = true)) {
                        isSkipping = true
                        if (!replaced) {
                            result.append(newContent).append("\n")
                            replaced = true
                        }
                    }
                    
                    if (!isSkipping) {
                        result.append(line).append("\n")
                    }
                    
                    if (isSkipping && trimmed.equals(".END", ignoreCase = true)) {
                        isSkipping = false
                    }
                }
                
                if (!replaced) {
                    result.append("\n").append(newContent).append("\n")
                }
                
                result.toString().trim()
            }
            saveBackupContent(fullContent)
        }
    }

    fun updateDataBankEntry(updatedEntry: RobotDataBankEntry) {
        val currentBackup = _latestBackup.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            val fullContent = withContext(Dispatchers.Default) {
                val lines = currentBackup.content.lines()
                val result = StringBuilder()
                var inSprdb = false
                var found = false
                
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.equals(".sprdb", ignoreCase = true)) {
                        inSprdb = true
                        result.append(line).append("\n")
                        continue
                    }
                    
                    if (inSprdb) {
                        // Tentar extrair o número da linha atual para comparar
                        val parts = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
                        val numPart = parts.getOrNull(0)?.removePrefix("DB")
                        
                        if (trimmed.equals(".END", ignoreCase = true)) {
                            inSprdb = false
                        } else if (!found && numPart == updatedEntry.num) {
                            // Salva usando o formato de espaços conforme a imagem
                            val newLine = "  DB${updatedEntry.num} ${updatedEntry.frate} ${updatedEntry.pattern} ${updatedEntry.atomize} ${updatedEntry.hvolt} ${updatedEntry.speed} ${updatedEntry.jspeed} \"${updatedEntry.comment}\""
                            result.append(newLine).append("\n")
                            found = true
                            continue
                        }
                    }
                    result.append(line).append("\n")
                }
                result.toString().trim()
            }
            saveBackupContent(fullContent)
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (!isConnected.value) {
            terminalManager.disconnect(robotId, clearHistory = true)
        }
    }
}
