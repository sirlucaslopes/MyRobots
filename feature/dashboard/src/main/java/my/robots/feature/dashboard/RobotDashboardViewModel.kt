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
import my.robots.core.common.ascode.AsControllerReplies
import my.robots.core.common.ascode.AsRobotInfo
import my.robots.core.common.ascode.ControllerMemory
import my.robots.core.data.ControllerChecks
import my.robots.core.designsystem.SendProgress
import my.robots.core.designsystem.SendState
import my.robots.core.model.HeartbeatState
import my.robots.core.common.ascode.RobotInfo
import my.robots.core.common.ascode.DailyUsage
import my.robots.core.common.ascode.RobotUsageHistory
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
 * - type: FRAME (posição em transformação, linha sem "=" na .TRANS), JOINTS (posição em juntas,
 *   nome com "#", na .JOINTS), REALS, STRINGS (nome com "$", valor entre aspas), INTEGER...
 *
 * Os tipos e prefixos seguem o AS Language Reference Manual (3.4): "pick" (transformação),
 * "#pick" (juntas), "count" (real) e "$count" (texto). No arquivo de SAVE, cada tipo fica na sua
 * seção: .TRANS, .JOINTS, .REALS e .STRINGS.
 */
data class RobotVariable(
    val name: String,
    val value: String = "0.000",
    val type: String = "TRANS"
) {
    /** Seção do backup onde a variável fica. */
    val section: String get() = when (type) {
        "FRAME", "TRANS" -> ".TRANS"
        "JOINTS" -> ".JOINTS"
        else -> ".$type"
    }

    /** Linha da variável no backup: posições com espaço, as outras com " = ". */
    val line: String get() = if (type == "FRAME" || type == "JOINTS") "$name $value" else "$name = $value"
}

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
    private val checks: ControllerChecks,
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

    private val _isShowingNewestBackup = MutableStateFlow(true)
    /**
     * false quando o painel mostra um backup mais antigo, escolhido no histórico (a home
     * avisa que não é o mais recente).
     */
    val isShowingNewestBackup: StateFlow<Boolean> = _isShowingNewestBackup.asStateFlow()

    private val _robotInfo = MutableStateFlow(RobotInfo())
    /**
     * Dados do robô lidos do backup SAVE/FULL (modelo, eixos, horímetro...). Vazio num
     * backup só com programas.
     */
    val robotInfo: StateFlow<RobotInfo> = _robotInfo.asStateFlow()

    private val _dailyUsage = MutableStateFlow<List<DailyUsage>>(emptyList())
    /**
     * Uso do robô por dia (horas em operação, ligado, motor ligado), montado com os
     * contadores de todos os backups SAVE/FULL do robô. Vazio com menos de dois backups.
     */
    val dailyUsage: StateFlow<List<DailyUsage>> = _dailyUsage.asStateFlow()
    private var usageKey: Pair<Int, Long>? = null

    private val _foreignBackups = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    /**
     * Backups da pasta deste robô que são de outro controlador: (arquivo, série). A série de
     * referência é a cadastrada no robô; sem ela, a do backup mais novo.
     */
    val foreignBackups: StateFlow<List<Pair<String, String>>> = _foreignBackups.asStateFlow()

    private val _axisMoveHoursLast30 = MutableStateFlow<List<Double>>(emptyList())
    /**
     * Horas em movimento de cada eixo nos últimos 30 dias (diferença do MOVE_TJT entre os
     * backups do período). Vazio sem dois backups SAVE/FULL nesse período.
     */
    val axisMoveHoursLast30: StateFlow<List<Double>> = _axisMoveHoursLast30.asStateFlow()

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

    /**
     * Memória de programas do controlador: a última leitura do comando FREE (guardada no
     * aparelho, aparece mesmo sem conexão). O `ControllerChecks` lê sozinho a cada login.
     */
    val controllerMemory: StateFlow<ControllerMemory?> = checks.lastMemory(robotId)

    private val _isReadingMemory = MutableStateFlow(false)
    /** true enquanto conecta ou espera a resposta do FREE (inclusive nas checagens do login). */
    val isReadingMemory: StateFlow<Boolean> = combine(_isReadingMemory, checks.busy) { reading, busy ->
        reading || robotId in busy
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Botão "Ler agora": se o robô não estiver conectado, conecta primeiro (o login já lê a
     * memória); se estiver, manda o FREE.
     */
    fun readMemoryNow() {
        if (_isReadingMemory.value) return
        val r = _robot.value ?: return
        viewModelScope.launch {
            _isReadingMemory.value = true
            if (isConnected.value) checks.readMemory(robotId) else checks.connectAndWait(r)
            _isReadingMemory.value = false
        }
    }

    // ---------- Destino de um envio (lista com os robôs e o LED de cada um) ----------

    private val _connectedIds = MutableStateFlow<Set<Int>>(emptySet())
    /** Ids dos robôs conectados agora (para a lista de destino do envio). */
    val connectedIds: StateFlow<Set<Int>> = _connectedIds.asStateFlow()

    private val _heartbeats = MutableStateFlow<Map<Int, HeartbeatState>>(emptyMap())
    /** Heartbeat de cada robô (para a lista de destino do envio). */
    val heartbeats: StateFlow<Map<Int, HeartbeatState>> = _heartbeats.asStateFlow()

    private val _sendProgress = MutableStateFlow<Map<Int, SendProgress>>(emptyMap())
    /** Andamento do envio atual, robô por robô (vazio = nenhum envio). */
    val sendProgress: StateFlow<Map<Int, SendProgress>> = _sendProgress.asStateFlow()

    /** Limpa o andamento ao fechar a lista de destino (só se o envio já terminou). */
    fun clearSendProgress() {
        if (_sendProgress.value.values.all { it.finished }) _sendProgress.value = emptyMap()
    }

    // Ids que já têm um observador de conexão/heartbeat (evita duplicar o coletor).
    private val watchedIds = mutableSetOf<Int>()

    private fun watchRobot(id: Int) {
        if (!watchedIds.add(id)) return
        viewModelScope.launch {
            terminalManager.getConnectionStatus(id).collect { on ->
                _connectedIds.update { if (on) it + id else it - id }
            }
        }
        viewModelScope.launch {
            terminalManager.getHeartbeat(id).collect { hb -> _heartbeats.update { it + (id to hb) } }
        }
    }

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
        viewModelScope.launch {
            repository.allRobots.collect { robots -> robots.forEach { watchRobot(it.id) } }
        }
    }

    /**
     * Observa no banco os dados do robô deste painel (a série, por exemplo, pode ser gravada
     * pelas checagens do login com o painel aberto).
     */
    private fun loadRobot() {
        viewModelScope.launch {
            repository.allRobots.collect { robots -> _robot.value = robots.firstOrNull { it.id == robotId } }
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
                
                // conecta se preciso e espera o login e as checagens (ID, relógio, FREE) acabarem,
                // para o LOAD não se misturar com elas
                val r = repository.getRobotById(robotId)
                if (r != null) checks.connectAndWait(r)
                
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
     * Envia o mesmo arquivo para vários robôs, ao mesmo tempo, cada um na sua conexão:
     * 1. conecta (se preciso) e espera o login e as checagens (`ControllerChecks.connectAndWait`);
     * 2. grava o arquivo na pasta do robô de destino;
     * 3. manda `LOAD <arquivo>` e espera o controlador voltar ao prompt.
     * O andamento de cada robô fica em [sendProgress]. Um robô que falha não para os outros.
     */
    private fun sendFileToRobots(targets: List<Robot>, fileName: String, content: String) {
        if (targets.isEmpty() || content.isBlank()) return
        _sendProgress.value = targets.associate { it.id to SendProgress(SendState.CONNECTING) }
        fun set(id: Int, p: SendProgress) = _sendProgress.update { it + (id to p) }
        targets.forEach { target ->
            viewModelScope.launch {
                if (!checks.connectAndWait(target)) {
                    set(target.id, SendProgress(SendState.FAILED, "Não conectou"))
                    return@launch
                }
                set(target.id, SendProgress(SendState.SENDING))
                if (!repository.saveFileToRobotFolder(target.id, fileName, content)) {
                    set(target.id, SendProgress(SendState.FAILED, "Não gravou o arquivo"))
                    return@launch
                }
                delay(500)
                val ok = checks.sendAndAwaitPrompt(target.id, "LOAD $fileName")
                // "File load completed. (N errors)": 0 = deu certo; sem essa linha, vale o prompt
                val errors = AsControllerReplies.parseLoadErrors(
                    terminalManager.getHistory(target.id).value.takeLast(30).joinToString("\n")
                )
                set(
                    target.id,
                    when {
                        !ok -> SendProgress(SendState.FAILED, "Sem resposta ao LOAD: veja o terminal")
                        errors == null -> SendProgress(SendState.DONE, "LOAD terminou")
                        errors == 0 -> SendProgress(SendState.DONE, "LOAD sem erros")
                        else -> SendProgress(SendState.FAILED, "LOAD com $errors erro(s): veja o terminal")
                    }
                )
            }
        }
    }

    /**
     * Envia um ou mais programas do backup para os robôs escolhidos, empacotados num arquivo
     * só: transfer_<nome>.as (um programa) ou transfer_batch_<hora>.as (vários).
     */
    fun sendProgramsToRobots(programs: List<RobotProgram>, targets: List<Robot>) {
        if (programs.isEmpty()) return
        viewModelScope.launch {
            val packed = packProgramsContent(programs)
            val fileName = if (programs.size == 1) {
                "transfer_${FileUtil.sanitizeFileName(programs[0].name).replace(".as", "")}.as"
            } else {
                "transfer_batch_${System.currentTimeMillis()}.as"
            }
            sendFileToRobots(targets, fileName, packed)
        }
    }

    /**
     * Texto com as variáveis escolhidas, cada uma dentro da sua seção, como no backup
     * (".TRANS / linhas / .END", ".REALS / linhas / .END"...). Lê o texto do backup: as linhas
     * saem exatamente como estão lá. Seções sem variável escolhida ficam de fora.
     */
    suspend fun variablesContent(variables: List<RobotVariable>): String {
        val summary = _latestBackup.value ?: return ""
        val fullBackup = repository.getBackupById(summary.id) ?: return ""
        val names = variables.map { it.name }.toSet()
        return withContext(Dispatchers.Default) {
            val out = StringBuilder()
            var header: String? = null
            val picked = mutableListOf<String>()
            for (line in fullBackup.content.lines()) {
                val trimmed = line.trim()
                val upper = trimmed.uppercase()
                if (header == null) {
                    if (upper in VARIABLE_SECTIONS) {
                        header = line
                        picked.clear()
                    }
                    continue
                }
                if (upper == ".END") {
                    if (picked.isNotEmpty()) {
                        out.append(header).append("\n")
                        picked.forEach { out.append(it).append("\n") }
                        out.append(line).append("\n")
                    }
                    header = null
                    continue
                }
                if (variableNameOf(trimmed) in names) picked.add(line)
            }
            out.toString()
        }
    }

    /** Nome do arquivo de envio/compartilhamento das variáveis. */
    fun variablesFileName(variables: List<RobotVariable>) =
        if (variables.size == 1) "var_${FileUtil.sanitizeFileName(variables[0].name).replace(".as", "")}.as"
        else "var_batch_${System.currentTimeMillis()}.as"

    /** Envia as variáveis escolhidas para os robôs escolhidos (um arquivo só, com as seções). */
    fun sendVariablesToRobots(variables: List<RobotVariable>, targets: List<Robot>) {
        if (variables.isEmpty()) return
        viewModelScope.launch {
            sendFileToRobots(targets, variablesFileName(variables), variablesContent(variables))
        }
    }

    /** Texto ".sprdb ... .END" com as linhas do Data Bank, no formato do backup. */
    fun dataBankContent(entries: List<RobotDataBankEntry>): String = buildString {
        append(".sprdb\n")
        entries.forEach { append(dataBankLine(it)).append("\n") }
        append(".END")
    }

    private fun dataBankLine(e: RobotDataBankEntry) =
        "  DB${e.num} ${e.frate} ${e.pattern} ${e.atomize} ${e.hvolt} ${e.speed} ${e.jspeed} \"${e.comment}\""

    /** Nome do arquivo de envio/compartilhamento das linhas do Data Bank. */
    fun dataBankFileName(entries: List<RobotDataBankEntry>) =
        if (entries.size == 1) "db_${entries[0].num}.as" else "db_batch_${System.currentTimeMillis()}.as"

    /** Envia linhas do Data Bank para os robôs escolhidos (arquivo .sprdb ... .END). */
    fun sendDataBankEntriesToRobots(entries: List<RobotDataBankEntry>, targets: List<Robot>) {
        if (entries.isEmpty()) return
        sendFileToRobots(targets, dataBankFileName(entries), dataBankContent(entries))
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
                    // o mais recente que seja backup de verdade (arquivos de envio importados
                    // até a v1.2, como transfer_pg635.as, não contam); sem nenhum, o mais recente
                    summaries.filterNot { FileUtil.isTransferFile(it.fileName) }.maxByOrNull { it.timestamp }
                        ?: summaries.maxByOrNull { it.timestamp }
                }
                val newest = summaries.filterNot { FileUtil.isTransferFile(it.fileName) }.maxByOrNull { it.timestamp }
                    ?: summaries.maxByOrNull { it.timestamp }
                _isShowingNewestBackup.value = targetSummary == null || targetSummary.id == newest?.id
                loadUsageIfChanged(summaries.size, newest?.timestamp ?: 0L)

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
     * Refaz o uso por dia quando a lista de backups muda (entrou ou saiu um backup).
     * A consulta traz só o trecho ".OPE_INFO1" de cada backup, recortado pelo banco.
     */
    private fun loadUsageIfChanged(count: Int, newestTimestamp: Long) {
        val key = count to newestTimestamp
        if (key == usageKey) return
        usageKey = key
        viewModelScope.launch {
            val snippets = repository.getUsageSnippets(robotId)
            withContext(Dispatchers.Default) {
                val points = snippets.map { RobotUsageHistory.pointFrom(it.timestamp, it.fileName, it.snippet) }
                val reference = _robot.value?.serialNumber ?: points.lastOrNull { it.serialNumber != null }?.serialNumber
                _foreignBackups.value = if (reference == null) emptyList() else snippets.zip(points)
                    .filter { (_, p) -> p.serialNumber != null && p.serialNumber != reference }
                    .map { (s, p) -> s.fileName to p.serialNumber!! }
                _dailyUsage.value = RobotUsageHistory.daily(points)
                _axisMoveHoursLast30.value = RobotUsageHistory.axisMoveHoursLast(points, 30)
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
                    if (upper in listOf(".TRANS", ".JOINTS", ".REALS", ".STRINGS", ".INTEGER", ".POS")) {
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
                                val posType = if (currentSection == ".JOINTS") "JOINTS" else "FRAME"
                                variablesList.add(RobotVariable(name = parts[0], value = parts.drop(1).joinToString(" "), type = posType))
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
            // lido do texto original: .ROBOTDATA1 e .OPE_INFO1 podem vir depois de uma seção
            // que a visão limpa corta
            val info = AsRobotInfo.parse(content)
            _robotInfo.value = info
            // robô ainda sem série: a do backup passa a ser a dele
            val current = _robot.value
            if (current != null && current.serialNumber == null && info.serialNumber != null) {
                repository.setRobotSerialNumber(robotId, info.serialNumber)
            }
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
     * Cria uma cópia da variável com outro nome (mesmo tipo e valor).
     */
    fun duplicateVariable(variable: RobotVariable, newName: String) = createVariable(variable.copy(name = newName))

    /**
     * Cria a variável na seção do tipo dela (.TRANS, .JOINTS, .REALS ou .STRINGS), antes do
     * ".END" da seção. Se o backup não tiver essa seção, ela é criada no fim do arquivo.
     */
    fun createVariable(variable: RobotVariable) {
        val summary = _latestBackup.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            val fullBackup = repository.getBackupById(summary.id) ?: return@launch
            val newContent = withContext(Dispatchers.Default) {
                val lines = fullBackup.content.lines().toMutableList()
                var insertIndex = -1
                var inSection = false
                for (i in lines.indices) {
                    val upper = lines[i].trim().uppercase()
                    if (upper == variable.section) inSection = true
                    else if (inSection && upper == ".END") { insertIndex = i; break }
                }
                if (insertIndex != -1) {
                    lines.add(insertIndex, variable.line)
                } else {
                    while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
                    lines.add(variable.section)
                    lines.add(variable.line)
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
                // só dentro das seções de variáveis: uma linha de programa que comece com o
                // mesmo nome não pode ser trocada
                val result = StringBuilder()
                var inSection = false
                var replaced = false
                for (line in fullBackup.content.lines()) {
                    val trimmed = line.trim()
                    val upper = trimmed.uppercase()
                    when {
                        upper in VARIABLE_SECTIONS -> inSection = true
                        upper == ".END" -> inSection = false
                        inSection && !replaced && variableNameOf(trimmed) == oldName -> {
                            result.append(updated.line).append("\n")
                            replaced = true
                            continue
                        }
                    }
                    result.append(line).append("\n")
                }
                result.toString().trimEnd('\n')
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
     * Apaga uma ou mais variáveis do texto do backup, numa gravação só. Só mexe nas linhas de
     * dentro das seções de variáveis (.TRANS, .REALS...). Se o terminal estiver conectado,
     * também manda o robô apagar cada uma (DELETE).
     */
    fun deleteVariables(variables: List<RobotVariable>) {
        if (variables.isEmpty()) return
        val summary = _latestBackup.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            val fullBackup = repository.getBackupById(summary.id) ?: return@launch
            if (isConnected.value) {
                variables.forEach { terminalManager.deleteVariable(robotId, it.name, it.type) }
            }
            val names = variables.map { it.name }.toSet()
            val newContent = withContext(Dispatchers.Default) {
                val result = StringBuilder()
                var inSection = false
                for (line in fullBackup.content.lines()) {
                    val trimmed = line.trim()
                    val upper = trimmed.uppercase()
                    when {
                        upper in VARIABLE_SECTIONS -> inSection = true
                        upper == ".END" -> inSection = false
                        inSection && variableNameOf(trimmed) in names -> continue
                    }
                    result.append(line).append("\n")
                }
                result.toString().trimEnd('\n')
            }
            saveBackupContent(newContent)
        }
    }

    fun deleteVariable(variable: RobotVariable) = deleteVariables(listOf(variable))

    /**
     * Apaga uma linha do Data Bank (pelo número) do texto do backup.
     */
    fun deleteDataBankEntry(entry: RobotDataBankEntry) = deleteDataBankEntries(listOf(entry))

    /**
     * Apaga várias linhas do Data Bank (pelo número) do texto do backup, numa gravação só.
     */
    fun deleteDataBankEntries(entries: List<RobotDataBankEntry>) {
        val nums = entries.map { it.num }.toSet()
        rewriteDataBank { num, _ -> if (num in nums) null else DataBankKeep }
    }

    /**
     * Troca as linhas do Data Bank que têm o mesmo número pelas versões editadas, numa
     * gravação só (edição de uma linha ou "Editar selecionados").
     */
    fun updateDataBankEntries(updated: List<RobotDataBankEntry>) {
        val byNum = updated.associateBy { it.num }
        rewriteDataBank { num, _ -> byNum[num]?.let { dataBankLine(it) } ?: DataBankKeep }
    }

    /**
     * Percorre a seção .sprdb do backup e, para cada linha "DBn ...", pergunta a [decide]:
     * [DataBankKeep] mantém a linha, null apaga, outro texto substitui. Grava o backup no fim.
     */
    private fun rewriteDataBank(decide: (num: String, line: String) -> String?) {
        val summary = _latestBackup.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            val fullBackup = repository.getBackupById(summary.id) ?: return@launch
            val fullContent = withContext(Dispatchers.Default) {
                val result = StringBuilder()
                var inSprdb = false
                for (line in fullBackup.content.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.equals(".sprdb", ignoreCase = true)) {
                        inSprdb = true
                        result.append(line).append("\n")
                        continue
                    }
                    if (inSprdb) {
                        if (trimmed.equals(".END", ignoreCase = true)) {
                            inSprdb = false
                        } else {
                            val num = trimmed.split(Regex("\\s+")).firstOrNull()?.removePrefix("DB")
                            if (num != null && num.isNotBlank()) {
                                when (val out = decide(num, line)) {
                                    DataBankKeep -> result.append(line).append("\n")
                                    null -> Unit
                                    else -> result.append(out).append("\n")
                                }
                                continue
                            }
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
    fun updateDataBankEntry(updatedEntry: RobotDataBankEntry) = updateDataBankEntries(listOf(updatedEntry))

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

/** Marca "manter a linha como está" no [RobotDashboardViewModel.rewriteDataBank]. */
private const val DataBankKeep = "\u0000keep"

/** Seções do backup que guardam variáveis. */
private val VARIABLE_SECTIONS = setOf(".TRANS", ".REALS", ".STRINGS", ".INTEGER", ".POS", ".JOINT", ".POINT")

/** Nome da variável numa linha de seção ("a1 0 0 0..." ou "speed = 50"), ou null. */
private fun variableNameOf(trimmed: String): String? {
    if (trimmed.isEmpty() || trimmed.startsWith(";")) return null
    return if (trimmed.contains("=")) trimmed.substringBefore("=").trim()
    else trimmed.split(Regex("\\s+")).firstOrNull()
}
