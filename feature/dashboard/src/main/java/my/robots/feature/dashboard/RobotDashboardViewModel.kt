package my.robots.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.robots.core.model.Backup
import my.robots.core.model.QuickCommand
import my.robots.core.model.Robot
import my.robots.core.network.KawasakiTerminalManager
import my.robots.core.data.RobotRepository
import my.robots.core.common.FileUtil
import my.robots.core.common.ascode.AsControllerLogs
import my.robots.core.common.ascode.AsProgramBlocks
import my.robots.core.common.ascode.RobotErrorLogEntry
import my.robots.core.common.ascode.RobotLogEntry

/**
 * Um programa lido do backup (bloco .PROGRAM ... .END).
 * - name: nome do programa.
 * - size: tamanho aproximado, em KB.
 * - group: grupo a que pertence (vem dos comentários do backup; padrão "Geral").
 * - modifiedAt: data/hora da última alteração, lida do próprio cabeçalho do programa
 *   (ex.: "26/09/23 11:42"). Vazio se o cabeçalho não tiver essa informação.
 * - comment: descrição curta do programa, escrita por quem programou (ex.: "Robot States
 *   Control Program"). Vazio se o cabeçalho não tiver comentário.
 * - lineCount: quantidade de linhas do bloco (do .PROGRAM ao .END, incluindo os dois).
 */
data class RobotProgram(
    val name: String,
    val size: String = "0 KB",
    val group: String = "Geral",
    val modifiedAt: String = "",
    val comment: String = "",
    val lineCount: Int = 0
)

/**
 * Uma variável lida do backup.
 * - name: nome da variável.
 * - value: valor como texto (ex.: "0 0 0 0 0 0" para uma posição).
 * - type: TRANS, REALS, STRINGS, INTEGER... ou FRAME (posição escrita sem "=").
 */
data class RobotVariable(
    val name: String,
    val value: String = "0.000",
    val type: String = "TRANS"
)

/**
 * Uma linha do Data Bank (seção .sprdb), como "DB1 28 10 50 -1 -1 -1 "comentário"".
 * Os campos são os parâmetros de pintura: vazão (frate), padrão (pattern), atomização
 * (atomize), alta tensão (hvolt) e velocidades (speed, jspeed), mais um comentário.
 */
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

/**
 * Cérebro do painel do robô.
 *
 * Lê o backup escolhido (ou o mais recente), separa programas, variáveis e Data Bank,
 * e oferece as ações: conectar no terminal, enviar comandos, editar, duplicar, apagar
 * e enviar itens para outro robô.
 *
 * Regra importante: toda edição altera o TEXTO do backup e o salva de novo;
 * a tela então se atualiza sozinha, pois observa o banco (veja observeBackup).
 *
 * - robotId: robô do painel.
 * - initialBackupId: backup a analisar (null ou -1 = o mais recente).
 */
