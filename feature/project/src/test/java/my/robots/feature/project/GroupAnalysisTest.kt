package my.robots.feature.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Análises das ações em grupo (transferência mestre -> escravo e duplicação). */
class GroupAnalysisTest {

    private fun lines(vararg l: String) = l.joinToString("\n")

    private val origin = lines(
        ".PROGRAM pg100()@26/03/31 21:30#5; HEX Stick",
        "  BASE fr_[100]",
        "  TWAIT 1",
        "  BASE NULL",
        ".END",
        ".TRANS",
        "fr_[100] 1.0 2.0 3.0 0 0 0 0",
        ".END"
    )

    @Test
    fun programCheck_mostraBaseFrameEDestino() {
        val target = lines(".PROGRAM pg100()", "  TWAIT 9", ".END", ".TRANS", "fr_[100] 9 9 9 0 0 0 0", ".END")
        val c = GroupAnalysis.programCheck("pg100", origin, target, applyOffset = true, withFrames = true, offset = "top_offset", pattern = "fr_[pgnum]")
        assertEquals(3, c.origin!!.lines)
        assertEquals("26/03/31 21:30", c.origin!!.modifiedAt)
        assertEquals(1, c.target!!.lines)                       // já existe: será substituído
        assertEquals(listOf("BASE fr_[100]" to "BASE fr_[100]+top_offset"), c.baseChanges)
        assertEquals(1, c.frames.size)
        assertEquals("fr_[100] 1.0 2.0 3.0 0 0 0 0", c.frames[0].origin)
        assertEquals("fr_[100] 9 9 9 0 0 0 0", c.frames[0].target)
    }

    @Test
    fun programCheck_semOffsetESemTrans() {
        val c = GroupAnalysis.programCheck("pg100", origin, null, applyOffset = false, withFrames = false, offset = "top_offset", pattern = null)
        assertTrue(c.baseChanges.isEmpty())
        assertTrue(c.frames.isEmpty())
        assertNull(c.target)
    }

    @Test
    fun programCheck_programaQueNaoExiste() {
        val c = GroupAnalysis.programCheck("pg7", origin, null, true, true, "top_offset", null)
        assertNull(c.origin)
        assertTrue(c.frames.isEmpty())
    }

    @Test
    fun nextFreeName_pulaOsUsados() {
        assertEquals("pg103", GroupAnalysis.nextFreeName("pg100", listOf("pg100", "pg101", "pg102", "pg110")))
        assertEquals("pg1", GroupAnalysis.nextFreeName("pg0", emptyList()))
        assertEquals("initvar_copia", GroupAnalysis.nextFreeName("initvar", listOf("initvar")))
        assertEquals("initvar_copia2", GroupAnalysis.nextFreeName("initvar", listOf("initvar", "INITVAR_COPIA")))
    }

    @Test
    fun nomeDePrograma_regraDoManual() {
        assertTrue(GroupAnalysis.isValidProgramName("pg101"))
        assertTrue(GroupAnalysis.isValidProgramName("pg_teste.2"))
        assertFalse(GroupAnalysis.isValidProgramName("3pg"))
        assertFalse(GroupAnalysis.isValidProgramName("pg#2"))
        assertFalse(GroupAnalysis.isValidProgramName("nome_com_mais_de_15"))
    }
}
