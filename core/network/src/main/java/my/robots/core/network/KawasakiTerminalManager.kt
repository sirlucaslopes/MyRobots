package my.robots.core.network

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import my.robots.core.model.Robot
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset

/**
 * Estado do "heartbeat" (pulso) de um robô conectado.
 *
 * - ALIVE: chegou alguma coisa do robô há pouco tempo (ele está respondendo de verdade).
 * - STALE: a conexão continua aberta, mas faz tempo que o robô não manda nada.
 * - DISCONNECTED: sem conexão.
 */
enum class HeartbeatState {
    ALIVE, STALE, DISCONNECTED
}

/**
 * Cuida da conversa com os controladores Kawasaki pela rede (telnet/TCP).
 *
 * O que ela faz:
 * - Abre e fecha a conexão com cada robô (uma conexão por robô).
 * - Recebe o que o robô escreve e guarda como histórico do terminal.
 * - Envia comandos digitados (ou os comandos rápidos) ao robô.
 * - Faz o login automático (usuário e senha) quando o robô pede.
 * - Entende o protocolo de transferência de arquivos do controlador:
 *   quando o robô manda um SAVE, grava o arquivo em /MyRobots/<robô>/;
 *   quando recebe um LOAD, envia o arquivo do celular para o robô.
 *
 * Uma única instância vive o app inteiro (criada em MyRobotsApp), então a
 * conexão continua aberta mesmo quando você troca de tela.
 */
class KawasakiTerminalManager(private val context: Context) {
    /**
     * Guarda o estado da conexão de cada robô. A chave é o id do robô.
     */
    private val connections = mutableMapOf<Int, ConnectionState>()
    /**
     * Área onde rodam as tarefas de rede (em segundo plano, fora da tela principal).
     */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    /**
     * Tabela de caracteres usada na conversa com o robô (ISO-8859-1, a que o controlador usa).
     */
    private val charset = Charset.forName("ISO-8859-1")
    
    /**
     * Transferências que estão esperando o robô de destino conectar.
     * Quando você manda um programa para OUTRO robô, ele fica aqui até a conexão abrir.
     */
    private val pendingTransfers = mutableMapOf<Int, PendingTransfer>()

    companion object {
        /** De quanto em quanto tempo a sondagem de heartbeat é enviada. */
        private const val HEARTBEAT_PING_INTERVAL_MS = 3000L
        /** Se passar esse tempo sem receber nada do robô, o heartbeat vira STALE. */
        private const val HEARTBEAT_STALE_AFTER_MS = 8000L
    }

    /**
     * Um envio pendente: nome do arquivo e o texto que será gravado/carregado no robô.
     */
    data class PendingTransfer(
        val fileName: String,
        val content: String
    )

    /**
     * Devolve a pasta do robô dentro de /MyRobots (ex.: /MyRobots/kawasaki_r1).
     * Se a pasta ainda não existir, ela é criada. O nome vira minúsculo e sem espaços.
     */
    private fun getRobotDir(robotName: String): File {
        val root = Environment.getExternalStorageDirectory()
        val myRobotsDir = File(root, "MyRobots")
        val robotDir = File(myRobotsDir, robotName.lowercase().replace(" ", "_"))
        if (!robotDir.exists()) robotDir.mkdirs()
        return robotDir
    }

