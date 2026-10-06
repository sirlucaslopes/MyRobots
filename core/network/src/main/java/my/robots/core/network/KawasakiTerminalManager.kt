package my.robots.core.network

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import my.robots.core.model.HeartbeatState
import my.robots.core.model.Robot
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

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
 *   quando recebe um LOAD, envia o arquivo para o robô.
 *
 * Protocolo de transferência (conferido com o K-ROSET, ver GUIDE.md seção 5):
 * - o robô manda blocos `05 02 <tipo> <conteúdo> 17`, misturados com o texto normal;
 * - LOAD: `A<arquivo>` (o app responde `02 A "    0" 17`), depois `C` a cada pedaço que ele quer
 *   (o app manda `02 C "    0" <até 512 bytes> 17`, e no fim `02 C "    0" 1A 17`), e `E` no fim
 *   (o app responde `E`). O controlador mostra "File load completed. (N errors)";
 * - SAVE: `B<arquivo>` (o app responde `B`), vários `D<texto>` e `E` no fim (o app responde `E`).
 *
 * Regras para nunca deixar o controlador preso numa transferência (ele trava esperando e só volta
 * reiniciando):
 * - todo pedido `C` recebe resposta. Sem arquivo para mandar (não achado, ou o app perdeu o
 *   estado), a resposta é o fim de arquivo: o LOAD termina vazio, e o app marca como falha;
 * - os bytes saem por uma fila única por conexão, na ordem em que foram pedidos;
 * - um bloco partido em dois pacotes de rede é juntado antes de ser lido;
 * - transferência parada há [TRANSFER_STALL_MS] é encerrada pelo app (LOAD: manda o fim de
 *   arquivo; SAVE: fecha o arquivo como incompleto);
 * - desconectar no meio de uma transferência fica adiado até ela terminar;
 * - se o controlador faz uma pergunta no meio da transferência (ex.: passo com erro de sintaxe:
 *   "(0:Change to comment and continue, 1:Delete program and abort)", ou o "Load?" do LOAD/Q),
 *   a transferência **não** é dada como parada: a pergunta vai para [getQuestion] e espera a
 *   resposta ([answerQuestion]). Sem resposta, o controlador fica preso de verdade.
 *
 * Uma única instância vive o app inteiro (criada em MyRobotsApp), então a
 * conexão continua aberta mesmo quando você troca de tela.
 */
