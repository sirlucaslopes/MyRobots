package my.robots.core.data.protocolo

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import my.robots.core.network.KawasakiTerminalManager.Transfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * Protocolo contra o controlador falso (tools/protocolo/controlador_falso.py), com as falhas
 * que o K-ROSET não faz quando a gente quer: pacotes partidos, LOAD com erro ou recusado, queda
 * de conexão e robô que para no meio. O cenário vai no usuário do login.
 *
 * Em todos: o app nunca pode dizer que deu certo quando não deu, e nunca pode deixar o
 * controlador esperando (o controlador falso registra "preso" quando isso acontece; o
 * relatório mostra).
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ControladorFalsoTest {

    @get:Rule val registro = RegistroDoTerminal()

    private fun sessao(cenario: String, id: Int = 1): Sessao {
        val (host, porta) = Protocolo.exige("protocolo.falso")
        return registro.usa(Sessao(host, porta, cenario, id = id))
    }

    private fun conectada(cenario: String, id: Int = 1): Sessao =
        sessao(cenario, id).also { assertTrue("não chegou ao prompt (cenário $cenario)", it.conecta()) }

    private val programa = buildString {
        append(".PROGRAM pgtesteapp()\n")
        repeat(60) { append("  TWAIT 0.1 ; linha $it do programa de teste do protocolo\n") }
        append(".END\n")
    }

    @Test fun `F01 login automatico chega ao prompt`() {
        conectada("normal")
    }

    @Test fun `F02 comando ID volta ao prompt com a serie`() = runBlocking {
        val s = conectada("normal")
        assertTrue("ID sem prompt de volta", s.comandos.sendAndAwaitPrompt(s.id, "ID", 10_000))
        assertTrue("série não apareceu", s.historico().any { it.contains("Serial No. 1996") })
    }

    @Test fun `F03 LOAD certo e conferido pelo SAVE de volta`() = runBlocking {
        val s = conectada("normal")
        val r = s.comandos.loadFile(s.id, "transfer_pgtesteapp.as", programa, 30_000)
        assertTrue("LOAD deveria dar certo: ${r.message}", r.ok)
        assertEquals(r.total, r.sent)
        val save = s.comandos.saveFile(s.id, "SAVE/P/SEL copia_teste=pgtesteapp", "copia_teste", 30_000)
        assertTrue("SAVE deveria dar certo: ${save.message}", save.ok)
        val texto = s.arquivos.texto("TESTE", save.fileName!!)!!
        assertTrue("o programa voltou diferente", texto.contains("linha 59 do programa de teste"))
    }

    @Test fun `F04 LOAD e SAVE com pacotes partidos`() = runBlocking {
        val s = conectada("fragmentado")
        val r = s.comandos.loadFile(s.id, "transfer_pgtesteapp.as", programa, 60_000)
        assertTrue("LOAD com pacotes partidos falhou: ${r.message}", r.ok)
        val save = s.comandos.saveFile(s.id, "SAVE/P/SEL copia_frag=pgtesteapp", "copia_frag", 60_000)
        assertTrue("SAVE com pacotes partidos falhou: ${save.message}", save.ok)
        assertTrue(s.arquivos.texto("TESTE", save.fileName!!)!!.contains("linha 59 do programa de teste"))
    }

    @Test fun `F05 LOAD com erros no controlador nao conta como certo`() = runBlocking {
        val s = conectada("erro_load")
        val r = s.comandos.loadFile(s.id, "transfer_pgtesteapp.as", programa, 30_000)
        assertFalse("LOAD com 2 erros foi dado como certo", r.ok)
        assertEquals(2, r.errors)
    }

    @Test fun `F06 LOAD recusado pelo controlador nao conta como certo`() = runBlocking {
        val s = conectada("recusa_load")
        val r = s.comandos.loadFile(s.id, "transfer_pgtesteapp.as", programa, 30_000)
        assertFalse("LOAD recusado foi dado como certo", r.ok)
        assertTrue(r.message, r.message.contains("não pediu"))
    }

    @Test fun `F07 LOAD de arquivo que o app nao tem termina sem travar o controlador`() = runBlocking {
        val s = conectada("normal")
        // LOAD digitado no terminal, de um arquivo que não existe no celular
        assertTrue("o controlador não voltou ao prompt", s.comandos.sendAndAwaitPrompt(s.id, "LOAD nao_existe_no_celular.as", 30_000))
        val ev = s.terminal.getLoad(s.id).value
        assertNotNull(ev)
        assertFalse("arquivo inexistente marcado como certo", ev!!.ok)
        assertNotNull("transferência não terminou", ev.finishedAt)
        assertEquals(Transfer.NONE, s.terminal.getTransfer(s.id).value)
        // e o controlador continua atendendo
        assertTrue(s.comandos.sendAndAwaitPrompt(s.id, "ID", 10_000))
    }

    @Test fun `F08 queda de conexao no meio do LOAD`() = runBlocking {
        val s = conectada("corta_load")
        val r = s.comandos.loadFile(s.id, "transfer_pgtesteapp.as", programa, 30_000)
        assertFalse("LOAD com queda foi dado como certo", r.ok)
        assertEquals(Transfer.NONE, s.terminal.getTransfer(s.id).value)
        assertTrue("o app não avisou da queda", s.historico().any { it.contains("conexão caiu durante a transferência") })
    }

    @Test fun `F09 robo que para no meio do LOAD e encerrado pelo app`() = runBlocking {
        val s = conectada("para_load")
        val r = s.comandos.loadFile(s.id, "transfer_pgtesteapp.as", programa, 60_000)
        assertFalse("LOAD parado foi dado como certo", r.ok)
        assertTrue(r.message, r.message.contains("parou"))
        assertTrue("o controlador não voltou a atender", s.comandos.sendAndAwaitPrompt(s.id, "ID", 10_000))
    }

    @Test fun `F10 SAVE completo maior que o historico do terminal`() = runBlocking {
        val s = conectada("normal")
        val r = s.comandos.saveFile(s.id, "SAVE/FULL teste_full", "teste_full", 60_000)
        assertTrue("SAVE/FULL falhou: ${r.message}", r.ok)
        val texto = s.arquivos.texto("TESTE", r.fileName!!)!!
        assertTrue("arquivo curto: ${texto.lines().size} linhas", texto.lines().size > 3000)
        assertTrue(texto.trimEnd().endsWith(".END"))
    }

    @Test fun `F11 queda de conexao no meio do SAVE`() = runBlocking {
        val s = conectada("corta_save")
        val r = s.comandos.saveFile(s.id, "SAVE/FULL teste_corte", "teste_corte", 30_000)
        assertFalse("SAVE cortado foi dado como certo", r.ok)
        assertEquals(Transfer.NONE, s.terminal.getTransfer(s.id).value)
    }

    @Test fun `F12 desconectar no meio do LOAD fica adiado ate o fim`() = runBlocking {
        val s = conectada("para_load")
        val carga = async { s.comandos.loadFile(s.id, "transfer_pgtesteapp.as", programa, 60_000) }
        assertTrue("LOAD não começou", s.espera(10_000) { s.terminal.isTransferring(s.id) })
        s.terminal.disconnect(s.id)   // sem força: tem que esperar a transferência acabar
        assertTrue("desconectou no meio da transferência", s.terminal.getConnectionStatus(s.id).value)
        assertFalse(carga.await().ok)
        assertTrue("não desconectou depois do fim", s.espera(10_000) { !s.terminal.getConnectionStatus(s.id).value })
    }

    @Test fun `F13 pedido de dados sem LOAD recebe fim de arquivo`() = runBlocking {
        val s = conectada("pede_dados")
        assertTrue("o app não respondeu ao pedido", s.espera(10_000) { s.historico().any { it.contains("pediu dados sem um LOAD") } })
        assertTrue(s.comandos.sendAndAwaitPrompt(s.id, "ID", 10_000))
    }

    @Test fun `F14 comandos ao mesmo tempo nao se misturam com o LOAD`() = runBlocking {
        val s = conectada("normal")
        val carga = async { s.comandos.loadFile(s.id, "transfer_pgtesteapp.as", programa, 30_000) }
        val id = async { s.comandos.sendAndAwaitPrompt(s.id, "ID", 30_000) }
        val outra = async { s.comandos.loadFile(s.id, "transfer_pgtesteapp.as", programa, 30_000) }
        assertTrue("primeiro LOAD: ${carga.await().message}", carga.await().ok)
        assertTrue("ID entre os LOADs", id.await())
        assertTrue("segundo LOAD: ${outra.await().message}", outra.await().ok)
    }

    @Test fun `F15 LOAD grande em muitos pedacos`() = runBlocking {
        val s = conectada("normal")
        val grande = (1..80).joinToString("") { n ->
            ".PROGRAM pgtg$n()\n" + (1..12).joinToString("") { "  LMOVE XYZ1 0000,$n.1,$it.2,3.3,4,5,6,0,0,0\n" } + ".END\n"
        }
        val r = s.comandos.loadFile(s.id, "transfer_batch_teste.as", grande, 60_000)
        assertTrue("LOAD grande falhou: ${r.message}", r.ok)
        assertTrue("arquivo pequeno demais para o teste", r.total > 40_000)
        assertEquals(r.total, r.sent)
    }

    @Test fun `F17 pergunta de erro de sintaxe no meio do LOAD respondida`() = runBlocking {
        val s = conectada("pergunta_load")
        val r = s.comandos.loadFile(s.id, "transfer_pgtesteapp.as", programa, 30_000) { q ->
            assertTrue("pergunta sem as opções 0 e 1: ${q.options}", q.options.map { it.first } == listOf("0", "1"))
            "1"
        }
        assertFalse("LOAD com erro de sintaxe foi dado como certo", r.ok)
        assertTrue(r.message, r.message.contains("erro no arquivo"))
        assertTrue("o controlador não voltou a atender", s.comandos.sendAndAwaitPrompt(s.id, "ID", 10_000))
    }

    @Test fun `F18 pergunta sem resposta nao e abandonada pelo app`() = runBlocking {
        val s = conectada("pergunta_load")
        // sem quem responda: o LOAD não pode ser dado como parado nem abandonado
        val r = s.comandos.loadFile(s.id, "transfer_pgtesteapp.as", programa, 10_000)
        assertFalse(r.ok)
        assertTrue("o app abandonou a transferência com a pergunta pendente", s.terminal.isTransferring(s.id))
        assertNotNull("a pergunta sumiu sem resposta", s.terminal.getQuestion(s.id).value)
        assertTrue(s.terminal.getConnectionStatus(s.id).value)
        // a tela responde depois (opção 0: vira comentário e continua)
        s.terminal.answerQuestion(s.id, "0")
        assertTrue("a transferência não terminou depois da resposta", s.espera(15_000) { !s.terminal.isTransferring(s.id) })
        val ev = s.terminal.getLoad(s.id).value!!
        assertFalse("LOAD com passo virado comentário foi dado como certo", ev.ok)
        assertTrue(s.comandos.sendAndAwaitPrompt(s.id, "ID", 10_000))
    }

    @Test fun `F16 dois robos ao mesmo tempo`() = runBlocking {
        val a = conectada("normal", id = 1)
        val b = conectada("fragmentado", id = 2)
        val ra = async { a.comandos.loadFile(a.id, "transfer_pgtesteapp.as", programa, 60_000) }
        val rb = async { b.comandos.loadFile(b.id, "transfer_pgtesteapp.as", programa, 60_000) }
        assertTrue("robô A: ${ra.await().message}", ra.await().ok)
        assertTrue("robô B: ${rb.await().message}", rb.await().ok)
    }
}