    /**
     * Tudo o que o app precisa lembrar sobre a conexão de UM robô.
     *
     * - socket / outputStream: o "cano" de rede aberto (entrada e saída).
     * - job: a tarefa que fica lendo o que o robô manda.
     * - history: linhas do terminal (a tela observa e se atualiza sozinha).
     * - isConnected: true enquanto estiver conectado.
     * - isSaving / saveFileOutputStream / currentFileName: usados quando o robô
     *   está enviando um arquivo (SAVE) para o celular.
     * - isLoading / loadData / loadOffset: usados quando o celular está enviando
     *   um arquivo (LOAD) para o robô, aos pedaços.
     * - robotName: nome do robô (define a pasta onde os arquivos são gravados).
     * - autoLogin / loginUser / loginPassword / loginStep: dados do login automático.
     * - heartbeat / lastActivityAt / heartbeatJob: pulso da conexão (ver HeartbeatState).
     */
    data class ConnectionState(
        var socket: Socket? = null,
        var outputStream: OutputStream? = null,
        var job: Job? = null,
        val history: MutableStateFlow<List<String>> = MutableStateFlow(emptyList()),
        val isConnected: MutableStateFlow<Boolean> = MutableStateFlow(false),
        val heartbeat: MutableStateFlow<HeartbeatState> = MutableStateFlow(HeartbeatState.DISCONNECTED),
        var lastActivityAt: Long = 0L,
        var heartbeatJob: Job? = null,
        var isSaving: Boolean = false,
        var saveFileOutputStream: FileOutputStream? = null,
        var currentFileName: String? = null,
        var isLoading: Boolean = false,
        var loadData: ByteArray? = null,
        var loadOffset: Int = 0,
        var robotName: String = "",
        var autoLogin: Boolean = false,
        var loginUser: String = "",
        var loginPassword: String = "",
        // Etapa do login automático: 0 = esperando "login:", 1 = esperando "password:",
        // 2 = terminou.
        var loginStep: Int = 0 
    )

    /**
     * Devolve o estado da conexão do robô. Se ainda não existir, cria um novo vazio.
     */
    private fun getOrCreateState(robotId: Int) = connections.getOrPut(robotId) { ConnectionState() }

    /**
     * Devolve o histórico do terminal do robô. A tela observa esse valor para mostrar o texto novo.
     */
    fun getHistory(robotId: Int) = getOrCreateState(robotId).history.asStateFlow()
    /**
     * Devolve se o robô está conectado (true) ou não (false). A tela observa esse valor.
     */
    fun getConnectionStatus(robotId: Int) = getOrCreateState(robotId).isConnected.asStateFlow()
    /**
     * Devolve o heartbeat do robô: ALIVE (respondendo), STALE (conectado mas quieto) ou
     * DISCONNECTED. A tela observa esse valor para mostrar o indicador de pulso.
     */
    fun getHeartbeat(robotId: Int) = getOrCreateState(robotId).heartbeat.asStateFlow()

    /**
     * Deixa um arquivo na fila para ser enviado ao robô assim que ele conectar.
     */
    fun setPendingTransfer(robotId: Int, fileName: String, content: String) {
        pendingTransfers[robotId] = PendingTransfer(fileName, content)
    }

    /**
     * Consulta se existe um envio na fila para esse robô. Devolve null se não houver.
     */
    fun getPendingTransfer(robotId: Int): PendingTransfer? {
        return pendingTransfers[robotId]
    }

    /**
     * Tira da fila o envio pendente do robô (chamar depois de enviar).
     */
    fun clearPendingTransfer(robotId: Int) {
        pendingTransfers.remove(robotId)
    }

