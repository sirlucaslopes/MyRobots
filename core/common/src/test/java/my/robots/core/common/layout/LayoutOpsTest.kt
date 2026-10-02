package my.robots.core.common.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regras da grade da cabine.
 */
class LayoutOpsTest {

    private val base = CabinState(rows = 2, cols = 2, placed = mapOf(1 to Cell(0, 0), 2 to Cell(1, 1)))

    @Test
    fun addRowECol_respeitamLimites_eFaixaDeBaixoContinuaEmbaixo() {
        val s = LayoutOps.addRow(base.copy(bandPositions = listOf(0, 1, 2)))
        assertEquals(3, s.rows)
        assertEquals(listOf(0, 1, 3), s.bandPositions)
        val max = CabinState(LayoutOps.MAX_ROWS, LayoutOps.MAX_COLS)
        assertEquals(max, LayoutOps.addRow(max))
        assertEquals(max, LayoutOps.addCol(max))
    }

    @Test
    fun removeRow_soVazia_deslocaRobosEFaixas() {
        val s = base.copy(rows = 3, placed = mapOf(1 to Cell(0, 0), 2 to Cell(2, 1)), bandPositions = listOf(1, 2, 3))
        assertFalse(LayoutOps.canRemoveRow(s, 0))
        val r = LayoutOps.removeRow(s, 1)
        assertEquals(2, r.rows)
        assertEquals(Cell(1, 1), r.placed[2])
        assertEquals(listOf(1, 1, 2), r.bandPositions)
        // nunca remove a última linha que sobrou
        assertFalse(LayoutOps.canRemoveRow(CabinState(1, 2), 0))
    }

    @Test
    fun removeCol_deslocaParaAEsquerda() {
        val s = CabinState(2, 3, mapOf(1 to Cell(0, 0), 2 to Cell(0, 2)))
        val r = LayoutOps.removeCol(s, 1)
        assertEquals(2, r.cols)
        assertEquals(Cell(0, 1), r.placed[2])
        assertEquals(s, LayoutOps.removeCol(s, 0))   // coluna ocupada: não remove
    }

    @Test
    fun moveTo_vagaLivre_trocaComOutro_eTrazDeFora() {
        val moved = LayoutOps.moveTo(base, 1, Cell(0, 1))
        assertEquals(Cell(0, 1), moved.placed[1])

        val swapped = LayoutOps.moveTo(base, 1, Cell(1, 1))
        assertEquals(Cell(1, 1), swapped.placed[1])
        assertEquals(Cell(0, 0), swapped.placed[2])

        // robô 3 estava fora: entra na vaga do 2, e o 2 sai do layout
        val fromOutside = LayoutOps.moveTo(base, 3, Cell(1, 1))
        assertEquals(Cell(1, 1), fromOutside.placed[3])
        assertFalse(2 in fromOutside.placed)

        assertEquals(base, LayoutOps.moveTo(base, 1, Cell(5, 5)))   // fora da grade: nada muda
    }

    @Test
    fun removeFromLayout() {
        assertFalse(1 in LayoutOps.removeFromLayout(base, 1).placed)
    }

    @Test
    fun placeAll_preenchePorLinha_eCresceSePreciso() {
        val s = LayoutOps.placeAll(CabinState(1, 2, mapOf(1 to Cell(0, 0))), listOf(1, 2, 3, 4))
        assertEquals(Cell(0, 1), s.placed[2])
        assertEquals(2, s.rows)
        assertEquals(Cell(1, 0), s.placed[3])
        assertEquals(Cell(1, 1), s.placed[4])
        assertEquals(Cell(0, 0), s.placed[1])   // quem já estava não muda
    }

    @Test
    fun sanitize_foraDosLimitesEDuplicadoVaoParaFora() {
        val s = LayoutOps.sanitize(
            rows = 2, cols = 2,
            positions = mapOf(1 to Cell(0, 0), 2 to Cell(0, 0), 3 to Cell(4, 0), 4 to null, 5 to Cell(1, 1)),
            bandPositions = listOf(7, -1)
        )
        assertEquals(mapOf(1 to Cell(0, 0), 5 to Cell(1, 1)), s.placed)
        assertEquals(listOf(2, 0), s.bandPositions)
        assertTrue(s.isRowEmpty(1).not())
        assertEquals(LayoutOps.MAX_ROWS, LayoutOps.sanitize(99, 1, emptyMap(), emptyList()).rows)
    }
}
