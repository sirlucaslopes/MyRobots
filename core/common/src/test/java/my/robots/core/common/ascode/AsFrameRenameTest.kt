package my.robots.core.common.ascode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Troca do frame da base na duplicação de programa. */
class AsFrameRenameTest {

    private fun lines(vararg l: String) = l.joinToString("\n")

    @Test
    fun renameFrame_nomeExato() {
        val code = lines(
            ".PROGRAM pg102()",
            "  BASE fr_[100]+top_offset",
            "  POINT p1 = fr_[100]",
            "  BASE fr_[1000]",
            "  BASE xfr_[100]",
            ".END"
        )
        val (out, n) = AsMasterTransfer.renameFrame(code, "fr_[100]", "fr_[102]")
        assertEquals(2, n)
        assertEquals(
            lines(
                ".PROGRAM pg102()",
                "  BASE fr_[102]+top_offset",
                "  POINT p1 = fr_[102]",
                "  BASE fr_[1000]",
                "  BASE xfr_[100]",
                ".END"
            ),
            out
        )
    }

    @Test
    fun renameFrame_mesmoNomeNaoMexe() {
        assertEquals(0, AsMasterTransfer.renameFrame("BASE fr_[1]", "fr_[1]", "fr_[1]").second)
    }

    @Test
    fun renameTransLine_trocaSoONome() {
        assertEquals("fr_[102] 1.0 2.0 3.0 0 0 0 0", AsMasterTransfer.renameTransLine("fr_[100] 1.0 2.0 3.0 0 0 0 0", "fr_[102]"))
    }

    @Test
    fun nomeDePose_aceitaColcheteESublinhado() {
        assertTrue(AsMasterTransfer.isValidPoseName("fr_[102]"))
        assertTrue(AsMasterTransfer.isValidPoseName("teste_[999]"))
        assertTrue(AsMasterTransfer.isValidPoseName("p[1,2]"))
        assertTrue(AsMasterTransfer.isValidPoseName("top_offset"))
        assertFalse(AsMasterTransfer.isValidPoseName("1fr"))
        assertFalse(AsMasterTransfer.isValidPoseName("fr_[a]"))
    }
}
