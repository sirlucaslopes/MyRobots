package my.robots.core.data.hierarchy

import my.robots.core.model.Client
import my.robots.core.model.HeartbeatState
import my.robots.core.model.Manufacturer
import my.robots.core.model.ProductionLine
import my.robots.core.model.ProjectLayout
import my.robots.core.model.Robot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regras da tela inicial: árvore, ordem, atalhos, filtro e linhas diferentes. */
class ClientTreeTest {

    private val meu = Client(1, "Meu cliente", lastUsedAt = 100)
    private val honda = Client(2, "Honda", lastUsedAt = 300)
    private val velho = Client(3, "Antigo", hidden = true, lastUsedAt = 900)

    private val linha1 = ProductionLine(1, clientId = 1, name = "Linha 1")
    private val cab1 = ProductionLine(2, clientId = 2, name = "Cabine 1", sortOrder = 0)
    private val cab2 = ProductionLine(3, clientId = 2, name = "Cabine 2", sortOrder = 1)
    private val oculta = ProductionLine(4, clientId = 2, name = "Desativada", hidden = true)

    private val stations = listOf(
        ProjectLayout("Top Coat", lineId = 2, sortOrder = 1, masterProject = "Primer", workType = "Pintura"),
        ProjectLayout("Primer", lineId = 2, sortOrder = 0, workType = "Pintura"),
        ProjectLayout("Solda A", lineId = 3, sortOrder = 0, workType = "Solda", masterProject = "Primer"),
        ProjectLayout("k-roset", lineId = 1, sortOrder = 0),
        ProjectLayout("Velha", lineId = 2, sortOrder = 2, hidden = true)
    )
    private val robots = listOf(
        Robot(1, "R10", "ip", 23, project = "Primer"),
        Robot(2, "R11", "ip", 23, project = "Primer"),
        Robot(3, "R14", "ip", 23, project = "Top Coat"),
        Robot(4, "S1", "ip", 23, project = "Solda A", manufacturer = Manufacturer.FANUC),
        Robot(5, "C01", "ip", 23, project = "k-roset")
    )
    private val lines = listOf(linha1, cab1, cab2, oculta)

    private fun tree(showHidden: Boolean = false) =
        ClientTree.build(listOf(meu, honda, velho), lines, stations, robots, showHidden)

    @Test
    fun arvore_na_ordem_do_ultimo_uso_e_do_processo() {
        val t = tree()
        // Honda (300) antes de Meu cliente (100); o oculto (900) não aparece
        assertEquals(listOf("Honda", "Meu cliente"), t.map { it.client.name })
        val honda = t.first()
        assertEquals(listOf("Cabine 1", "Cabine 2"), honda.lines.map { it.line.name })
        // ordem do processo, sem a estação oculta
        assertEquals(listOf("Primer", "Top Coat"), honda.lines[0].stations.map { it.name })
        assertEquals(listOf("R10", "R11"), honda.lines[0].stations[0].robots.map { it.name })
    }

    @Test
    fun mostrar_ocultos_traz_tudo() {
        val t = tree(showHidden = true)
        assertEquals(listOf("Antigo", "Honda", "Meu cliente"), t.map { it.client.name })
        assertEquals(3, t[1].lines.size)
        assertEquals(listOf("Primer", "Top Coat", "Velha"), t[1].lines[0].stations.map { it.name })
    }

    @Test
    fun atalhos_de_um_cliente_e_de_uma_linha() {
        val t = tree()
        assertNull(ClientTree.shortcut(t))
        // só o "Meu cliente", que tem uma linha: abre direto nela
        assertEquals(Shortcut.OpenLine(1), ClientTree.shortcut(t.filter { it.client.id == 1L }))
        // só a Honda, com duas linhas: abre o cliente
        assertEquals(Shortcut.OpenClient(2), ClientTree.shortcut(t.filter { it.client.id == 2L }))
        assertNull(ClientTree.shortcut(emptyList()))
    }

    @Test
    fun filtro_por_tipo_linha_status_e_marca() {
        val t = tree()
        val conectados = setOf(1, 5)
        val state = { id: Int -> if (id in conectados) HeartbeatState.ALIVE else HeartbeatState.DISCONNECTED }

        val pintura = ClientTree.filter(t, TreeFilter(workTypes = setOf("Pintura")), state)
        assertEquals(listOf("Honda"), pintura.map { it.client.name })
        assertEquals(listOf("Cabine 1"), pintura[0].lines.map { it.line.name })

        val cab2 = ClientTree.filter(t, TreeFilter(lineIds = setOf(3L)), state)
        assertEquals(listOf("Solda A"), cab2.flatMap { c -> c.lines.flatMap { l -> l.stations.map { it.name } } })

        // conectado: Primer (R10) e k-roset (C01)
        val on = ClientTree.filter(t, TreeFilter(status = StatusFilter.CONNECTED), state)
        assertEquals(listOf("Primer", "k-roset"), on.flatMap { c -> c.lines.flatMap { l -> l.stations.map { it.name } } })

        val fanuc = ClientTree.filter(t, TreeFilter(manufacturers = setOf(Manufacturer.FANUC)), state)
        assertEquals(listOf("Solda A"), fanuc.flatMap { c -> c.lines.flatMap { l -> l.stations.map { it.name } } })

        assertEquals(t, ClientTree.filter(t, TreeFilter(), state))
        assertEquals(3, TreeFilter(workTypes = setOf("a", "b"), status = StatusFilter.CONNECTED).count)
    }

    @Test
    fun ligacoes_e_linhas_diferentes() {
        val links = ClientTree.links(stations)
        assertEquals(
            setOf(StationLink("Primer", "Top Coat", crossLine = false), StationLink("Primer", "Solda A", crossLine = true)),
            links.toSet()
        )
        assertTrue(ClientTree.isCrossLine("Primer", "Solda A", stations))
        assertFalse(ClientTree.isCrossLine("Primer", "Top Coat", stations))
        assertFalse(ClientTree.isCrossLine("Primer", "não existe", stations))
    }

    @Test
    fun tipo_da_linha_pior_estado_e_mover() {
        val t = tree()
        assertEquals("Pintura", ClientTree.lineWorkType(t[0].lines[0]))
        assertNull(ClientTree.lineWorkType(t[1].lines[0]))

        val r = robots.take(2)
        assertEquals(HeartbeatState.ALIVE, ClientTree.worstState(r) { HeartbeatState.ALIVE })
        assertEquals(HeartbeatState.STALE, ClientTree.worstState(r) { if (it == 1) HeartbeatState.STALE else HeartbeatState.ALIVE })
        assertEquals(HeartbeatState.DISCONNECTED, ClientTree.worstState(emptyList()) { HeartbeatState.ALIVE })
        assertEquals(1, ClientTree.connectedCount(r) { if (it == 1) HeartbeatState.STALE else HeartbeatState.DISCONNECTED })

        assertEquals(listOf("b", "a", "c"), ClientTree.moved(listOf("a", "b", "c"), "b", -1))
        assertEquals(listOf("a", "c", "b"), ClientTree.moved(listOf("a", "b", "c"), "b", 1))
        assertEquals(listOf("a", "b", "c"), ClientTree.moved(listOf("a", "b", "c"), "a", -1))
    }
}