class KawasakiTerminalManager(
    private val files: RobotFileStore
) {
    /**
     * Tempo sem bloco do robô para o app encerrar uma transferência parada (os testes do
     * protocolo usam um valor menor).
     */
    @Volatile var transferStallMs: Long = TRANSFER_STALL_MS

    /**
     * Guarda o estado da conexão de cada robô. A chave é o id do robô.
     */
    private val connections = ConcurrentHashMap<Int, ConnectionState>()
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
    private val pendingTransfers = ConcurrentHashMap<Int, PendingTransfer>()

    companion object {
        /** De quanto em quanto tempo a sondagem de heartbeat é enviada. */
        private const val HEARTBEAT_PING_INTERVAL_MS = 3000L
        /** Se passar esse tempo sem receber nada do robô, o heartbeat vira STALE. */
        private const val HEARTBEAT_STALE_AFTER_MS = 8000L
        /** Transferência sem nenhum bloco do robô por esse tempo é encerrada pelo app. */
        const val TRANSFER_STALL_MS = 30_000L
        /** Tamanho de cada pedaço do LOAD (o mesmo do KRterm). */
        private const val LOAD_CHUNK = 512
        /** Bloco sem o fim (0x17) maior que isso não é bloco: vira texto. */
        private const val MAX_PENDING_BLOCK = 64 * 1024

        private const val ENQ: Byte = 0x05
        private const val STX: Byte = 0x02
        private const val ETB: Byte = 0x17
        private const val EOF: Byte = 0x1A
        private const val IAC: Byte = 0xFF.toByte()
        private const val ESC: Byte = 0x1B
        private const val BS: Byte = 0x08
    }

    /**
     * Um envio pendente: nome do arquivo e o texto que será gravado/carregado no robô.
     */
    data class PendingTransfer(
        val fileName: String,
        val content: String
    )

    /**
     * Arquivo que o robô mandou com SAVE.
     * - fileName: o nome gravado na pasta; bytes: o tamanho; finishedAt: quando terminou (null =
     *   ainda recebendo); ok: false se o arquivo não pôde ser gravado ou ficou incompleto;
     *   message: o motivo quando não deu certo.
     */
    data class SaveEvent(
        val fileName: String,
        val bytes: Long,
        val finishedAt: Long?,
        val ok: Boolean = true,
        val message: String = ""
    )

    /**
     * Arquivo que o app mandou com LOAD (o robô pediu com o bloco A).
     * - total / sent: tamanho e quanto já foi; finishedAt: quando o robô mandou o fim (null =
     *   em andamento); ok: false se o arquivo não foi achado ou não foi inteiro; message: motivo.
     */
    data class LoadEvent(
        val fileName: String,
        val total: Int,
        val sent: Int,
        val finishedAt: Long?,
        val ok: Boolean,
        val message: String = "",
        /** Perguntas que o controlador fez no meio (erro no arquivo) e a resposta dada. */
        val problems: List<String> = emptyList()
    )

    /**
     * Pergunta do controlador no meio de uma transferência, esperando resposta.
     * - text: as últimas linhas (o erro e a pergunta); options: (tecla, texto) de cada opção;
     * - fileName: o arquivo da transferência.
     */
    data class ControllerQuestion(
        val text: String,
        val options: List<Pair<String, String>>,
        val fileName: String?,
        val at: Long
    )

    /** Arquivo deixado pronto para o próximo LOAD do robô (não depende da pasta). */
    class StagedLoad(val fileName: String, val bytes: ByteArray)

    /** O que está acontecendo no protocolo de arquivo. */
    enum class Transfer { NONE, SAVING, LOADING }

    /**
     * Tudo o que o app precisa lembrar sobre a conexão de UM robô.
     *
     * - socket / outbox / writerJob: a conexão e a fila única de saída (um escritor só, para
     *   os bytes nunca saírem fora de ordem).
     * - job: a tarefa que fica lendo o que o robô manda; rx: bytes de um bloco ou sequência
     *   que chegou partida e espera o resto.
     * - history: linhas do terminal (a tela observa e se atualiza sozinha).
     * - isConnected: true enquanto estiver conectado.
     * - transfer: SAVE ou LOAD em andamento; save / load: o último de cada um.
     * - robotName: nome do robô (define a pasta onde os arquivos são gravados).
     * - autoLogin / loginUser / loginPassword / loginStep: dados do login automático.
     * - heartbeat / lastActivityAt / heartbeatJob: pulso da conexão (ver HeartbeatState).
     */
    class ConnectionState {
        @Volatile var socket: Socket? = null
        @Volatile var outbox: Channel<ByteArray>? = null
        var writerJob: Job? = null
        var job: Job? = null
        val history: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
        val isConnected: MutableStateFlow<Boolean> = MutableStateFlow(false)
        val heartbeat: MutableStateFlow<HeartbeatState> = MutableStateFlow(HeartbeatState.DISCONNECTED)
        @Volatile var lastActivityAt: Long = 0L
        var heartbeatJob: Job? = null
        // Quantas vezes o prompt ">" apareceu no fim do terminal desde a conexão. Só aumenta:
        // o histórico guarda só as últimas linhas, então contar ">" nele falha depois de uma
        // saída longa (um SAVE/FULL tem milhares de linhas).
        val prompts: MutableStateFlow<Long> = MutableStateFlow(0L)
        var atPrompt: Boolean = false
        val rx = ByteArrayOutputStream()
        val transfer: MutableStateFlow<Transfer> = MutableStateFlow(Transfer.NONE)
        @Volatile var lastTransferAt: Long = 0L
        val save: MutableStateFlow<SaveEvent?> = MutableStateFlow(null)
        var saveFileOutputStream: OutputStream? = null
        val load: MutableStateFlow<LoadEvent?> = MutableStateFlow(null)
        var loadData: ByteArray? = null
        var loadOffset: Int = 0
        var loadEofSent: Boolean = false
        @Volatile var staged: StagedLoad? = null
        @Volatile var disconnectWhenIdle: Boolean = false
        val question: MutableStateFlow<ControllerQuestion?> = MutableStateFlow(null)
        var robotName: String = ""
        var autoLogin: Boolean = false
        var loginUser: String = ""
        var loginPassword: String = ""
        // Etapa do login automático: 0 = esperando "login:", 1 = esperando "password:",
        // 2 = terminou.
        var loginStep: Int = 0
    }

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

    /** Quantas vezes o prompt ">" voltou no terminal do robô (só aumenta). */
    fun getPromptCount(robotId: Int) = getOrCreateState(robotId).prompts.asStateFlow()

    /** SAVE em andamento (finishedAt = null) ou o último que terminou, do robô. */
    fun getSave(robotId: Int) = getOrCreateState(robotId).save.asStateFlow()

    /** LOAD em andamento (finishedAt = null) ou o último que terminou, do robô. */
    fun getLoad(robotId: Int) = getOrCreateState(robotId).load.asStateFlow()

    /** SAVE ou LOAD em andamento no robô (NONE = nenhum). */
    fun getTransfer(robotId: Int) = getOrCreateState(robotId).transfer.asStateFlow()

    /** Pergunta do controlador esperando resposta no meio de uma transferência (null = nenhuma). */
    fun getQuestion(robotId: Int) = getOrCreateState(robotId).question.asStateFlow()

    /**
     * Responde a pergunta do controlador com a tecla [key] (ex.: "0" ou "1") e Enter.
     */
    fun answerQuestion(robotId: Int, key: String) {
        val state = getOrCreateState(robotId)
        val q = state.question.value ?: return
        val label = q.options.firstOrNull { it.first == key }?.second ?: key
        state.question.value = null
        state.lastTransferAt = System.currentTimeMillis()
        state.load.value = state.load.value?.let { it.copy(problems = it.problems + "resposta: $key ($label)") }
        appendLog(robotId, "\n>>> Resposta à pergunta do controlador: $key ($label)\n")
        send(robotId, "$key\r\n".toByteArray(charset))
    }

    /** true se o robô está no meio de um SAVE ou LOAD. */
    fun isTransferring(robotId: Int) = getOrCreateState(robotId).transfer.value != Transfer.NONE

    /**
     * Deixa [bytes] prontos para o próximo LOAD de [fileName] neste robô. O robô pede o arquivo
     * pelo nome; se for este, ele sai daqui (da memória), sem depender da pasta do robô.
     */
    fun stageLoad(robotId: Int, fileName: String, bytes: ByteArray) {
        getOrCreateState(robotId).staged = StagedLoad(fileName, bytes)
    }

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
     * 3. Abre a conexão (espera no máximo 5 segundos) e a fila de saída.
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

        // já conectado, ou ainda tentando conectar (evita abrir duas sessões no robô)
        if (state.isConnected.value || state.job?.isActive == true) return
        state.disconnectWhenIdle = false

        state.job = scope.launch {
            var s: Socket? = null
            try {
                s = Socket()
                state.socket = s
                s.connect(InetSocketAddress(robot.ip, robot.port), 5000)
                s.tcpNoDelay = true
                val output = s.getOutputStream()
                val inputStream = s.getInputStream()

                // fila única de saída: um escritor só, na ordem em que os envios foram pedidos
                val outbox = Channel<ByteArray>(Channel.UNLIMITED)
                state.outbox = outbox
                state.writerJob = scope.launch {
                    for (bytes in outbox) {
                        try {
                            output.write(bytes)
                            output.flush()
                        } catch (e: Exception) {
                            break
                        }
                    }
                }
                synchronized(state.rx) { state.rx.reset() }
                state.atPrompt = false
                state.transfer.value = Transfer.NONE

                state.isConnected.value = true
                state.lastActivityAt = System.currentTimeMillis()
                state.heartbeat.value = HeartbeatState.ALIVE
                state.heartbeatJob = scope.launch { heartbeatLoop(robot.id) }

                // Negociação do telnet: avisa ao robô que sabemos informar o tipo de terminal
                // (bytes IAC, WILL, TERMINAL-TYPE).
                send(robot.id, byteArrayOf(IAC, 0xFB.toByte(), 0x18))

                if (!state.autoLogin) {
                    delay(300)
                    sendCommand(robot.id, "")
                }

                readLoop(robot.id, s, inputStream)
            } catch (e: Exception) {
                // só mexe no estado se esta tentativa ainda for a atual (não houve
                // disconnect + connect enquanto ela falhava)
                if (state.socket === s) {
                    appendLog(robot.id, "Erro: ${e.message}")
                    state.isConnected.value = false
                    state.heartbeat.value = HeartbeatState.DISCONNECTED
                    closeOutbox(state)
                }
            }
        }
    }

    /**
     * Fica em loop lendo o que o robô envia e passando para processBytes.
     * Termina quando a conexão cai ou é fechada; aí marca o robô como desconectado,
     * a não ser que já exista outra conexão no lugar desta (reconectou logo em seguida).
     */
    private suspend fun readLoop(robotId: Int, socket: Socket, inputStream: InputStream) {
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
        if (state.socket !== socket) return
        // a conexão caiu no meio de uma transferência: o controlador pode ter ficado esperando
        if (state.transfer.value != Transfer.NONE) {
            failTransfer(robotId, "a conexão caiu no meio da transferência")
            appendLog(robotId, "\n>>> A conexão caiu durante a transferência. Confira o controlador: ele pode ter ficado esperando o arquivo.\n")
        }
        state.isConnected.value = false
        state.heartbeat.value = HeartbeatState.DISCONNECTED
        state.heartbeatJob?.cancel()
        closeOutbox(state)
    }

    /**
     * Fica de olho no pulso do robô: se nada chega há mais de [HEARTBEAT_STALE_AFTER_MS], o
     * heartbeat vira STALE. Também encerra transferência parada há [TRANSFER_STALL_MS].
     */
    private suspend fun heartbeatLoop(robotId: Int) {
        val state = getOrCreateState(robotId)
        while (currentCoroutineContext().isActive && state.isConnected.value) {
            delay(HEARTBEAT_PING_INTERVAL_MS)

            val now = System.currentTimeMillis()
            val silence = now - state.lastActivityAt
            state.heartbeat.value = if (silence < HEARTBEAT_STALE_AFTER_MS) {
                HeartbeatState.ALIVE
            } else {
                HeartbeatState.STALE
            }
            // com pergunta pendente o robô não parou: está esperando a resposta
            if (state.question.value != null) state.lastTransferAt = now
            if (state.transfer.value != Transfer.NONE && now - state.lastTransferAt > transferStallMs) {
                stallTransfer(robotId)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Leitura
    // ---------------------------------------------------------------------------------------

    /**
     * Interpreta os bytes que chegaram do robô.
     *
     * O que é reconhecido:
     * - 0xFF: comando de negociação do telnet (3 bytes) -> é ignorado.
     * - 0x05 0x02 <tipo> ... 0x17: bloco do protocolo de transferência de
     *   arquivos -> vai para handleHandshakeBlock.
     * - 0x08: apagar o último caractere (backspace).
     * - ESC [ K  e  ESC [ nD: comandos de tela (limpar fim da linha / voltar cursor).
     * - Qualquer outra coisa: texto normal, mostrado no terminal.
     *
     * Um bloco ou sequência que chega partido (o resto vem no próximo pacote de rede) fica
     * guardado em `rx` e é lido quando o resto chegar.
     */
    internal fun processBytes(robotId: Int, data: ByteArray, length: Int) {
        val state = getOrCreateState(robotId)
        val buffer: ByteArray
        synchronized(state.rx) {
            if (state.rx.size() > 0) {
                state.rx.write(data, 0, length)
                buffer = state.rx.toByteArray()
                state.rx.reset()
            } else {
                buffer = data.copyOf(length)
            }
        }
        val end = buffer.size
        var i = 0

        fun keepRest(from: Int) = synchronized(state.rx) { state.rx.write(buffer, from, end - from) }

        while (i < end) {
            val byte = buffer[i]

            if (byte == IAC) {
                if (i + 2 >= end) { keepRest(i); return }
                i += 3
                continue
            }

            if (byte == ENQ) {
                if (i + 1 >= end) { keepRest(i); return }
                if (buffer[i + 1] != STX) { i++; continue }
                if (i + 2 >= end) { keepRest(i); return }
                var etbPos = -1
                for (j in i + 3 until end) {
                    if (buffer[j] == ETB) { etbPos = j; break }
                }
                if (etbPos == -1) {
                    if (end - i <= MAX_PENDING_BLOCK) { keepRest(i); return }
                    i++   // grande demais para ser um bloco: segue como texto
                    continue
                }
                val blockType = buffer[i + 2].toInt().toChar()
                val content = buffer.copyOfRange(i + 3, etbPos)
                handleHandshakeBlock(robotId, blockType, content)
                i = etbPos + 1
                continue
            }

            // Backspace (0x08): apaga o último caractere que está na tela
            if (byte == BS) {
                removeLastChar(robotId)
                i++
                continue
            }

            // Sequências de controle de tela (ESC [ ...): limpar fim da linha ou voltar o cursor
            if (byte == ESC) {
                if (i + 2 >= end) { keepRest(i); return }
                if (buffer[i + 1] == '['.code.toByte()) {
                    val code = buffer[i + 2].toInt().toChar()
                    if (code == 'K') {
                        clearLastLineEnd(robotId)
                        i += 3
                        continue
                    }
                    if (code.isDigit()) {
                        if (i + 3 >= end) { keepRest(i); return }
                        if (buffer[i + 3] == 'D'.code.toByte()) {
                            repeat(code.digitToInt()) { removeLastChar(robotId) }
                            i += 4
                            continue
                        }
                    }
                }
                i++   // ESC que não conhecemos: ignora só ele
                continue
            }

            // Texto comum: junta tudo até o próximo byte especial e escreve no terminal.
            // Se o login automático ainda não terminou, olha a última linha para ver se
            // o robô pediu usuário ou senha.
            val start = i
            while (i < end && buffer[i] != ENQ && buffer[i] != IAC && buffer[i] != ESC && buffer[i] != BS) i++
            val chunk = String(buffer, start, i - start, charset)

            appendLog(robotId, chunk)
            if (state.transfer.value != Transfer.NONE) detectQuestion(robotId)

            if (state.autoLogin && state.loginStep < 2) {
                val lastLine = state.history.value.lastOrNull() ?: ""
                handleAutoLoginMonitoring(robotId, lastLine)
            }
        }
    }

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

    private suspend fun sendStringCharByChar(robotId: Int, text: String) {
        text.forEach { char ->
            send(robotId, char.toString().toByteArray(charset))
            delay(50)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Protocolo de arquivo
    // ---------------------------------------------------------------------------------------

    /**
     * Um bloco do protocolo de arquivo (ver o comentário da classe). Todo pedido do robô
     * recebe resposta, mesmo quando o app não tem o que mandar.
     */
    private fun handleHandshakeBlock(robotId: Int, type: Char, content: ByteArray) {
        val state = getOrCreateState(robotId)
        state.lastTransferAt = System.currentTimeMillis()
        state.question.value = null   // o robô seguiu: a pergunta (se havia) foi respondida
        // bloco do protocolo também é sinal de vida (o texto do SAVE não vai mais para a tela)
        state.lastActivityAt = state.lastTransferAt
        if (state.isConnected.value) state.heartbeat.value = HeartbeatState.ALIVE
        when (type) {
            'B' -> {
                val fileName = String(content, charset).trim()
                startSaveFile(robotId, if (fileName.isNotEmpty()) fileName else "backup.as")
                sendHandshakeResponse(robotId, 'B')
            }
            'A' -> {
                val fileName = String(content, charset).trim()
                prepareLoadFile(robotId, fileName)
                sendHandshakeResponse(robotId, 'A')
            }
            'D' -> {
                if (state.transfer.value == Transfer.SAVING) {
                    try {
                        state.saveFileOutputStream?.write(content)
                    } catch (e: Exception) {
                        state.save.value = state.save.value?.copy(ok = false, message = "erro ao gravar: ${e.message}")
                    }
                    state.save.value = state.save.value?.let { it.copy(bytes = it.bytes + content.size) }
                }
            }
            'C' -> sendDataBlock(robotId)
            'E' -> {
                sendHandshakeResponse(robotId, 'E')
                when (state.transfer.value) {
                    Transfer.SAVING -> stopSaveFile(robotId)
                    Transfer.LOADING -> finishLoad(robotId)
                    Transfer.NONE -> {}
                }
                state.transfer.value = Transfer.NONE
                afterTransfer(robotId)
            }
        }
    }

    /**
     * Abre o arquivo (na pasta do robô, via RobotFileStore) onde será gravado o que o robô
     * enviar. Se falhar, avisa no terminal; os blocos do robô continuam sendo respondidos.
     *
     * O nome vem do controlador (rede): só é aceito se for um nome simples (TransferFileNames).
     */
    private fun startSaveFile(robotId: Int, fileName: String) {
        val state = getOrCreateState(robotId)
        state.transfer.value = Transfer.SAVING
        val safeName = TransferFileNames.safeName(fileName)
        if (safeName == null) {
            state.saveFileOutputStream = null
            state.save.value = SaveEvent(fileName.take(60), 0, null, ok = false, message = "nome de arquivo inválido")
            appendLog(robotId, "\n>>> SAVE recusado: nome de arquivo inválido vindo do robô (\"${fileName.take(60)}\")\n")
            return
        }
        try {
            state.saveFileOutputStream = files.openOutput(state.robotName, safeName)
            state.save.value = SaveEvent(safeName, 0, null)
            appendLog(robotId, "\n>>> Recebendo $safeName…\n")
        } catch (e: Exception) {
            state.saveFileOutputStream = null
            state.save.value = SaveEvent(safeName, 0, null, ok = false, message = "não abriu o arquivo: ${e.message}")
            appendLog(robotId, "\n>>> Erro ao gravar $safeName: ${e.message}\n")
        }
    }

    /**
     * Termina a gravação: fecha o arquivo e marca o SAVE como terminado.
     */
    private fun stopSaveFile(robotId: Int, failure: String? = null) {
        val state = getOrCreateState(robotId)
        try { state.saveFileOutputStream?.close() } catch (e: Exception) {}
        state.saveFileOutputStream = null
        state.save.value = state.save.value?.let { ev ->
            val ok = ev.ok && failure == null
            ev.copy(finishedAt = System.currentTimeMillis(), ok = ok, message = failure ?: ev.message)
        }
        state.save.value?.let { ev ->
            appendLog(robotId, if (ev.ok) "\n>>> ${ev.fileName} recebido (${ev.bytes / 1024} KB)\n"
            else "\n>>> ${ev.fileName} incompleto: ${ev.message}\n")
        }
    }

    /**
     * O robô pediu um arquivo (LOAD). Procura primeiro o que foi deixado pronto com
     * [stageLoad] e depois a pasta do robô. Sem arquivo, o LOAD segue vazio (o robô recebe só
     * o fim de arquivo e não trava) e fica marcado como falha.
     */
    private fun prepareLoadFile(robotId: Int, fileName: String) {
        val state = getOrCreateState(robotId)
        state.transfer.value = Transfer.LOADING
        state.loadOffset = 0
        state.loadEofSent = false

        val staged = state.staged?.takeIf { sameFile(it.fileName, fileName) }
        if (staged != null) state.staged = null
        val safeName = TransferFileNames.safeName(fileName)
        val data = staged?.bytes ?: safeName?.let { files.read(state.robotName, it) }
        state.loadData = data ?: ByteArray(0)
        state.load.value = when {
            data != null -> LoadEvent(fileName, data.size, 0, null, ok = true)
            safeName == null -> LoadEvent(fileName, 0, 0, null, ok = false, message = "nome de arquivo inválido")
            else -> LoadEvent(fileName, 0, 0, null, ok = false, message = "arquivo não encontrado no celular")
        }
        if (data == null) {
            appendLog(robotId, "\n>>> LOAD: ${state.load.value?.message} (\"${fileName.take(60)}\"). O robô recebe um arquivo vazio para não ficar esperando.\n")
        }
    }

    /**
     * Responde a um pedido C do robô com o próximo pedaço (até 512 bytes) ou com o fim de
     * arquivo (0x1A). Também responde quando não há LOAD em andamento: um pedido sem
     * resposta deixa o controlador preso.
     */
    private fun sendDataBlock(robotId: Int) {
        val state = getOrCreateState(robotId)
        val header = byteArrayOf(STX, 'C'.code.toByte(), 0x20, 0x20, 0x20, 0x20, 0x30)
        if (state.transfer.value != Transfer.LOADING) {
            appendLog(robotId, "\n>>> O robô pediu dados sem um LOAD em andamento: respondido com fim de arquivo.\n")
            send(robotId, header + byteArrayOf(EOF, ETB))
            return
        }
        val data = state.loadData ?: ByteArray(0)
        val remaining = data.size - state.loadOffset
        if (remaining <= 0) {
            state.loadEofSent = true
            send(robotId, header + byteArrayOf(EOF, ETB))
            return
        }
        val size = minOf(LOAD_CHUNK, remaining)
        val chunk = data.copyOfRange(state.loadOffset, state.loadOffset + size)
        state.loadOffset += size
        state.load.value = state.load.value?.copy(sent = state.loadOffset)
        send(robotId, header + chunk + byteArrayOf(ETB))
    }

    /** O robô mandou o fim (E) de um LOAD: confere se o arquivo foi inteiro. */
    private fun finishLoad(robotId: Int, failure: String? = null) {
        val state = getOrCreateState(robotId)
        state.load.value = state.load.value?.let { ev ->
            val complete = ev.sent >= ev.total && state.loadEofSent
            val reason = failure ?: when {
                !ev.ok -> ev.message
                ev.problems.isNotEmpty() -> "o controlador achou erro no arquivo: " + ev.problems.joinToString(" · ")
                !complete -> "o robô encerrou antes do fim (${ev.sent} de ${ev.total} bytes)"
                else -> ""
            }
            ev.copy(finishedAt = System.currentTimeMillis(), ok = reason.isEmpty(), message = reason)
        }
        state.loadData = null
        state.loadOffset = 0
    }

    /** A conexão caiu ou a transferência parou: marca o que estava em andamento como falha. */
    private fun failTransfer(robotId: Int, reason: String) {
        val state = getOrCreateState(robotId)
        when (state.transfer.value) {
            Transfer.SAVING -> stopSaveFile(robotId, reason)
            Transfer.LOADING -> finishLoad(robotId, reason)
            Transfer.NONE -> {}
        }
        state.transfer.value = Transfer.NONE
        state.question.value = null
    }

    /**
     * Transferência sem nenhum bloco do robô há [TRANSFER_STALL_MS]. No LOAD, manda o fim de
     * arquivo (se o robô estiver esperando dados, ele termina); no SAVE, fecha o arquivo como
     * incompleto. Depois disso o app não está mais "no meio" de nada.
     */
    private fun stallTransfer(robotId: Int) {
        val state = getOrCreateState(robotId)
        if (state.transfer.value == Transfer.LOADING) {
            send(robotId, byteArrayOf(STX, 'C'.code.toByte(), 0x20, 0x20, 0x20, 0x20, 0x30, EOF, ETB))
        }
        appendLog(robotId, "\n>>> Transferência parada há ${transferStallMs / 1000} s: encerrada pelo app.\n")
        failTransfer(robotId, "a transferência parou (sem resposta do robô)")
        afterTransfer(robotId)
    }

    /** Depois de uma transferência: faz a desconexão que tinha sido adiada. */
    private fun afterTransfer(robotId: Int) {
        val state = getOrCreateState(robotId)
        if (state.disconnectWhenIdle) {
            scope.launch {
                delay(800)   // deixa chegar o "File ... completed." e o prompt
                disconnect(robotId, force = true)
            }
        }
    }

    /**
     * Olha o fim do terminal no meio de uma transferência: se a última linha é uma pergunta com
     * opções numeradas, guarda a pergunta (uma vez) para quem for responder.
     * Formatos: "(0:Change to comment and continue, 1:Delete program and abort)",
     * "(1:Yes, 0:No, 2:Load all, 3:Exit)" e "(Yes:1, No:0)".
     */
    private fun detectQuestion(robotId: Int) {
        val state = getOrCreateState(robotId)
        val lines = state.history.value.takeLast(12).map { it.trim() }.filter { it.isNotEmpty() }
        val last = lines.lastOrNull() ?: return
        val options = parseOptions(last)
        if (options.size < 2) return
        val text = lines.takeLast(6).joinToString("\n")
        if (state.question.value?.text == text) return
        val fileName = state.load.value?.takeIf { state.transfer.value == Transfer.LOADING }?.fileName
            ?: state.save.value?.fileName
        state.question.value = ControllerQuestion(text, options, fileName, System.currentTimeMillis())
        state.load.value = state.load.value?.let { ev ->
            val problem = lines.dropLast(1).takeLast(4)
                .filter { it.contains("(P") || it.contains("error", ignoreCase = true) }
                .joinToString(" ").ifBlank { last }
            ev.copy(problems = ev.problems + problem)
        }
        state.lastTransferAt = System.currentTimeMillis()
    }

    /** Opções de uma pergunta do controlador: (tecla, texto). Vazio se não for pergunta. */
    private fun parseOptions(line: String): List<Pair<String, String>> {
        val inside = Regex("""\(([^()]*\d[^()]*)\)\s*$""").find(line)?.groupValues?.get(1) ?: return emptyList()
        val keyFirst = Regex("""(\d+)\s*:\s*([^,]+)""").findAll(inside).map { it.groupValues[1] to it.groupValues[2].trim() }.toList()
        if (keyFirst.size >= 2) return keyFirst
        return Regex("""([A-Za-z][\w ]*?)\s*:\s*(\d+)""").findAll(inside).map { it.groupValues[2] to it.groupValues[1].trim() }.toList()
    }

    /** Mesmo arquivo? Sem diferença de maiúsculas e com ".as" quando não há extensão. */
    private fun sameFile(a: String, b: String): Boolean {
        fun norm(n: String) = n.trim().lowercase().let { if ('.' in it) it else "$it.as" }
        return norm(a) == norm(b)
    }

    /**
     * Manda ao robô a confirmação padrão do protocolo para o tipo de bloco informado (A, B ou E).
     */
    private fun sendHandshakeResponse(robotId: Int, type: Char) {
        send(robotId, byteArrayOf(STX, type.code.toByte(), 0x20, 0x20, 0x20, 0x20, 0x30, ETB))
    }

    // ---------------------------------------------------------------------------------------
    // Saída
    // ---------------------------------------------------------------------------------------

    /** Põe bytes na fila de saída do robô. Sem conexão, não faz nada. */
    private fun send(robotId: Int, bytes: ByteArray) {
        connections[robotId]?.outbox?.trySend(bytes)
    }

    private fun closeOutbox(state: ConnectionState) {
        state.outbox?.close()
        state.outbox = null
    }

    // ---------------------------------------------------------------------------------------
    // Histórico
    // ---------------------------------------------------------------------------------------

    /**
     * Acrescenta texto ao histórico do terminal (quebrando nas linhas) e conta o prompt quando
     * ele volta.
     */
    fun appendLog(robotId: Int, text: String) {
        val state = getOrCreateState(robotId)
        val cleanText = text.replace(Regex("[\\x00-\\x07\\x0B\\x0E-\\x1F]"), "")
        if (cleanText.isEmpty() && !text.contains("\n") && !text.contains("\r")) return

        // chegou algo de verdade do robô: o heartbeat volta a ficar ALIVE
        state.lastActivityAt = System.currentTimeMillis()
        if (state.isConnected.value) state.heartbeat.value = HeartbeatState.ALIVE

        synchronized(state.history) {
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

            // o prompt voltou (a última linha virou ">" e antes não era): conta mais um
            val nowAtPrompt = currentHistory.lastOrNull()?.trim() == ">"
            if (nowAtPrompt && !state.atPrompt) state.prompts.value += 1
            state.atPrompt = nowAtPrompt
        }
    }

    private fun removeLastChar(robotId: Int) {
        val state = getOrCreateState(robotId)
        synchronized(state.history) {
            val currentHistory = state.history.value.toMutableList()
            if (currentHistory.isNotEmpty()) {
                val lastLine = currentHistory.last()
                if (lastLine.isNotEmpty()) {
                    currentHistory[currentHistory.size - 1] = lastLine.dropLast(1)
                    state.history.value = currentHistory
                }
            }
        }
    }

    /**
     * Apaga o final da última linha do terminal (efeito de ESC [ K).
     * Se a linha tem o prompt ">", mantém o texto até o ">"; senão, limpa a linha toda.
     */
    private fun clearLastLineEnd(robotId: Int) {
        val state = getOrCreateState(robotId)
        synchronized(state.history) {
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
    }

    /**
     * Limpa todo o texto do terminal desse robô.
     */
    fun clearLog(robotId: Int) {
        val state = getOrCreateState(robotId)
        state.history.value = emptyList()
    }

    // ---------------------------------------------------------------------------------------
    // Comandos
    // ---------------------------------------------------------------------------------------

    /**
     * Envia UMA tecla ao robô, sem apertar Enter (usado enquanto você digita).
     * Teclas especiais: "\b" = apagar, "RECALL" = repetir comando, "UP"/"DOWN" = setas.
     */
    fun sendChar(robotId: Int, char: String) {
        val bytes = when (char) {
            "\b" -> byteArrayOf(BS)
            "RECALL" -> byteArrayOf(0x0C)
            "UP" -> byteArrayOf(ESC, '['.code.toByte(), 'A'.code.toByte())
            "DOWN" -> byteArrayOf(ESC, '['.code.toByte(), 'B'.code.toByte())
            else -> char.toByteArray(charset)
        }
        send(robotId, bytes)
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
        val state = connections[robotId] ?: return
        if (state.outbox == null) return
        // mandou uma linha: o próximo ">" é um prompt novo, mesmo que o eco e o prompt cheguem
        // juntos e a última linha continue ">" (acontece com o Enter vazio)
        if (command != "SPACE") synchronized(state.history) { state.atPrompt = false }
        when {
            command == "SPACE" -> send(robotId, byteArrayOf(0x20))
            isManualFinalize -> send(robotId, "\r\n".toByteArray(charset))
            else -> {
                if (command.isNotEmpty()) appendLog(robotId, "\n> $command")
                send(robotId, "$command\r\n".toByteArray(charset))
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
     * Com clearHistory = true, também apaga o histórico do terminal.
     *
     * No meio de um SAVE ou LOAD, a desconexão fica **adiada** até a transferência terminar
     * (fechar a conexão no meio deixa o controlador esperando o arquivo). [force] fecha na hora.
     *
     * O estado do robô nunca sai do mapa `connections`: as telas guardam os fluxos dele
     * (isConnected, history, heartbeat) uma vez só. Se ele fosse trocado por um novo, a
     * próxima conexão aconteceria num estado que nenhuma tela observa (o botão não mudava
     * ao reconectar).
     */
    fun disconnect(robotId: Int, clearHistory: Boolean = false, force: Boolean = false) {
        val state = connections[robotId] ?: return
        if (!force && state.transfer.value != Transfer.NONE && state.isConnected.value) {
            if (!state.disconnectWhenIdle) {
                state.disconnectWhenIdle = true
                appendLog(robotId, "\n>>> Transferência em andamento: desconecta quando ela terminar.\n")
            }
            return
        }
        state.disconnectWhenIdle = false
        if (state.transfer.value != Transfer.NONE) failTransfer(robotId, "desconectado no meio da transferência")
        state.question.value = null
        state.job?.cancel()
        state.heartbeatJob?.cancel()
        try { state.socket?.close() } catch (e: Exception) {}
        try { state.saveFileOutputStream?.close() } catch (e: Exception) {}
        closeOutbox(state)
        state.writerJob?.cancel()
        state.socket = null
        state.job = null
        state.heartbeatJob = null
        state.writerJob = null
        state.saveFileOutputStream = null
        state.loadData = null
        state.loadOffset = 0
        state.staged = null
        state.transfer.value = Transfer.NONE
        synchronized(state.rx) { state.rx.reset() }
        state.isConnected.value = false
        state.heartbeat.value = HeartbeatState.DISCONNECTED
        if (clearHistory) {
            state.history.value = emptyList()
        }
    }
}
