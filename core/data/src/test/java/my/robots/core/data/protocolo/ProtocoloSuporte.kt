package my.robots.core.data.protocolo

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import my.robots.core.data.RobotCommands
import my.robots.core.model.Robot
import my.robots.core.network.KawasakiTerminalManager
import my.robots.core.network.RobotFileInfo
import my.robots.core.network.RobotFileStore
import org.junit.Assume.assumeTrue
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Peças comuns dos testes do protocolo (rodados pelo tools/protocolo/rodar_testes.py).
 *
 * Os endereços vêm das propriedades do teste: "protocolo.falso" (controlador falso em Python) e
 * "protocolo.kroset" (K-ROSET). Sem a propriedade, o teste é pulado (o build normal não depende
 * de rede). "protocolo.saida" é a pasta onde cada teste deixa o registro do terminal.
 */
object Protocolo {
    fun endereco(chave: String): Pair<String, Int>? {
        val valor = System.getProperty(chave)?.takeIf { it.isNotBlank() && it != "nao" } ?: return null
        val host = valor.substringBeforeLast(':')
        val porta = valor.substringAfterLast(':').toIntOrNull() ?: return null
        return host to porta
    }

    fun exige(chave: String): Pair<String, Int> {
        val e = endereco(chave)
        assumeTrue("$chave não informado: teste pulado", e != null)
        return e!!
    }

    val saida: File? get() = System.getProperty("protocolo.saida")?.let { File(it).apply { mkdirs() } }
}

/** Pasta de arquivos do robô na memória (o lugar dos arquivos do SAVE). */
class ArquivosNaMemoria : RobotFileStore {
    val arquivos = ConcurrentHashMap<String, ByteArray>()

    override fun list(robotName: String): List<RobotFileInfo> =
        arquivos.keys.filter { it.startsWith("$robotName/") }.map { RobotFileInfo(it.substringAfter('/'), 0) }

    override fun read(robotName: String, fileName: String): ByteArray? = arquivos["$robotName/$fileName"]

    override fun openOutput(robotName: String, fileName: String): OutputStream = object : ByteArrayOutputStream() {
        override fun close() {
            arquivos["$robotName/$fileName"] = toByteArray()
        }
    }

    override fun delete(robotName: String, fileName: String): Boolean = arquivos.remove("$robotName/$fileName") != null

    fun texto(robotName: String, fileName: String): String? = read(robotName, fileName)?.toString(Charsets.ISO_8859_1)
}

/**
 * Uma conexão de teste: um KawasakiTerminalManager só para ela, com a pasta na memória, e o
 * RobotCommands (o mesmo caminho que o app usa para LOAD e SAVE).
 */
class Sessao(host: String, porta: Int, usuario: String, val id: Int = 1, nome: String = "TESTE") {
    val arquivos = ArquivosNaMemoria()
    val terminal = KawasakiTerminalManager(arquivos).apply { transferStallMs = 4_000 }
    val comandos = RobotCommands(terminal)
    val robo = Robot(id = id, name = nome, ip = host, port = porta, autoLogin = true, loginUser = usuario, loginPassword = "")

    /** Conecta e espera o prompt. Devolve false se não chegar em [timeoutMs]. */
    fun conecta(timeoutMs: Long = 15_000): Boolean = runBlocking {
        terminal.connect(robo)
        var esperou = 0L
        while (!terminal.getConnectionStatus(id).value && esperou < 6_000) { delay(100); esperou += 100 }
        if (!terminal.getConnectionStatus(id).value) return@runBlocking false
        comandos.awaitPrompt(id, timeoutMs)
    }

    fun historico(): List<String> = terminal.getHistory(id).value

    fun fecha() = terminal.disconnect(id, force = true)

    /** Espera [cond] ficar verdadeira (até [timeoutMs]). */
    fun espera(timeoutMs: Long, cond: () -> Boolean): Boolean = runBlocking {
        var esperou = 0L
        while (!cond() && esperou < timeoutMs) { delay(100); esperou += 100 }
        cond()
    }
}

/**
 * Guarda o terminal de cada sessão do teste em <protocolo.saida>/<classe>.<teste>.txt, para o
 * relatório mostrar o que aconteceu quando um teste falha.
 */
class RegistroDoTerminal : TestWatcher() {
    private val sessoes = mutableListOf<Sessao>()

    fun <T : Sessao> usa(s: T): T = s.also { sessoes += it }

    override fun finished(description: Description) {
        val pasta = Protocolo.saida
        if (pasta != null && sessoes.isNotEmpty()) {
            val texto = sessoes.joinToString("\n\n") { s ->
                "=== ${s.robo.loginUser} (${s.robo.ip}:${s.robo.port}) ===\n" + s.historico().joinToString("\n")
            }
            File(pasta, "${description.className.substringAfterLast('.')}.${description.methodName}.txt").writeText(texto)
        }
        sessoes.forEach { runCatching { it.fecha() } }
        sessoes.clear()
    }
}
