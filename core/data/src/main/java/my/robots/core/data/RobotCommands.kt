package my.robots.core.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import my.robots.core.common.ascode.AsControllerReplies
import my.robots.core.network.KawasakiTerminalManager

/**
 * Resultado de um LOAD feito pelo app.
 * - ok: só true com tudo conferido (ver [RobotCommands.loadFile]);
 * - message: o que aconteceu, em poucas palavras, para a tela;
 * - errors: o N de "File load completed. (N errors)", quando apareceu;
 * - sent / total: bytes enviados e tamanho do arquivo.
 */
data class LoadResult(val ok: Boolean, val message: String, val errors: Int? = null, val sent: Int = 0, val total: Int = 0)

/**
 * Resultado de um SAVE pedido pelo app.
 * - fileName: o arquivo gravado na pasta do robô (null se o robô não mandou nenhum);
 * - bytes: o tamanho recebido.
 */
data class SaveResult(val ok: Boolean, val message: String, val fileName: String? = null, val bytes: Long = 0)

/**
 * Conversa com o robô em vários passos: mandar um comando e esperar o prompt voltar, LOAD e
 * SAVE com o resultado conferido. Uma trava por robô ([lock]) garante que só uma dessas
 * conversas acontece por vez em cada robô; as checagens do login (ControllerChecks) usam a
 * mesma trava.
 *
 * Não depende do Android, para rodar nos testes do protocolo (tools/protocolo).
 */
class RobotCommands(private val terminal: KawasakiTerminalManager) {

    companion object {
        /** Espera do prompt em cada tentativa da confirmação de estado. */
        private const val CONFIRM_TIMEOUT_MS = 5_000L
    }

    private val locks = mutableMapOf<Int, Mutex>()

    /** Trava do robô: quem segura manda comandos sem misturar respostas com outros. */
    fun lock(robotId: Int): Mutex = synchronized(locks) { locks.getOrPut(robotId) { Mutex() } }

