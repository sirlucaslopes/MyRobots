package my.robots.core.common.ascode

import org.junit.Assert.assertEquals
import org.junit.Test

/** Troca do comentário do cabeçalho (usada na duplicação de programa em grupo). */
class AsProgramHeaderCommentTest {

    private fun lines(vararg l: String) = l.joinToString("\n")

    private val withComment = lines(".PROGRAM pg100()@26/03/31 21:30#5; 5955055 P1 G2-5", "  TWAIT 1", ".END")

    @Test
    fun trocaOComentario() {
        assertEquals(
            lines(".PROGRAM pg100()@26/03/31 21:30#5;Novo texto", "  TWAIT 1", ".END"),
            AsProgramBlocks.setHeaderComment(withComment, "Novo texto")
        )
    }

    @Test
    fun acrescentaQuandoNaoTem() {
        assertEquals(lines(".PROGRAM pg7();teste", ".END"), AsProgramBlocks.setHeaderComment(lines(".PROGRAM pg7()", ".END"), "teste"))
    }

    @Test
    fun vazioTiraOComentario() {
        assertEquals(lines(".PROGRAM pg100()@26/03/31 21:30#5", "  TWAIT 1", ".END"), AsProgramBlocks.setHeaderComment(withComment, ""))
    }

    @Test
    fun nomeEComentarioJuntos() {
        val dup = AsProgramBlocks.setHeaderComment(AsProgramBlocks.renameHeader(withComment, "pg900"), "copia")
        assertEquals(lines(".PROGRAM pg900()@26/03/31 21:30#5;copia", "  TWAIT 1", ".END"), dup)
    }

    @Test
    fun mantemOCrDoCabecalho() {
        val crlf = ".PROGRAM pg1()\r\n  TWAIT 1\r\n.END\r\n"
        assertEquals(".PROGRAM pg1();x\r\n  TWAIT 1\r\n.END\r\n", AsProgramBlocks.setHeaderComment(crlf, "x"))
    }
}