    /**
     * Conecta no robô pelo IP e porta cadastrados.
     *
     * Passo a passo:
     * 1. Guarda os dados do robô (nome e login automático).
     * 2. Se já estiver conectado, não faz nada.
     * 3. Abre a conexão (espera no máximo 5 segundos).
     * 4. Avisa ao robô que somos um terminal.
     * 5. Sem login automático, manda um Enter para o robô mostrar o prompt.
     * 6. Fica lendo tudo o que o robô responder (readLoop).
     * Se der erro, escreve "Erro: ..." no terminal e marca como desconectado.
     */
    fun connect(robot: Robot) {
        val state = getOrCreateState(robot.id)
        state.robotName = robot.name
        state.autoLogin = robot.autoLogin
        state.loginUser = robot.loginUser
        state.loginPassword = robot.loginPassword
        state.loginStep = 0
        
        if (state.isConnected.value) return

        state.job = scope.launch {
            try {
                val s = Socket()
                state.socket = s
                s.connect(InetSocketAddress(robot.ip, robot.port), 5000)
                state.outputStream = s.getOutputStream()
                val inputStream = s.getInputStream()

                state.isConnected.value = true
                state.lastActivityAt = System.currentTimeMillis()
                state.heartbeat.value = HeartbeatState.ALIVE
                state.heartbeatJob = scope.launch { heartbeatLoop(robot.id) }

                // Negociação do telnet: avisa ao robô que sabemos informar o tipo de terminal
                // (bytes IAC, WILL, TERMINAL-TYPE).
                sendRawDirect(robot.id, byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x18.toByte()))

                if (!state.autoLogin) {
                    delay(300)
                    sendCommand(robot.id, "")
                }

                readLoop(robot.id, inputStream)
            } catch (e: Exception) {
                appendLog(robot.id, "Erro: ${e.message}")
                state.isConnected.value = false
                state.heartbeat.value = HeartbeatState.DISCONNECTED
            }
        }
    }

    /**
     * Fica em loop lendo o que o robô envia e passando para processBytes.
     * Termina quando a conexão cai ou é fechada; aí marca o robô como desconectado.
     */
    private suspend fun readLoop(robotId: Int, inputStream: InputStream) {
        val buffer = ByteArray(8192)
        while (currentCoroutineContext().isActive) {
            try {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) break
                processBytes(robotId, buffer, bytesRead)
            } catch (e: Exception) {
                break
            }
        }
        val state = getOrCreateState(robotId)
        state.isConnected.value = false
        state.heartbeat.value = HeartbeatState.DISCONNECTED
        state.heartbeatJob?.cancel()
    }

    /**
     * Reavalia o heartbeat de tempos em tempos, olhando só para o que JÁ chegou do robô.
     *
     * **Importante:** este loop não escreve nada no socket. O controlador Kawasaki lê o
     * canal caractere por caractere e só processa a linha ao receber Enter — qualquer byte
     * extra injetado por fora (mesmo um "NOP" de telnet) aparece misturado no meio do que o
     * usuário está digitando (foi o que aconteceu: 0xF1 virava um "ñ" solto no comando).
     * Por isso o heartbeat é só passivo: compara `lastActivityAt` (atualizado em `appendLog`
     * sempre que chega algo de verdade do robô) com o tempo atual para decidir entre ALIVE
     * e STALE. Uma queda de conexão de verdade ainda é detectada, só que pelo `readLoop`
     * (que já marca DISCONNECTED ao ler EOF ou erro), não por uma sondagem ativa.
     */
    private suspend fun heartbeatLoop(robotId: Int) {
        val state = getOrCreateState(robotId)
        while (currentCoroutineContext().isActive && state.isConnected.value) {
            delay(HEARTBEAT_PING_INTERVAL_MS)

            val silence = System.currentTimeMillis() - state.lastActivityAt
            state.heartbeat.value = if (silence < HEARTBEAT_STALE_AFTER_MS) {
                HeartbeatState.ALIVE
            } else {
                HeartbeatState.STALE
            }
        }
    }

    /**
     * Interpreta os bytes que chegaram do robô, um por um.
     *
     * O que é reconhecido:
     * - 0xFF: comando de negociação do telnet (3 bytes) -> é ignorado.
     * - 0x05 0x02 <tipo> ... 0x17: bloco do protocolo de transferência de
     *   arquivos -> vai para handleHandshakeBlock.
     * - 0x08: apagar o último caractere (backspace).
     * - ESC [ K  e  ESC [ nD: comandos de tela (limpar fim da linha / voltar cursor).
     * - Qualquer outra coisa: texto normal, mostrado no terminal.
     *
     * Atenção: um bloco de arquivo só é entendido se chegar inteiro no mesmo
     * pacote de rede; se vier partido em dois pacotes, ele é descartado.
     */
    private fun processBytes(robotId: Int, buffer: ByteArray, length: Int) {
        val state = getOrCreateState(robotId)
        var i = 0
        while (i < length) {
            val byte = buffer[i]

            if (byte == 0xFF.toByte()) {
                i += 3 
                continue
            }

            if (byte == 0x05.toByte()) { 
                if (i + 2 < length && buffer[i + 1] == 0x02.toByte()) {
                    val blockType = buffer[i + 2].toInt().toChar()
                    val dataStart = i + 3
                    var etbPos = -1
                    for (j in dataStart until length) {
                        if (buffer[j] == 0x17.toByte()) {
                            etbPos = j
                            break
                        }
                    }
                    if (etbPos != -1) {
                        val content = if (etbPos > dataStart) buffer.copyOfRange(dataStart, etbPos) else byteArrayOf()
                        handleHandshakeBlock(robotId, blockType, content)
                        i = etbPos + 1
                    } else i++
                } else i++
                continue
            }

            // Backspace (0x08): apaga o último caractere que está na tela
            if (byte == 0x08.toByte()) {
                removeLastChar(robotId)
                i++
                continue
            }

            // Sequências de controle de tela (ESC [ ...): limpar fim da linha ou voltar o cursor
            if (byte == 0x1B.toByte() && i + 2 < length && buffer[i+1] == '['.code.toByte()) {
                val code = buffer[i+2].toInt().toChar()
                if (code == 'K') { 
                    clearLastLineEnd(robotId)
                    i += 3
                    continue
                } else if (i + 3 < length && buffer[i+2].toInt().toChar().isDigit() && buffer[i+3] == 'D'.toByte()) {
                    val count = buffer[i+2].toInt().toChar().toString().toIntOrNull() ?: 1
                    repeat(count) { removeLastChar(robotId) }
                    i += 4
                    continue
                }
            }

            // Texto comum: junta tudo até o próximo byte especial e escreve no terminal.
            // Se o login automático ainda não terminou, olha a última linha para ver se
            // o robô pediu usuário ou senha.
            val start = i
            while (i < length && buffer[i] != 0x05.toByte() && buffer[i] != 0xFF.toByte() && buffer[i] != 0x1B.toByte() && buffer[i] != 0x08.toByte()) i++
            val chunk = String(buffer, start, i - start, charset)
            
            appendLog(robotId, chunk)

            if (state.autoLogin && state.loginStep < 2) {
                val lastLine = state.history.value.lastOrNull() ?: ""
                handleAutoLoginMonitoring(robotId, lastLine)
            }
        }
    }

    /**
     * Vigia a última linha do terminal para fazer o login sozinho.
     *
     * - Viu "login:" ou "user:"  -> digita o usuário e dá Enter.
     * - Viu "password:"          -> digita a senha e dá Enter (duas vezes).
     * Os pequenos atrasos (delay) existem para o controlador conseguir acompanhar.
     */
    private fun handleAutoLoginMonitoring(robotId: Int, currentLine: String) {
        val state = getOrCreateState(robotId)
        val lowerLine = currentLine.lowercase()
        
        if (state.loginStep == 0 && (lowerLine.contains("login:") || lowerLine.contains("user:"))) {
            state.loginStep = 1
            scope.launch {
                delay(300)
                sendStringCharByChar(robotId, state.loginUser)
                delay(100)
                sendCommand(robotId, "") 
            }
        } else if (state.loginStep == 1 && lowerLine.contains("password:")) {
            state.loginStep = 2
            scope.launch {
                delay(300)
                sendStringCharByChar(robotId, state.loginPassword)
                delay(100)
                sendCommand(robotId, "")
                delay(600)
                sendCommand(robotId, "")
            }
        }
    }

    /**
     * Envia um texto letra por letra, com uma pausa de 50 ms entre elas.
     * O controlador Kawasaki perde caracteres se receber tudo de uma vez.
     */
    private suspend fun sendStringCharByChar(robotId: Int, text: String) {
        text.forEach { char ->
            sendRawDirect(robotId, char.toString().toByteArray(charset))
            delay(50)
        }
    }

    /**
     * Envia bytes crus ao robô (sem modificar nada), em segundo plano. Erros são ignorados.
     */
    private fun sendRawDirect(robotId: Int, bytes: ByteArray) {
        connections[robotId]?.outputStream?.let { out ->
            scope.launch {
                try {
                    out.write(bytes)
                    out.flush()
                } catch (e: Exception) {}
            }
        }
    }

    /**
     * Trata um bloco do protocolo de transferência de arquivos do controlador.
     *
     * Tipos de bloco:
     * - 'B': o robô vai ENVIAR um arquivo (SAVE) -> abre o arquivo para gravar.
     * - 'A': o robô quer RECEBER um arquivo (LOAD) -> prepara o arquivo para enviar.
     * - 'D': pedaço de dados do arquivo que o robô está enviando -> grava.
     * - 'C': o robô pediu o próximo pedaço do arquivo -> envia.
     * - 'E': fim da transferência -> confirma e fecha o arquivo.
     * Para 'A', 'B' e 'E' o app responde com uma confirmação (sendHandshakeResponse).
     */
    private fun handleHandshakeBlock(robotId: Int, type: Char, content: ByteArray) {
        val state = getOrCreateState(robotId)
        when (type) {
            'B' -> {
                val fileName = String(content, charset).trim()
                startSaveFile(robotId, if (fileName.isNotEmpty()) fileName else "backup.as")
                sendHandshakeResponse(robotId, 'B')
            }
            'A' -> {
                val fileName = String(content, charset).trim()
                val success = prepareLoadFile(robotId, fileName)
                sendHandshakeResponse(robotId, 'A')
            }
            'D' -> {
                if (state.isSaving) {
                    state.saveFileOutputStream?.write(content)
                    state.saveFileOutputStream?.flush()
                }
                appendLog(robotId, String(content, charset))
            }
            'C' -> { if (state.isLoading) sendDataBlock(robotId) }
            'E' -> {
                sendHandshakeResponse(robotId, 'E')
                if (state.isSaving) stopSaveFile(robotId)
                state.isLoading = false
            }
        }
    }

    /**
     * Abre o arquivo (na pasta do robô) onde será gravado o que o robô enviar. Se falhar, avisa no terminal.
     */
    private fun startSaveFile(robotId: Int, fileName: String) {
        val state = getOrCreateState(robotId)
        try {
            val file = File(getRobotDir(state.robotName), fileName)
            state.saveFileOutputStream = FileOutputStream(file)
            state.isSaving = true
        } catch (e: Exception) {
            appendLog(robotId, ">>> Erro E/S: ${e.message}")
        }
    }

    /**
     * Termina a gravação: fecha o arquivo e desliga o modo de salvar.
     */
    private fun stopSaveFile(robotId: Int) {
        val state = getOrCreateState(robotId)
        state.isSaving = false
        try { state.saveFileOutputStream?.close() } catch (e: Exception) {}
        state.saveFileOutputStream = null
    }

    /**
     * Prepara o envio de um arquivo da pasta do robô para o robô.
     * Lê o arquivo inteiro para a memória. Devolve false se o arquivo não existir.
     */
    private fun prepareLoadFile(robotId: Int, fileName: String): Boolean {
        val state = getOrCreateState(robotId)
        val file = File(getRobotDir(state.robotName), fileName)
        return if (file.exists()) {
            state.loadData = file.readBytes()
            state.loadOffset = 0
            state.isLoading = true
            true
        } else {
            state.isLoading = false
            false
        }
    }

    /**
     * Envia ao robô o próximo pedaço do arquivo (até 512 bytes por vez).
     * Quando não sobrar mais nada, envia o bloco final com a marca de fim de arquivo (0x1A).
     */
    private fun sendDataBlock(robotId: Int) {
        val state = getOrCreateState(robotId)
        val data = state.loadData ?: return
        val chunkSize = 512
        val remaining = data.size - state.loadOffset
        if (remaining <= 0) {
            sendRaw(robotId, byteArrayOf(0x02.toByte(), 'C'.code.toByte(), 0x20, 0x20, 0x20, 0x20, 0x30, 0x1A.toByte(), 0x17.toByte()))
            return
        }
        val currentSize = if (remaining > chunkSize) chunkSize else remaining
        val blockData = data.copyOfRange(state.loadOffset, state.loadOffset + currentSize)
        state.loadOffset += currentSize
        val header = byteArrayOf(0x02.toByte(), 'C'.code.toByte(), 0x20, 0x20, 0x20, 0x20, 0x30)
        sendRaw(robotId, header + blockData + byteArrayOf(0x17.toByte()))
    }

    /**
     * Manda ao robô a confirmação padrão do protocolo para o tipo de bloco informado (A, B ou E).
     */
    private fun sendHandshakeResponse(robotId: Int, type: Char) {
        val response = byteArrayOf(0x02.toByte(), type.code.toByte(), 0x20, 0x20, 0x20, 0x20, 0x30, 0x17.toByte())
        sendRaw(robotId, response)
    }

    /**
     * Envia bytes ao robô em segundo plano. Igual ao sendRawDirect, usado nos blocos do protocolo.
     */
    private fun sendRaw(robotId: Int, bytes: ByteArray) {
        connections[robotId]?.outputStream?.let { out ->
            scope.launch { try { out.write(bytes); out.flush() } catch (e: Exception) {} }
        }
    }

    /**
     * Escreve texto no final do terminal.
     *
     * - Remove caracteres de controle invisíveis.
     * - Se o texto não tem quebra de linha, ele continua a última linha.
     * - Guarda no máximo as últimas 1000 linhas, para não pesar a memória.
     */
    fun appendLog(robotId: Int, text: String) {
        val state = getOrCreateState(robotId)
        val cleanText = text.replace(Regex("[\\x00-\\x07\\x0B\\x0E-\\x1F]"), "")
        if (cleanText.isEmpty() && !text.contains("\n") && !text.contains("\r")) return

        // chegou algo de verdade do robô: o heartbeat volta a ficar ALIVE
        state.lastActivityAt = System.currentTimeMillis()
        if (state.isConnected.value) state.heartbeat.value = HeartbeatState.ALIVE

        val currentHistory = state.history.value.toMutableList()
        val lines = cleanText.replace("\r\n", "\n").replace("\r", "\n").split("\n")

        lines.forEachIndexed { index, line ->
            if (index == 0) {
                if (currentHistory.isEmpty()) {
                    currentHistory.add(line)
                } else {
                    val lastLine = currentHistory.last()
                    currentHistory[currentHistory.size - 1] = lastLine + line
                }
            } else {
                currentHistory.add(line)
            }
        }
        state.history.value = currentHistory.takeLast(1000)
    }

    /**
     * Apaga o último caractere da última linha do terminal (efeito do backspace).
     */
    private fun removeLastChar(robotId: Int) {
        val state = getOrCreateState(robotId)
        val currentHistory = state.history.value.toMutableList()
        if (currentHistory.isNotEmpty()) {
            val lastLine = currentHistory.last()
            if (lastLine.isNotEmpty()) {
                currentHistory[currentHistory.size - 1] = lastLine.dropLast(1)
                state.history.value = currentHistory
            }
        }
    }

    /**
     * Apaga o final da última linha do terminal (efeito de ESC [ K).
     * Se a linha tem o prompt ">", mantém o texto até o ">"; senão, limpa a linha toda.
     */
    private fun clearLastLineEnd(robotId: Int) {
        val state = getOrCreateState(robotId)
        val currentHistory = state.history.value.toMutableList()
        if (currentHistory.isNotEmpty()) {
            val lastLine = currentHistory.last()
            if (lastLine.contains(">")) {
                currentHistory[currentHistory.size - 1] = lastLine.substringBefore(">") + ">"
            } else {
                currentHistory[currentHistory.size - 1] = ""
            }
            state.history.value = currentHistory
        }
    }

    /**
     * Limpa todo o texto do terminal desse robô.
     */
    fun clearLog(robotId: Int) {
        val state = getOrCreateState(robotId)
        state.history.value = emptyList()
    }

    /**
     * Envia UMA tecla ao robô, sem apertar Enter (usado enquanto você digita).
     * Teclas especiais: "\b" = apagar, "RECALL" = repetir comando, "UP"/"DOWN" = setas.
     */
    fun sendChar(robotId: Int, char: String) {
        connections[robotId]?.outputStream?.let { out ->
            scope.launch {
                try {
                    val bytes = when (char) {
                        "\b" -> byteArrayOf(0x08.toByte())
                        "RECALL" -> byteArrayOf(0x0C.toByte())
                        "UP" -> byteArrayOf(0x1B.toByte(), '['.code.toByte(), 'A'.code.toByte())
                        "DOWN" -> byteArrayOf(0x1B.toByte(), '['.code.toByte(), 'B'.code.toByte())
                        else -> char.toByteArray(charset)
                    }
                    out.write(bytes)
                    out.flush()
                } catch (e: Exception) {}
            }
        }
    }

    /**
     * Envia um comando ao robô e dá Enter.
     *
     * - O comando digitado também aparece no terminal, com ">" na frente.
     * - Texto vazio serve só para dar Enter.
     * - "SPACE" envia um espaço.
     * - isManualFinalize = true envia só o Enter (o texto já foi enviado tecla por tecla).
     */
    fun sendCommand(robotId: Int, command: String, isManualFinalize: Boolean = false) {
        connections[robotId]?.outputStream?.let { out ->
            scope.launch {
                try {
                    if (command == "SPACE") {
                        out.write(byteArrayOf(0x20.toByte()))
                    } else {
                        if (isManualFinalize) {
                            out.write("\r\n".toByteArray(charset))
                        } else {
                            if (command.isNotEmpty()) {
                                appendLog(robotId, "\n> $command")
                            }
                            out.write("$command\r\n".toByteArray(charset))
                        }
                    }
                    out.flush()
                } catch (e: Exception) {}
            }
        }
    }

    /**
     * Manda o robô apagar um programa (comando DELETE).
     *
     * - onlyProgram: usa /P e apaga só o programa (sem sub-rotinas e variáveis locais).
     * - forced: usa /D e força apagar, mesmo se outro programa usar esse.
     */
    fun deleteProgram(robotId: Int, programName: String, onlyProgram: Boolean = false, forced: Boolean = true) {
        val cmd = buildString {
            append("DELETE")
            if (onlyProgram) append("/P")
            if (forced) append("/D")
            append(" ")
            append(programName)
        }
        sendCommand(robotId, cmd)
    }

    /**
     * Manda o robô apagar uma variável (comando DELETE).
     * O tipo escolhe a opção: posição = /L, real = /R, texto = /S, inteiro = /INT.
     * Se o tipo não for reconhecido, nada é enviado.
     * forced usa /D para forçar a exclusão.
     */
    fun deleteVariable(robotId: Int, varName: String, type: String, forced: Boolean = true) {
        val typeModifier = when (type.uppercase()) {
            "TRANS", "POSE", "POS", "L" -> "/L"
            "REALS", "REAL", "R" -> "/R"
            "STRINGS", "STRING", "S" -> "/S"
            "INTEGER", "INT" -> "/INT"
            else -> ""
        }
        if (typeModifier.isEmpty()) return
        
        val cmd = buildString {
            append("DELETE")
            append(typeModifier)
            if (forced) append("/D")
            append(" ")
            append(varName)
        }
        sendCommand(robotId, cmd)
    }

    /**
     * Desconecta do robô: para a leitura, fecha a conexão e o arquivo aberto.
     * Com clearHistory = true, também apaga o histórico do terminal e esquece o robô.
     */
    fun disconnect(robotId: Int, clearHistory: Boolean = false) {
        val state = connections[robotId] ?: return
        state.job?.cancel()
        state.heartbeatJob?.cancel()
        try { state.socket?.close(); state.outputStream?.close(); state.saveFileOutputStream?.close() } catch (e: Exception) {}
        state.socket = null
        state.outputStream = null
        state.isConnected.value = false
        state.heartbeat.value = HeartbeatState.DISCONNECTED
        if (clearHistory) {
            state.history.value = emptyList()
            connections.remove(robotId)
        }
    }
}