    /**
     * Espera o prompt ">" aparecer no fim do terminal (robô logado e livre). Devolve false se
     * a conexão cair ou o prompt não aparecer em [timeoutMs].
     */
    suspend fun awaitPrompt(robotId: Int, timeoutMs: Long): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            if (!terminal.getConnectionStatus(robotId).value) return false
            val last = terminal.getHistory(robotId).value.lastOrNull { it.isNotBlank() }?.trim()
            if (last == ">" && !terminal.isTransferring(robotId)) return true
            delay(250)
            waited += 250
        }
        return false
    }

    /**
     * Manda um comando e espera o controlador voltar ao prompt ">" (contador de prompts do
     * terminal, que só aumenta). Devolve false se o robô cair ou o prompt não voltar em
     * [timeoutMs] (o controlador pode ter feito uma pergunta: ver o terminal).
     */
    suspend fun sendAndAwaitPrompt(robotId: Int, command: String, timeoutMs: Long = 60_000): Boolean =
        lock(robotId).withLock { sendAndAwaitPromptLocked(robotId, command, timeoutMs) }

    /** Igual a [sendAndAwaitPrompt], para quem já segura a trava do robô. */
    suspend fun sendAndAwaitPromptLocked(robotId: Int, command: String, timeoutMs: Long): Boolean {
        val prompts = terminal.getPromptCount(robotId)
        val before = prompts.value
        terminal.sendCommand(robotId, command)
        var waited = 0L
        while (waited < timeoutMs) {
            if (!terminal.getConnectionStatus(robotId).value) return false
            // o prompt pode voltar enquanto um bloco do protocolo ainda está sendo respondido
            if (prompts.value > before && !terminal.isTransferring(robotId)) return true
            delay(200)
            waited += 200
        }
        return false
    }

    /**
     * LOAD de [content] com o nome [fileName]. O arquivo sai da memória (stageLoad), não da
     * pasta. Só dá certo com as quatro provas: o robô pediu este arquivo, ele foi inteiro, o
     * robô fechou a transferência e respondeu "File load completed. (0 errors)". O texto
     * "0 errors" sozinho não basta: o controlador diz isso também quando recebe um arquivo
     * vazio.
     */
    suspend fun loadFile(
        robotId: Int,
        fileName: String,
        content: String,
        timeoutMs: Long = 5 * 60_000L,
        answer: (suspend (KawasakiTerminalManager.ControllerQuestion) -> String?)? = null
    ): LoadResult {
        if (!terminal.getConnectionStatus(robotId).value) return LoadResult(false, "Robô desconectado")
        val bytes = content.toByteArray(Charsets.ISO_8859_1)
        return lock(robotId).withLock {
            confirmReadyLocked(robotId)?.let { return@withLock LoadResult(false, "Antes do LOAD: $it") }
            terminal.stageLoad(robotId, fileName, bytes)
            val before = terminal.getLoad(robotId).value
            val command = "LOAD $fileName"
            val prompted = awaitWithQuestions(robotId, command, timeoutMs, answer)
            val event = terminal.getLoad(robotId).value?.takeIf { it !== before }
            val reply = replyAfter(robotId, command)
            val errors = AsControllerReplies.parseLoadErrors(reply)
            when {
                event == null && !prompted -> LoadResult(false, "Sem resposta ao LOAD: veja o terminal")
                event == null -> LoadResult(false, "O robô não pediu o arquivo" + firstLine(reply)?.let { ": $it" }.orEmpty())
                event.finishedAt == null && terminal.getQuestion(robotId).value != null ->
                    LoadResult(false, "O controlador está esperando a resposta a uma pergunta", errors, event.sent, event.total)
                event.finishedAt == null -> LoadResult(false, "A transferência não terminou (${event.sent} de ${event.total} bytes)", errors, event.sent, event.total)
                !event.ok -> LoadResult(false, "LOAD falhou: ${event.message}", errors, event.sent, event.total)
                !prompted -> LoadResult(false, "O robô não voltou ao prompt depois do LOAD", errors, event.sent, event.total)
                errors == null -> LoadResult(false, "Sem a confirmação \"File load completed\": veja o terminal", null, event.sent, event.total)
                errors > 0 -> LoadResult(false, "LOAD com $errors erro(s): veja o terminal", errors, event.sent, event.total)
                else -> LoadResult(true, "Carregado: ${event.total} bytes, 0 erros", 0, event.sent, event.total)
            }
        }
    }

    /**
     * SAVE: manda [command] (ex.: "SAVE/FULL R10_20261003_0800") e espera o arquivo chegar
     * inteiro e o prompt voltar. [expectedName] é o começo do nome que o robô deve mandar.
     */
    suspend fun saveFile(robotId: Int, command: String, expectedName: String, timeoutMs: Long = 15 * 60_000L): SaveResult {
        if (!terminal.getConnectionStatus(robotId).value) return SaveResult(false, "Robô desconectado")
        return lock(robotId).withLock {
            confirmReadyLocked(robotId)?.let { return@withLock SaveResult(false, "Antes do SAVE: $it") }
            val before = terminal.getSave(robotId).value
            val prompted = sendAndAwaitPromptLocked(robotId, command, timeoutMs)
            val save = terminal.getSave(robotId).value?.takeIf { it !== before }
            val reply = replyAfter(robotId, command)
            when {
                save == null && !prompted -> SaveResult(false, "SAVE sem resposta: veja o terminal")
                save == null -> SaveResult(false, "O robô não mandou o arquivo" + firstLine(reply)?.let { ": $it" }.orEmpty())
                !save.fileName.startsWith(expectedName, ignoreCase = true) ->
                    SaveResult(false, "O robô mandou outro arquivo (${save.fileName})", save.fileName, save.bytes)
                save.finishedAt == null -> SaveResult(false, "O arquivo não terminou de chegar", save.fileName, save.bytes)
                !save.ok -> SaveResult(false, "SAVE falhou: ${save.message}", save.fileName, save.bytes)
                !reply.contains("File save completed", ignoreCase = true) ->
                    SaveResult(false, "Sem a confirmação \"File save completed\": veja o terminal", save.fileName, save.bytes)
                else -> SaveResult(true, "${save.fileName} · ${save.bytes / 1024} KB", save.fileName, save.bytes)
            }
        }
    }

    /**
     * Manda [command] e espera o prompt voltar, como [sendAndAwaitPromptLocked], mas atento às
     * perguntas do controlador no meio da transferência: com [answer], responde; sem ele, a
     * pergunta fica para a tela (ControllerChecks mostra a todos) e aqui só se espera.
     */
    private suspend fun awaitWithQuestions(
        robotId: Int,
        command: String,
        timeoutMs: Long,
        answer: (suspend (KawasakiTerminalManager.ControllerQuestion) -> String?)?
    ): Boolean {
        val prompts = terminal.getPromptCount(robotId)
        val before = prompts.value
        terminal.sendCommand(robotId, command)
        var waited = 0L
        while (waited < timeoutMs) {
            if (!terminal.getConnectionStatus(robotId).value) return false
            val question = terminal.getQuestion(robotId).value
            if (question != null && answer != null) {
                answer(question)?.let { terminal.answerQuestion(robotId, it) }
            }
            if (prompts.value > before && !terminal.isTransferring(robotId)) return true
            delay(200)
            waited += 200
        }
        return false
    }

    /**
     * Confirmação de estado antes de mandar dados (LOAD ou SAVE), para quem já segura a trava:
     * o robô está conectado, sem transferência em andamento, sem pergunta do controlador
     * esperando resposta e responde a um Enter voltando ao prompt ">" (duas tentativas). O Enter
     * também encerra uma pergunta "Change?" que tenha ficado aberta (ex.: TIME das checagens).
     * Devolve null se estiver pronto, ou o motivo.
     */
    suspend fun confirmReadyLocked(robotId: Int): String? {
        if (!terminal.getConnectionStatus(robotId).value) return "robô desconectado"
        if (terminal.isTransferring(robotId)) return "já há uma transferência em andamento"
        if (terminal.getQuestion(robotId).value != null) return "o controlador está esperando a resposta a uma pergunta"
        repeat(2) {
            if (sendAndAwaitPromptLocked(robotId, "", CONFIRM_TIMEOUT_MS)) return null
        }
        return if (!terminal.getConnectionStatus(robotId).value) "a conexão caiu"
        else "o controlador não voltou ao prompt (pode estar ocupado ou esperando uma resposta: veja o terminal)"
    }

    /** Texto que chegou depois do comando (as linhas abaixo da última que o contém). */
    private fun replyAfter(robotId: Int, command: String): String {
        val lines = terminal.getHistory(robotId).value.takeLast(200)
        val idx = lines.indexOfLast { it.contains(command, ignoreCase = true) }
        return lines.drop(idx + 1).joinToString("\n")
    }

    /** Primeira linha útil da resposta (sem o prompt e sem as mensagens do app). */
    private fun firstLine(reply: String): String? =
        reply.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && it != ">" && !it.startsWith(">>>") }?.take(80)
}
