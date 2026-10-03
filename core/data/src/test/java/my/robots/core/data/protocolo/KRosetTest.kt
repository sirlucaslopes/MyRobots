package my.robots.core.data.protocolo

import kotlinx.coroutines.runBlocking
import my.robots.core.network.KawasakiTerminalManager.Transfer
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Protocolo contra o K-ROSET de verdade ("protocolo.kroset", ex.: 127.0.0.1:9205). Usa uma
 * conexão só para a classe inteira (o K-ROSET tem poucas sessões) e um programa de teste
 * próprio, "pgtesteapp", apagado no fim (K99). Os testes rodam em ordem (K01, K02...).
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class KRosetTest {

    companion object {
        private var sessao: Sessao? = null
        private var conectou = false

        /** A conexão da classe; conecta no primeiro uso. */
        fun s(): Sessao {
            val (host, porta) = Protocolo.exige("protocolo.kroset")
            val atual = sessao ?: Sessao(host, porta, "as", nome = "KROSET").also {
                sessao = it
                conectou = it.conecta(20_000)
            }
            assumeTrue("K-ROSET não respondeu em $host:$porta (está aberto?)", conectou)
            return atual
        }

        @AfterClass @JvmStatic fun fecha() {
            sessao?.fecha()
            sessao = null
        }

        const val PROGRAMA = ".PROGRAM pgtesteapp()\n  TWAIT 0.1\n  TWAIT 0.2 ; teste do protocolo do app\n.END\n"
    }

    /** Registro do terminal compartilhado, depois de cada teste. */
    @get:org.junit.Rule val registro = object : TestWatcher() {
        override fun finished(description: Description) {
            val pasta = Protocolo.saida ?: return
            val s = sessao ?: return
            File(pasta, "KRosetTest.${description.methodName}.txt").writeText(s.historico().takeLast(300).joinToString("\n"))
        }
    }

    @Test fun `K01 login e ID com a serie`() = runBlocking {
        val s = s()
        assertTrue(s.comandos.sendAndAwaitPrompt(s.id, "ID", 15_000))
        assertTrue("série não apareceu", s.historico().any { it.contains("Serial No.") })
    }

    @Test fun `K02 LOAD de um programa de teste`() = runBlocking {
        val s = s()
        val r = s.comandos.loadFile(s.id, "transfer_pgtesteapp.as", PROGRAMA, 60_000)
        assertTrue("LOAD falhou: ${r.message}", r.ok)
        assertEquals(r.total, r.sent)
    }

    @Test fun `K03 SAVE do programa carregado volta igual`() = runBlocking {
        val s = s()
        val r = s.comandos.saveFile(s.id, "SAVE/P/SEL pgtesteapp_volta=pgtesteapp", "pgtesteapp_volta", 60_000)
        assertTrue("SAVE falhou: ${r.message}", r.ok)
        val texto = s.arquivos.texto("KROSET", r.fileName!!)!!
        assertTrue("o programa não voltou", texto.contains(".PROGRAM pgtesteapp("))
        assertTrue("o conteúdo voltou diferente", texto.contains("teste do protocolo do app"))
    }

    @Test fun `K04 LOAD com erro de sintaxe nao conta como certo`() = runBlocking {
        val s = s()
        val ruim = ".PROGRAM pgtesteapp()\n  ESTA_INSTRUCAO_NAO_EXISTE 1,2,3\n.END\n"
        var perguntou = false
        // o controlador para e pergunta (0: vira comentário e continua, 1: apaga o programa e
        // cancela); o teste responde 1 (o programa é só de teste)
        val r = s.comandos.loadFile(s.id, "transfer_pgtesteapp_erro.as", ruim, 60_000) { perguntou = true; "1" }
        assertFalse("LOAD com instrução inválida foi dado como certo (${r.message})", r.ok)
        assertTrue("o controlador não perguntou nada (mudou o comportamento?)", perguntou)
        assertEquals("a transferência ficou aberta", Transfer.NONE, s.terminal.getTransfer(s.id).value)
        assertTrue("o controlador não voltou a atender depois da resposta", s.comandos.sendAndAwaitPrompt(s.id, "ID", 15_000))
    }

    @Test fun `K04b LOAD de novo depois do cancelamento`() = runBlocking {
        val s = s()
        val r = s.comandos.loadFile(s.id, "transfer_pgtesteapp.as", PROGRAMA, 60_000)
        assertTrue("LOAD depois do cancelamento falhou: ${r.message}", r.ok)
    }

    @Test fun `K05 LOAD de arquivo que o app nao tem nao trava o controlador`() = runBlocking {
        val s = s()
        assertTrue("o controlador não voltou ao prompt", s.comandos.sendAndAwaitPrompt(s.id, "LOAD nao_existe_no_celular.as", 60_000))
        val ev = s.terminal.getLoad(s.id).value
        assertNotNull(ev)
        assertFalse(ev!!.ok)
        assertEquals(Transfer.NONE, s.terminal.getTransfer(s.id).value)
        assertTrue("o controlador parou de atender depois", s.comandos.sendAndAwaitPrompt(s.id, "ID", 15_000))
    }

    @Test fun `K06 SAVE FULL completo`() = runBlocking {
        val s = s()
        val r = s.comandos.saveFile(s.id, "SAVE/FULL teste_protocolo_full", "teste_protocolo_full", 5 * 60_000)
        assertTrue("SAVE/FULL falhou: ${r.message}", r.ok)
        val texto = s.arquivos.texto("KROSET", r.fileName!!)!!
        assertTrue("backup pequeno demais: ${r.bytes} bytes", r.bytes > 20_000)
        assertTrue("o programa de teste não está no backup", texto.contains(".PROGRAM pgtesteapp("))
    }

    @Test fun `K99 apaga o programa de teste`() = runBlocking {
        val s = s()
        s.terminal.sendCommand(s.id, "DELETE/P pgtesteapp")
        assertTrue("não perguntou a confirmação", s.espera(10_000) { s.historico().any { it.contains("Yes:1") } })
        assertTrue("não voltou ao prompt", s.comandos.sendAndAwaitPrompt(s.id, "1", 15_000))
    }
}
