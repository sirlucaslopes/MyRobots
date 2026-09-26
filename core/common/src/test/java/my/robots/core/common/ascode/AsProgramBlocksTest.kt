package my.robots.core.common.ascode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AsProgramBlocksTest {

    // "pg10" vem ANTES de "pg1" de propósito: é o caso que apagava programa.
    private val backup = """
        .PROGRAM pg10()@26/09/23 11:42#0;Programa dez
          LMOVE a10
        .END
        .PROGRAM pg1(.p1,.p2)@25/09/23 08:00#3;Programa um
          JMOVE a1
          INZONE 1
        .END
        .TRANS
        a1 0 0 0 0 0 0
        .END
    """.trimIndent()

    @Test
    fun programName_leNomeExato() {
        assertEquals("pg1", AsProgramBlocks.programName(".PROGRAM pg1(.p1,.p2)@25/09/23 08:00#3;Programa um"))
        assertEquals("main", AsProgramBlocks.programName("  .program main ()"))
        assertEquals("main", AsProgramBlocks.programName(".PROGRAM main"))
        assertNull(AsProgramBlocks.programName(".PROGRAM"))
        assertNull(AsProgramBlocks.programName(".PROGRAMX()"))
        assertNull(AsProgramBlocks.programName("  LMOVE a1"))
    }

    @Test
    fun list_devolveProgramasNaOrdem() {
        assertEquals(listOf("pg10", "pg1"), AsProgramBlocks.list(backup))
    }

    @Test
    fun extract_naoConfundePrefixo() {
        val block = AsProgramBlocks.extract(backup, "pg1")!!
        assertEquals(
            ".PROGRAM pg1(.p1,.p2)@25/09/23 08:00#3;Programa um\n  JMOVE a1\n  INZONE 1\n.END\n",
            block
        )
        assertNull(AsProgramBlocks.extract(backup, "pg"))
    }

    @Test
    fun extract_ignoraMaiusculas() {
        assertEquals(AsProgramBlocks.extract(backup, "pg1"), AsProgramBlocks.extract(backup, "PG1"))
    }

    @Test
    fun extract_semEndFinalVaiAteOFim() {
        val truncated = ".PROGRAM main()\n  LMOVE a1\n  SPEED 50"
        assertEquals(".PROGRAM main()\n  LMOVE a1\n  SPEED 50\n", AsProgramBlocks.extract(truncated, "main"))
    }

    @Test
    fun extract_aceitaCrLf() {
        val crlf = backup.replace("\n", "\r\n")
        assertEquals(AsProgramBlocks.extract(backup, "pg1"), AsProgramBlocks.extract(crlf, "pg1"))
    }

    @Test
    fun replace_trocaSoOBlocoCerto() {
        val edited = ".PROGRAM pg1(.p1,.p2)@25/09/23 08:00#3;Programa um\n  JMOVE a1\n  LMOVE a2\n  INZONE 1\n.END"
        val result = AsProgramBlocks.replace(backup, "pg1", edited)

        assertEquals(listOf("pg10", "pg1"), AsProgramBlocks.list(result))
        assertEquals(AsProgramBlocks.extract(backup, "pg10"), AsProgramBlocks.extract(result, "pg10"))
        assertEquals("$edited\n", AsProgramBlocks.extract(result, "pg1"))
        // o que vem depois do programa continua igual
        assertEquals(true, result.endsWith(".TRANS\na1 0 0 0 0 0 0\n.END"))
    }

    @Test
    fun replace_programaInexistenteVaiParaOFim() {
        val result = AsProgramBlocks.replace(backup, "novo", ".PROGRAM novo()\n.END\n")
        assertEquals(listOf("pg10", "pg1", "novo"), AsProgramBlocks.list(result))
        assertEquals(true, result.startsWith(backup))
    }

    @Test
    fun remove_apagaSoOsPedidos() {
        val result = AsProgramBlocks.remove(backup, listOf("pg1"))
        assertEquals(listOf("pg10"), AsProgramBlocks.list(result))
        assertEquals(true, result.contains(".TRANS"))
        assertEquals(true, result.contains("LMOVE a10"))
    }

    @Test
    fun extractMany_mantemAOrdemDoBackup() {
        val packed = AsProgramBlocks.extractMany(backup, listOf("pg1", "pg10"))
        assertEquals(listOf("pg10", "pg1"), AsProgramBlocks.list(packed))
        assertEquals(false, packed.contains(".TRANS"))
    }

    @Test
    fun renameHeader_mantemParametrosDataEComentario() {
        val block = AsProgramBlocks.extract(backup, "pg1")!!
        val renamed = AsProgramBlocks.renameHeader(block, "pg1_copia")
        assertEquals(
            ".PROGRAM pg1_copia(.p1,.p2)@25/09/23 08:00#3;Programa um\n  JMOVE a1\n  INZONE 1\n.END\n",
            renamed
        )
    }
}
