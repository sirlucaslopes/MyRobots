package my.robots.core.common.ascode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transferência mestre -> escravo: offset na base e frames (trechos reais do R10).
 */
class AsMasterTransferTest {

    private val program = """
        .PROGRAM pg100()@26/03/31 21:30#5; 5955055 P1 G2-5 HEX Stick
          CALL_PGM 9998
          SPRAY_SPEED 500mm/s
          CALL initvar
        ;
          BASE fr_[100]
          GUN 1
          BASE NULL ; volta para a base zero
          BASE fr_[22]   ; outra peça
          BASE fr_[475]+top_offset
          BASE 0.000 0.000 0.000 0.000 0.000 0.000
        .END
    """.trimIndent()

    @Test
    fun applyBaseOffset_somaSoNasBasesDeFrame() {
        val (out, changed) = AsMasterTransfer.applyBaseOffset(program, "top_offset")
        assertEquals(2, changed)
        val lines = out.lines()
        assertTrue("  BASE fr_[100]+top_offset" in lines)
        assertTrue("  BASE fr_[22]+top_offset   ; outra peça" in lines)
        assertTrue("  BASE NULL ; volta para a base zero" in lines)          // NULL fica
        assertTrue("  BASE fr_[475]+top_offset" in lines)                    // já tinha
        assertTrue("  BASE 0.000 0.000 0.000 0.000 0.000 0.000" in lines)    // números ficam
        assertEquals(program.lines().size, lines.size)
    }

    @Test
    fun framesUsed_primeiroTermoSemNull() {
        assertEquals(listOf("fr_[100]", "fr_[22]", "fr_[475]"), AsMasterTransfer.framesUsed(program))
    }

    @Test
    fun transLines_achaPeloNomeExato() {
        val backup = """
            .TRANS
            floor 0.000000 0.000000 -50.000000 0.000000 0.000000 0.000000 0.000000
            fr_[100] 1.0 2.0 3.0 0 0 0 0
            fr_[1000] 9 9 9 0 0 0 0
            top_offset 0 0 120 0 0 0 0
            .END
        """.trimIndent()
        val (lines, missing) = AsMasterTransfer.transLines(backup, listOf("fr_[100]", "fr_[22]"))
        assertEquals(listOf("fr_[100] 1.0 2.0 3.0 0 0 0 0"), lines)
        assertEquals(listOf("fr_[22]"), missing)
        assertTrue(AsMasterTransfer.definesPose(backup, "top_offset"))
        assertFalse(AsMasterTransfer.definesPose(backup, "fr_[10]"))
    }

    @Test
    fun buildFile_programasEFrames() {
        val file = AsMasterTransfer.buildFile(".PROGRAM pg1()\n  BASE fr_[1]+top_offset\n.END", listOf("fr_[1] 1 2 3 0 0 0 0"))
        assertEquals(".PROGRAM pg1()\n  BASE fr_[1]+top_offset\n.END\n.TRANS\nfr_[1] 1 2 3 0 0 0 0\n.END\n", file)
        assertEquals(".PROGRAM pg1()\n.END\n", AsMasterTransfer.buildFile(".PROGRAM pg1()\n.END", emptyList()))
    }
}
