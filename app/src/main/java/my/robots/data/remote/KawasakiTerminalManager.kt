package my.robots.data.remote

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import my.robots.data.model.Robot
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset

class KawasakiTerminalManager(private val context: Context) {
    private val connections = mutableMapOf<Int, ConnectionState>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val charset = Charset.forName("ISO-8859-1")
    
    // Armazena transferências que devem ocorrer assim que o robô conectar
    private val pendingTransfers = mutableMapOf<Int, PendingTransfer>()

    data class PendingTransfer(
        val fileName: String,
        val content: String
    )

    private fun getRobotDir(robotName: String): File {
        val root = Environment.getExternalStorageDirectory()
        val myRobotsDir = File(root, "MyRobots")
        val robotDir = File(myRobotsDir, robotName.lowercase().replace(" ", "_"))
        if (!robotDir.exists()) robotDir.mkdirs()
        return robotDir
    }

    data class ConnectionState(
        var socket: Socket? = null,
        var outputStream: OutputStream? = null,
        var job: Job? = null,
        val history: MutableStateFlow<List<String>> = MutableStateFlow(emptyList()),
        val isConnected: MutableStateFlow<Boolean> = MutableStateFlow(false),
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
        var loginStep: Int = 0 
    )

    private fun getOrCreateState(robotId: Int) = connections.getOrPut(robotId) { ConnectionState() }

    fun getHistory(robotId: Int) = getOrCreateState(robotId).history.asStateFlow()
    fun getConnectionStatus(robotId: Int) = getOrCreateState(robotId).isConnected.asStateFlow()

    fun setPendingTransfer(robotId: Int, fileName: String, content: String) {
        pendingTransfers[robotId] = PendingTransfer(fileName, content)
    }

    fun getPendingTransfer(robotId: Int): PendingTransfer? {
        return pendingTransfers[robotId]
    }

    fun clearPendingTransfer(robotId: Int) {
        pendingTransfers.remove(robotId)
    }

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
                
                sendRawDirect(robot.id, byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x18.toByte()))
                
                if (!state.autoLogin) {
                    delay(300)
                    sendCommand(robot.id, "")
                }
                
                readLoop(robot.id, inputStream)
            } catch (e: Exception) {
                appendLog(robot.id, "Erro: ${e.message}")
                state.isConnected.value = false
            }
        }
    }

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
    }

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

            // Tratamento de Backspace direto (0x08)
            if (byte == 0x08.toByte()) {
                removeLastChar(robotId)
                i++
                continue
            }

            // ANSI Escape Sequences
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
            sendRawDirect(robotId, char.toString().toByteArray(charset))
            delay(50)
        }
    }

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

    private fun stopSaveFile(robotId: Int) {
        val state = getOrCreateState(robotId)
        state.isSaving = false
        try { state.saveFileOutputStream?.close() } catch (e: Exception) {}
        state.saveFileOutputStream = null
    }

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

    private fun sendHandshakeResponse(robotId: Int, type: Char) {
        val response = byteArrayOf(0x02.toByte(), type.code.toByte(), 0x20, 0x20, 0x20, 0x20, 0x30, 0x17.toByte())
        sendRaw(robotId, response)
    }

    private fun sendRaw(robotId: Int, bytes: ByteArray) {
        connections[robotId]?.outputStream?.let { out ->
            scope.launch { try { out.write(bytes); out.flush() } catch (e: Exception) {} }
        }
    }

    fun appendLog(robotId: Int, text: String) {
        val state = getOrCreateState(robotId)
        val cleanText = text.replace(Regex("[\\x00-\\x07\\x0B\\x0E-\\x1F]"), "")
        if (cleanText.isEmpty() && !text.contains("\n") && !text.contains("\r")) return

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

    fun clearLog(robotId: Int) {
        val state = getOrCreateState(robotId)
        state.history.value = emptyList()
    }

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
     * Deleta programas no robô usando o comando DELETE.
     * @param onlyProgram Se true, usa /P para deletar apenas o programa (sem sub-rotinas/variáveis locais)
     * @param forced Se true, usa /D para forçar a deleção mesmo que usado em outros programas
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
     * Deleta variáveis no robô usando as variantes do comando DELETE (/L, /R, /S, /INT).
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

    fun disconnect(robotId: Int, clearHistory: Boolean = false) {
        val state = connections[robotId] ?: return
        state.job?.cancel()
        try { state.socket?.close(); state.outputStream?.close(); state.saveFileOutputStream?.close() } catch (e: Exception) {}
        state.socket = null
        state.outputStream = null
        state.isConnected.value = false
        if (clearHistory) {
            state.history.value = emptyList()
            connections.remove(robotId)
        }
    }
}
