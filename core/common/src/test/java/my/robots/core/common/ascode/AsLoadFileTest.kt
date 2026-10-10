package my.robots.core.common.ascode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Carregar" das ações em grupo: itens do arquivo, arquivo do LOAD e conferência no destino. */
class AsLoadFileTest {

    private val source = listOf(
        ".*=== AS GROUP ===         : ASE_K80000W48",
        ".NETCONF     192.168.0.2,\"timesys-\",255.255.255.0",
        ".SYSDATA",
        "PAINT_FLOW flowrate",
        ".END",
        ".PROGRAM pg100()@26/03/31 21:30#5; 5955055 P1",
        "  BASE fr_[100]",
        "  .par = 1",
        ".END",
        ".PROGRAM pg101()",
        "  TWAIT 1",
        ".END",
        ".ERRLOG",
        "1 - [26/10/06 10:00] (E1326) Safety fence is open.",
        ".TRANS",
        "fr_[100] 1.0 2.0 3.0 0.0 0.0 0.0",
        "fr_[101] 1.0 2.0 3.0 0.0 0.0 0.0",
        ".END",
        ".REALS",
        "speed = 50",
        ".END",
        ".sprdb",
        "  DB1 24 10 55 -1 -1 -1 \"base\"",
        "  DB2 18 10 55 -1 -1 -1 \"\"",
        ".END"
    ).joinToString("\n")

    @Test
    fun separa_programas_variaveis_data_bank_e_sistema_sem_os_logs() {
        val items = AsLoadFile.items(source)
        assertEquals(
            listOf(
                "SYSTEM:.NETCONF", "SYSTEM:.SYSDATA", "PROGRAM:pg100", "PROGRAM:pg101",
                "POSE:fr_[100]", "POSE:fr_[101]", "REAL:speed", "DATABANK:db1", "DATABANK:db2"
            ),
            items.map { it.kind.name + ":" + it.name.let { n -> if (it.kind == LoadKind.PROGRAM || it.kind == LoadKind.SYSTEM || it.kind == LoadKind.POSE || it.kind == LoadKind.REAL) n else n.lowercase() } }
        )
        val pg100 = items.first { it.name == "pg100" }
        // ".par = 1" é linha do programa, não seção; o bloco vai inteiro
        assertEquals(4, pg100.lines.size)
        assertTrue(pg100.detail.startsWith("2 linhas"))
        assertEquals("50", items.first { it.name == "speed" }.detail)
        assertTrue(LoadKind.SYSTEM.system)
    }

    @Test
    fun monta_o_arquivo_so_com_os_escolhidos() {
        val items = AsLoadFile.items(source)
        val chosen = items.filter { it.name in setOf("pg101", "fr_[101]", "speed", "DB2") }
        val file = AsLoadFile.build(chosen)
        assertEquals(
            listOf(
                ".PROGRAM pg101()", "  TWAIT 1", ".END",
                ".TRANS", "fr_[101] 1.0 2.0 3.0 0.0 0.0 0.0", ".END",
                ".REALS", "speed = 50", ".END",
                ".sprdb", "  DB2 18 10 55 -1 -1 -1 \"\"", ".END"
            ),
            file.trimEnd().lines()
        )
        // seção do sistema vai inteira, com o .END
        val sys = AsLoadFile.build(items.filter { it.name == ".SYSDATA" })
        assertEquals(listOf(".SYSDATA", "PAINT_FLOW flowrate", ".END"), sys.trimEnd().lines())
    }

    @Test
    fun confere_contra_o_backup_do_destino() {
        val items = AsLoadFile.items(source)
        val target = listOf(
            ".PROGRAM pg100()", "  TWAIT 2", ".END",
            ".TRANS", "fr_[100] 1.000000 2.0 3 0 0 0", "fr_[101] 9 9 9 0 0 0", ".END",
            ".sprdb", "  DB1 1 1 1 -1 -1 -1 \"\"", ".END"
        ).joinToString("\n")
        val chosen = items.filter { it.kind != LoadKind.SYSTEM }
        val c = AsLoadFile.check(chosen, target)
        assertTrue(c.backupKnown)
        assertEquals(listOf("pg100"), c.replacedPrograms)
        assertEquals(listOf("pg101"), c.newPrograms)
        // fr_[100] igual (número como número), fr_[101] muda, speed é nova
        assertEquals(1, c.sameVariables)
        assertEquals(listOf("fr_[101]"), c.changedVariables)
        assertEquals(1, c.newVariables)
        assertEquals(listOf("DB1"), c.replacedDataBank)
        assertEquals(1, c.newDataBank)
        assertTrue(c.warn)

        val semBackup = AsLoadFile.check(items.filter { it.name == "pg101" }, null)
        assertFalse(semBackup.backupKnown)
        assertTrue(semBackup.warn)
        val nova = AsLoadFile.check(items.filter { it.name == "pg101" }, target)
        assertFalse(nova.warn)
    }
}