class RobotDashboardViewModel(
    private val repository: RobotRepository,
    private val robotId: Int,
    private val terminalManager: KawasakiTerminalManager,
    private val initialBackupId: Int? = null
) : ViewModel() {

    private val _robot = MutableStateFlow<Robot?>(null)
    /**
     * Dados do robô deste painel (null até carregar).
     */
    val robot: StateFlow<Robot?> = _robot.asStateFlow()

    private val _latestBackup = MutableStateFlow<my.robots.core.model.BackupSummary?>(null)
    /**
     * Resumo do backup que está sendo analisado.
     */
    val latestBackup: StateFlow<my.robots.core.model.BackupSummary?> = _latestBackup.asStateFlow()

    private val _programs = MutableStateFlow<List<RobotProgram>>(emptyList())
    /**
     * Programas encontrados no backup.
     */
    val programs: StateFlow<List<RobotProgram>> = _programs.asStateFlow()

    private val _variables = MutableStateFlow<List<RobotVariable>>(emptyList())
    /**
     * Variáveis encontradas no backup.
     */
    val variables: StateFlow<List<RobotVariable>> = _variables.asStateFlow()

    private val _dataBankEntries = MutableStateFlow<List<RobotDataBankEntry>>(emptyList())
    /**
     * Linhas do Data Bank, já separadas em campos.
     */
    val dataBankEntries: StateFlow<List<RobotDataBankEntry>> = _dataBankEntries.asStateFlow()

    private val _lineCount = MutableStateFlow(0)
    /**
     * Quantidade de linhas do texto do backup.
     */
    val lineCount: StateFlow<Int> = _lineCount.asStateFlow()

    private val _dataBankContent = MutableStateFlow<String>("")
    /**
     * Texto bruto da seção Data Bank.
     */
    val dataBankContent: StateFlow<String> = _dataBankContent.asStateFlow()

    private val _errorLog = MutableStateFlow<List<RobotErrorLogEntry>>(emptyList())
    /**
     * Log de erros do robô (.ERRLOG), já com os campos separados. Vazio se o backup não
     * foi feito com SAVE/FULL.
     */
    val errorLog: StateFlow<List<RobotErrorLogEntry>> = _errorLog.asStateFlow()

    private val _operationLog = MutableStateFlow<List<RobotLogEntry>>(emptyList())
    /**
     * Log de operação do robô (.OPELOG). Vazio se o backup não foi feito com SAVE/FULL.
     */
    val operationLog: StateFlow<List<RobotLogEntry>> = _operationLog.asStateFlow()

    private val _programEditLog = MutableStateFlow<List<RobotLogEntry>>(emptyList())
    /**
     * Log de edição de programas (.PGM_EDT_LOG). Vazio se o backup não foi feito com SAVE/FULL.
     */
    val programEditLog: StateFlow<List<RobotLogEntry>> = _programEditLog.asStateFlow()

    /**
     * true se o terminal deste robô está conectado.
     */
    val isConnected: StateFlow<Boolean> = terminalManager.getConnectionStatus(robotId)

    private val _isLoading = MutableStateFlow(false)
    /**
     * true enquanto uma operação demorada está em andamento (mostra o círculo de carregando).
     */
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * Comandos rápidos deste robô.
     */
    val quickCommands: StateFlow<List<QuickCommand>> = repository.getQuickCommands(robotId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Texto do terminal deste robô.
     */
    val terminalOutput: StateFlow<List<String>> = terminalManager.getHistory(robotId)

    /**
     * Todos os robôs cadastrados (para escolher o destino ao enviar um item).
     */
    val allRobots: StateFlow<List<Robot>> = repository.allRobots
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Ao criar o painel: carrega o robô, observa o backup e confere envios pendentes.
    init {
        loadRobot()
        observeBackup()
        checkPendingTransfers()
    }

    /**
     * Busca no banco os dados do robô deste painel.
     */
    private fun loadRobot() {
        viewModelScope.launch {
            _robot.value = repository.getRobotById(robotId)
        }
    }

    /**
     * Se outro robô deixou um arquivo na fila para ESTE robô, conecta (espera até 10 s),
     * grava o arquivo na pasta e manda o comando LOAD para o robô carregá-lo.
     */
    private fun checkPendingTransfers() {
        viewModelScope.launch {
            val pending = terminalManager.getPendingTransfer(robotId)
            if (pending != null) {
                _isLoading.value = true
                
                // se não estiver conectado, conecta e espera (até 20 x 0,5 s)
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
                    // grava o arquivo na pasta do robô de destino e manda o robô carregar
                    repository.saveFileToRobotFolder(robotId, pending.fileName, pending.content)
                    
                    delay(1500)
                    terminalManager.sendCommand(robotId, "LOAD ${pending.fileName}")
                    terminalManager.clearPendingTransfer(robotId)
                }
                _isLoading.value = false
            }
        }
    }

    /**
     * Uri da pasta dos arquivos (padrão ou escolhida), para o botão "Arquivos" do terminal.
     */
    fun filesFolderUri(): android.net.Uri = repository.filesFolderUri()

    /**
     * Conecta o terminal ao robô.
     */
    fun connectToRobot() {
        val r = _robot.value ?: return
        terminalManager.connect(r)
    }

    /**
     * Desconecta o terminal do robô e limpa o texto dele.
     */
    fun disconnectFromRobot() {
        terminalManager.disconnect(robotId, clearHistory = true)
    }

    /**
     * Envia UMA tecla ao terminal do robô (sem Enter).
     */
    fun sendChar(char: String) {
        terminalManager.sendChar(robotId, char)
    }

    /**
     * Envia um comando ao terminal do robô (com Enter). Texto vazio = só Enter.
     */
    fun sendCommand(command: String) {
        terminalManager.sendCommand(robotId, command)
    }

    /**
     * Limpa o texto do terminal na tela.
     */
    fun clearTerminal() {
        terminalManager.clearLog(robotId)
    }

    /**
     * Extrai do backup atual os blocos .PROGRAM ... .END de vários programas, já juntos
     * num texto só (na ordem em que aparecem no backup). Usado tanto para enviar a outro
     * robô quanto para compartilhar por fora do app.
     */
    suspend fun packProgramsContent(programs: List<RobotProgram>): String {
        if (programs.isEmpty()) return ""
        val summary = _latestBackup.value ?: return ""
        val fullBackup = repository.getBackupById(summary.id) ?: return ""

        return withContext(Dispatchers.Default) {
            AsProgramBlocks.extractMany(fullBackup.content, programs.map { it.name })
        }
    }

    /**
     * Envia um ou mais programas do backup para um robô, empacotados num único arquivo.
     *
     * 1. Extrai do backup o bloco .PROGRAM ... .END de cada programa selecionado (`packProgramsContent`).
     * 2. Cria o arquivo transfer_<nome>.as (um programa só) ou transfer_batch_<hora>.as (vários).
     * 3. Se o destino é ESTE robô: grava o arquivo e manda LOAD na hora.
     *    Se é OUTRO robô: deixa na fila; o painel dele fará o LOAD ao conectar.
     */
    fun sendProgramsToRobot(programs: List<RobotProgram>, targetRobot: Robot) {
        if (programs.isEmpty()) return
        viewModelScope.launch {
            _isLoading.value = true
            val packedContent = packProgramsContent(programs)

            if (packedContent.isNotBlank()) {
                val fileName = if (programs.size == 1) {
                    val sanitizedName = FileUtil.sanitizeFileName(programs[0].name).replace(".as", "")
                    "transfer_$sanitizedName.as"
                } else {
                    "transfer_batch_${System.currentTimeMillis()}.as"
                }

                if (targetRobot.id == robotId) {
                    repository.saveFileToRobotFolder(robotId, fileName, packedContent)
                    delay(500)
                    terminalManager.sendCommand(robotId, "LOAD $fileName")
                } else {
                    terminalManager.setPendingTransfer(targetRobot.id, fileName, packedContent)
                }
            }
            _isLoading.value = false
        }
    }

    /**
     * Envia uma variável do backup para um robô.
     * Monta um arquivo var_<nome>.as só com a linha da variável dentro da sua seção
     * (.TRANS, .REALS...) e segue a mesma regra do envio de programa (agora ou na fila).
     */
    fun sendVariableToRobot(variable: RobotVariable, targetRobot: Robot) {
        viewModelScope.launch {
            _isLoading.value = true
            val summary = _latestBackup.value ?: return@launch
            val fullBackup = repository.getBackupById(summary.id) ?: return@launch
            
            val varContent = withContext(Dispatchers.Default) {
                val lines = fullBackup.content.lines()
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

    /**
     * Envia UMA linha do Data Bank para um robô (atalho de sendDataBankEntriesToRobot).
     */
    fun sendDataBankToRobot(entry: RobotDataBankEntry, targetRobot: Robot) {
        sendDataBankEntriesToRobot(listOf(entry), targetRobot)
    }

    /**
     * Envia várias linhas do Data Bank para um robô.
     * Monta um arquivo .sprdb ... .END com as linhas e segue a mesma regra dos outros envios.
     */
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

    /**
     * Fica observando os backups do robô no banco.
     *
     * Escolhe o backup certo (o pedido, ou o mais recente) e, sempre que ele mudar,
     * carrega o texto completo e refaz as listas de programas, variáveis e Data Bank.
     * Por isso, depois de salvar uma edição a tela se atualiza sozinha.
     */
    private fun observeBackup() {
        viewModelScope.launch {
            repository.getBackupsSummary(robotId).collect { summaries ->
                val targetSummary = if (initialBackupId != null && initialBackupId != -1) {
                    summaries.find { it.id == initialBackupId }
                } else {
                    summaries.sortedByDescending { it.timestamp }.firstOrNull()
                }

                if (targetSummary != null) {
                    val current = _latestBackup.value
                    if (current == null || current.id != targetSummary.id || current.timestamp != targetSummary.timestamp) {
                        
                        _isLoading.value = true
                        val fullBackup = repository.getBackupById(targetSummary.id)
                        
                        if (fullBackup != null) {
                            updateStateFromContent(fullBackup.content)
                            _latestBackup.value = my.robots.core.model.BackupSummary(
                                id = fullBackup.id,
                                robotId = fullBackup.robotId,
                                backupName = fullBackup.backupName,
                                fileName = fullBackup.fileName,
                                programsCount = fullBackup.programsCount,
                                variablesCount = fullBackup.variablesCount,
                                framesCount = fullBackup.framesCount,
                                memoryUsage = fullBackup.memoryUsage,
                                timestamp = fullBackup.timestamp
                            )
                        }
                        _isLoading.value = false
                    }
                }
            }
        }
    }

    /**
     * Lê o texto do backup UMA vez e monta as listas da tela (em segundo plano).
     *
     * O backup pode ter, além do idioma AS, seções que o controlador anexa e que o app
     * não entende (ex.: despejos de diagnóstico do sistema, sem ".END" e com milhares de
     * linhas — ver FileUtil.sanitizeAsContent). Para não atrapalhar a leitura, os passos
     * abaixo trabalham em cima de uma VISÃO sem essas seções — o texto original do backup
     * (`content`, o que fica salvo) não é tocado; só `lineCount` mostra o total de linhas
     * do arquivo de verdade, sem esse corte.
     *
     * Passo 1 - grupos: comentários como ";Group:Nome:1" e ";1:programa" ligam cada programa a um grupo.
     * Passo 2 - programas: cada bloco .PROGRAM ... .END vira um item (ignora os "comment___").
     * Passo 3 - variáveis: linhas dentro de .TRANS, .REALS, .STRINGS, .INTEGER e .POS.
     *           Linha com "=" vira variável comum; sem "=", vira posição (FRAME).
     * Também separa a seção .sprdb (Data Bank) e conta as linhas.
     */
    private suspend fun updateStateFromContent(content: String) {
        withContext(Dispatchers.Default) {
            val cleanContent = FileUtil.sanitizeAsContent(content)

            val groupIndexToName = mutableMapOf<String, String>()
            val pendingMappings = mutableListOf<Pair<String, String>>()

            val programsList = mutableListOf<RobotProgram>()
            val variablesList = mutableListOf<RobotVariable>()
            val dataBankLines = StringBuilder()

            var currentProgramName: String? = null
            var currentProgramContent = StringBuilder()
            var currentProgramModifiedAt = ""
            var currentProgramComment = ""
            var currentProgramLineCount = 0
            var isReadingProgram = false
            var currentSection = ""
            var isReadingDataBank = false

            cleanContent.lineSequence().forEach { line ->
                val trimmed = line.trim()

                // --- Passo 1: descobrir os grupos (comentários do backup) ---
                if (trimmed.startsWith(";")) {
                    val commentLine = trimmed.substring(1).trim()
                    if (!commentLine.startsWith(".")) {
                        if (commentLine.startsWith("Group:", ignoreCase = true)) {
                            val parts = commentLine.split(":")
                            if (parts.size >= 3) {
                                val groupName = parts[1].trim()
                                val groupIndex = parts[2].trim()
                                if (groupName.isNotEmpty() && groupIndex.all { it.isDigit() }) {
                                    groupIndexToName[groupIndex] = groupName
                                }
                            }
                        } else if (commentLine.contains(":")) {
                            val parts = commentLine.split(":")
                            if (parts.size >= 2) {
                                val groupIndex = parts[0].trim()
                                val progName = parts[1].trim()
                                if (groupIndex.all { it.isDigit() } && progName.isNotEmpty() && !progName.startsWith(".")) {
                                    pendingMappings.add(groupIndex to progName)
                                }
                            }
                        }
                    }
                }

                // --- Passo 2: separar os programas (.PROGRAM ... .END) ---
                if (trimmed.startsWith(".PROGRAM", ignoreCase = true)) {
                    currentProgramName = trimmed.substringAfter(".PROGRAM").substringBefore("(").trim()
                    if (currentProgramName.isEmpty()) currentProgramName = "Untitled"
                    currentProgramContent = StringBuilder().append(line).append("\n")
                    currentProgramLineCount = 1
                    isReadingProgram = true

                    // lê data/hora e comentário do próprio cabeçalho, ex.: "@26/09/23 11:42#0;Descrição"
                    val header = AsProgramBlocks.parseHeader(trimmed)
                    currentProgramModifiedAt = header?.modifiedAt.orEmpty()
                    currentProgramComment = header?.comment.orEmpty()
                } else if (isReadingProgram) {
                    currentProgramContent.append(line).append("\n")
                    currentProgramLineCount++
                    if (trimmed.equals(".END", ignoreCase = true)) {
                        val name = currentProgramName ?: "Untitled"
                        if (!name.contains("comment___", ignoreCase = true) && !name.startsWith(".")) {
                            programsList.add(
                                RobotProgram(
                                    name = name,
                                    size = "${(currentProgramContent.length / 1024).coerceAtLeast(1)} KB",
                                    group = "Geral",
                                    modifiedAt = currentProgramModifiedAt,
                                    comment = currentProgramComment,
                                    lineCount = currentProgramLineCount
                                )
                            )
                        }
                        isReadingProgram = false
                        currentProgramName = null
                    }
                }

                // --- Passo 3: separar variáveis e Data Bank ---
                if (trimmed.startsWith(".") && !trimmed.equals(".END", ignoreCase = true) && !isReadingProgram) {
                    val upper = trimmed.uppercase()
                    if (upper in listOf(".TRANS", ".REALS", ".STRINGS", ".INTEGER", ".POS")) {
                        currentSection = upper
                    } else if (upper == ".SPRDB") {
                        isReadingDataBank = true
                        dataBankLines.append(line).append("\n")
                    } else {
                        currentSection = ""
                    }
                } else if (isReadingDataBank) {
                    dataBankLines.append(line).append("\n")
                    if (trimmed.equals(".END", ignoreCase = true)) isReadingDataBank = false
                } else if (currentSection.isNotEmpty()) {
                    if (trimmed.equals(".END", ignoreCase = true)) {
                        currentSection = ""
                    } else if (trimmed.isNotEmpty() && !trimmed.startsWith(";")) {
                        val type = currentSection.removePrefix(".")
                        if (trimmed.contains("=")) {
                            val name = trimmed.substringBefore("=").trim()
                            val valuesPart = trimmed.substringAfter("=").trim()
                            variablesList.add(RobotVariable(name = name, value = valuesPart, type = type))
                        } else {
                            val parts = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
                            if (parts.size >= 2) {
                                variablesList.add(RobotVariable(name = parts[0], value = parts.drop(1).joinToString(" "), type = "FRAME"))
                            }
                        }
                    }
                }
            }

            // liga cada programa ao seu grupo (se não achar, fica em "Geral")
            val resolvedGroupIndexToName = groupIndexToName
            val programToGroup = mutableMapOf<String, String>()
            pendingMappings.forEach { (index, name) ->
                resolvedGroupIndexToName[index]?.let { groupName ->
                    programToGroup[name.lowercase()] = groupName
                }
            }
            
            val finalPrograms = programsList.map { 
                it.copy(group = programToGroup[it.name.lowercase()] ?: "Geral")
            }

            _programs.value = finalPrograms
            _variables.value = variablesList
            val dbRaw = dataBankLines.toString()
            _dataBankContent.value = dbRaw
            _dataBankEntries.value = parseDataBankEntries(dbRaw)
            _lineCount.value = content.lineSequence().count()

            // Logs do controlador (só existem em backup SAVE/FULL) — cada seção é lida
            // separado, com acesso direto às linhas (não dá para reaproveitar o forEach
            // de cima porque essas seções não têm ".END" próprio; usa a visão limpa para
            // não confundir o fim da seção com uma seção estranha que o robô tenha anexado).
            val allLines = cleanContent.lines()
            _errorLog.value = AsControllerLogs.parseErrorLog(allLines)
            _operationLog.value = AsControllerLogs.parseLogSection(allLines, ".OPELOG")
            _programEditLog.value = AsControllerLogs.parseLogSection(allLines, ".PGM_EDT_LOG")
        }
    }

    /**
     * Transforma o texto do Data Bank em uma lista de linhas separadas em campos.
     * Formato de cada linha: DB<num> <frate> <pattern> <atomize> <hvolt> <speed> <jspeed> "comentário".
     */
    private fun parseDataBankEntries(rawContent: String): List<RobotDataBankEntry> {
        val entries = mutableListOf<RobotDataBankEntry>()
        val lines = rawContent.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith(".") || trimmed.startsWith(";") || trimmed.isEmpty()) continue
            
            // formato esperado: DB1 28 10 50 -1 -1 -1 "comentário"
            // primeiro pega o comentário (o que está entre as aspas)
            val firstQuote = trimmed.indexOf('"')
            val lastQuote = trimmed.lastIndexOf('"')
            val comment = if (firstQuote != -1 && lastQuote > firstQuote) {
                trimmed.substring(firstQuote + 1, lastQuote).trim()
            } else ""
            
            // depois tira o comentário para separar só os números com segurança
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

    /**
     * Liga/desliga o terminal: se está conectado desconecta, senão conecta.
     */
    fun toggleConnection() {
        if (isConnected.value) disconnectFromRobot() else connectToRobot()
    }

    /**
     * Confere se um nome de programa é válido. Devolve o texto do erro, ou null se estiver ok.
     * Regras: não vazio, não começa com número, sem espaços, sem caracteres especiais e sem repetir nome.
     */
    fun validateProgramName(name: String): String? {
        if (name.isBlank()) return "O nome não pode ser vazio"
        if (name.first().isDigit()) return "O nome não pode começar com um número"
        if (name.contains(" ")) return "O nome não pode conter espaços"
        val specialChars = "!@#$%^&*()+-=[]{}|;':\",/<>?"
        if (name.any { it in specialChars }) return "O nome não pode conter caracteres especiais"
        if (_programs.value.any { it.name.equals(name, ignoreCase = true) }) return "Já existe um programa com este nome"
        return null
    }

    /**
     * Confere se um nome de variável é válido. Devolve o texto do erro, ou null se estiver ok.
     * Regras: não vazio, não começa com número, sem espaços e (se for nova) sem repetir nome.
     */
    fun validateVariableName(name: String, isNew: Boolean = true): String? {
        if (name.isBlank()) return "O nome não pode ser vazio"
        if (name.first().isDigit()) return "O nome não pode começar com um número"
        if (name.contains(" ")) return "O nome não pode conter espaços"
        if (isNew && _variables.value.any { it.name.equals(name, ignoreCase = true) }) return "Já existe uma variável com este nome"
        return null
    }

    /**
     * Confere o número de uma linha do Data Bank. Devolve o texto do erro, ou null se estiver ok.
     * Regras: não vazio, ser número de 1 a 999 e não existir outra linha com o mesmo número.
     */
    fun validateDataBankNum(num: String): String? {
        if (num.isBlank()) return "O número não pode ser vazio"
        val n = num.toIntOrNull() ?: return "Deve ser um número"
        if (n < 1 || n > 999) return "O número deve estar entre 1 e 999"
        if (_dataBankEntries.value.any { it.num == num }) return "Já existe um registro com este número"
        return null
    }

    /**
     * Copia um programa com outro nome: pega o bloco original, troca o nome e coloca no fim do backup.
     */
    fun duplicateProgram(oldProgram: RobotProgram, newName: String) {
        val summary = _latestBackup.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            val fullBackup = repository.getBackupById(summary.id) ?: return@launch
            val newContent = withContext(Dispatchers.Default) {
                // nome exato: duplicar "pg1" não pode copiar o "pg10"
                val block = AsProgramBlocks.extract(fullBackup.content, oldProgram.name)
                block?.let { fullBackup.content + "\n" + AsProgramBlocks.renameHeader(it, newName) }
            }
            if (newContent == null) {
                _isLoading.value = false
                return@launch
            }
            saveBackupContent(newContent)
        }
    }

    /**
     * Cria uma variável com o nome e valor informados dentro da seção .TRANS do backup
     * (cria a seção se não existir). Também é usado para criar uma variável nova.
     */
    fun duplicateVariable(variable: RobotVariable, newName: String) {
        val summary = _latestBackup.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            val fullBackup = repository.getBackupById(summary.id) ?: return@launch
            val newContent = withContext(Dispatchers.Default) {
                val lines = fullBackup.content.lines().toMutableList()
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

    /**
     * Cria uma linha do Data Bank (cópia de outra, ou uma nova) dentro da seção .sprdb.
     * Se o backup não tiver essa seção, nada é inserido.
     */
    fun duplicateDataBankEntry(entry: RobotDataBankEntry, newNum: String) {
        val summary = _latestBackup.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            val fullBackup = repository.getBackupById(summary.id) ?: return@launch
            val fullContent = withContext(Dispatchers.Default) {
                val lines = fullBackup.content.lines().toMutableList()
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

    /**
     * Troca a linha de uma variável (nome e valor) pelo que foi editado e salva o backup.
     */
    fun updateVariable(oldName: String, updated: RobotVariable) {
        val summary = _latestBackup.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            val fullBackup = repository.getBackupById(summary.id) ?: return@launch
            val newContent = withContext(Dispatchers.Default) {
                val lines = fullBackup.content.lines()
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

    /**
     * Apaga um ou mais programas do texto do backup, numa passada só (para não perder uma
     * exclusão por causa de outra sendo salva ao mesmo tempo). Se o terminal estiver
     * conectado, também manda o robô apagar cada um deles (DELETE).
     */
    fun deletePrograms(programs: List<RobotProgram>) {
        if (programs.isEmpty()) return
        val summary = _latestBackup.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            val fullBackup = repository.getBackupById(summary.id) ?: return@launch
            val nameSet = programs.map { it.name.lowercase() }.toSet()

            // se o robô está conectado, apaga nele também
            if (isConnected.value) {
                programs.forEach { terminalManager.deleteProgram(robotId, it.name) }
            }

            val newContent = withContext(Dispatchers.Default) {
                AsProgramBlocks.remove(fullBackup.content, nameSet)
            }
            saveBackupContent(newContent)
        }
    }

    /**
     * Apaga uma variável do texto do backup. Se o terminal estiver conectado, também manda
     * o robô apagar a variável (DELETE).
     */
    fun deleteVariable(variable: RobotVariable) {
        val summary = _latestBackup.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            val fullBackup = repository.getBackupById(summary.id) ?: return@launch

            // se o robô está conectado, apaga nele também
            if (isConnected.value) {
                terminalManager.deleteVariable(robotId, variable.name, variable.type)
            }

            val newContent = withContext(Dispatchers.Default) {
                val lines = fullBackup.content.lines()
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

    /**
     * Apaga uma linha do Data Bank (pelo número) do texto do backup.
     */
    fun deleteDataBankEntry(entry: RobotDataBankEntry) {
        val summary = _latestBackup.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            val fullBackup = repository.getBackupById(summary.id) ?: return@launch
            val fullContent = withContext(Dispatchers.Default) {
                val lines = fullBackup.content.lines()
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

    /**
     * Salva o texto completo do backup (banco + arquivo) e atualiza a data.
     * Todas as edições passam por aqui; a tela recarrega sozinha ao ver a mudança.
     */
    fun saveBackupContent(content: String) {
        val summary = _latestBackup.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            val fullBackup = repository.getBackupById(summary.id) ?: return@launch
            val updated = fullBackup.copy(
                content = content,
                timestamp = System.currentTimeMillis()
            )
            repository.insertBackup(updated)
            // o observeBackup percebe a mudança e atualiza a tela sozinho
            _isLoading.value = false
        }
    }

    /**
     * Troca a seção .sprdb inteira do backup pelo texto informado (ou a cria no fim, se não existir).
     */
    fun updateDataBank(newContent: String) {
        val summary = _latestBackup.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            val fullBackup = repository.getBackupById(summary.id) ?: return@launch
            val fullContent = withContext(Dispatchers.Default) {
                val lines = fullBackup.content.lines()
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

    /**
     * Troca a linha do Data Bank que tem o mesmo número pela versão editada e salva o backup.
     */
    fun updateDataBankEntry(updatedEntry: RobotDataBankEntry) {
        val summary = _latestBackup.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            val fullBackup = repository.getBackupById(summary.id) ?: return@launch
            val fullContent = withContext(Dispatchers.Default) {
                val lines = fullBackup.content.lines()
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
                        // pega o número da linha para comparar com o que está sendo editado
                        val parts = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
                        val numPart = parts.getOrNull(0)?.removePrefix("DB")
                        
                        if (trimmed.equals(".END", ignoreCase = true)) {
                            inSprdb = false
                        } else if (!found && numPart == updatedEntry.num) {
                            // escreve no mesmo formato do backup (valores separados por espaço)
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

    /**
     * Chamado quando o painel é fechado. Se o terminal não estava conectado, limpa o que sobrou dele.
     */
    override fun onCleared() {
        super.onCleared()
        if (!isConnected.value) {
            terminalManager.disconnect(robotId, clearHistory = true)
        }
    }
}
