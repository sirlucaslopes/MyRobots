package my.robots.core.common.ascode

import my.robots.core.common.FileUtil
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Testes de caracterização das contagens do backup e do corte de seções desconhecidas.
 */
class AsBackupStatsTest {

    private val backup = """
        .PROGRAM main()
          ; comentário
          LMOVE a1
        .END
        .PROGRAM pg1()
        .END
        .TRANS
        a1 0.0, 10.0, 20.0, 0, 90, 0
        a2 1 2 3 4 5 6
        .END
        .REALS
        speed = 50
        ; comentário = não conta
        .END
        .DIAGNOSTICO_DO_SISTEMA
        x = 1
        y = 2
        .STRINGS
        ${'$'}nome = "R10"
        .END
    """.trimIndent()

    @Test
    fun count_contaProgramasEVariaveis() {
        // a1 conta (3+ valores separados por vírgula); a2 não (só espaços); speed e ${'$'}nome contam;
        // x e y ficam na seção desconhecida e são ignorados.
        assertEquals(BackupCounts(programs = 2, variables = 3), AsBackupStats.count(backup))
    }

    @Test
    fun count_textoVazio() {
        assertEquals(BackupCounts(0, 0), AsBackupStats.count("   "))
    }

    @Test
    fun sanitizeAsContent_cortaSoASecaoDesconhecida() {
        val clean = FileUtil.sanitizeAsContent(backup)
        assertEquals(false, clean.contains(".DIAGNOSTICO_DO_SISTEMA"))
        assertEquals(false, clean.contains("x = 1"))
        assertEquals(true, clean.contains(".STRINGS"))
        assertEquals(true, clean.contains("LMOVE a1"))
    }

    @Test
    fun parseHeader_leDataHoraEComentario() {
        assertEquals(
            AsProgramBlocks.Header("26/09/23 11:42", "Robot States Control Program"),
            AsProgramBlocks.parseHeader(".PROGRAM main()@26/09/23 11:42#0;Robot States Control Program")
        )
        assertEquals(AsProgramBlocks.Header("", "só comentário"), AsProgramBlocks.parseHeader(".PROGRAM pg1(.a,.b);só comentário"))
        assertEquals(AsProgramBlocks.Header("", ""), AsProgramBlocks.parseHeader(".PROGRAM pg1()"))
        assertEquals(null, AsProgramBlocks.parseHeader(".PROGRAM pg1"))
    }
}
