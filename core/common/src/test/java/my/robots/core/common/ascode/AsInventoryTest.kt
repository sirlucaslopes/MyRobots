package my.robots.core.common.ascode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Inventário do backup, uso das variáveis, comparação e diferença de linhas (trechos do K-ROSET). */
class AsInventoryTest {

    private val backup = """
        .*=== AS GROUP ===         : ASE_K80000W48 2019/08/05 09:48
        .PROGRAM outzone(.par,.zone)@26/10/06 22:06#0; Comandos de saida
        ; usa .par (local) e sig_clr_12z1 (global)
          CASE .par OF
           VALUE 12: ; PAR 1-2 com fr_295 no comentario
            .sig_clr_base = sig_clr_12z1-1
          END
          TYPE "texto com vazao dentro"
        .END
        .PROGRAM pg101()@26/10/06 22:06#3; 5955055 P2 G8-10 HEX Stick
          BASE fr_[101]
          LMOVE #home
          PRINT ${'$'}msg
          POINT fr_[n+1] = fr_[101]
        .END
        .SYSDATA
        PAINT_FLOW flowrate
        .END
        .ERRLOG
        1 - [26/10/06 10:00] fr_296 citado no log
        .TRANS
        fr_[101] 1.0 2.0 3.0 0.0 0.0 0.0
        fr_[102] 1.0 2.0 3.0 0.0 0.0 0.0
        fr_295 1608.080078 786.126709 -467.711212 -89.861504 61.443005 -18.452099 0.000000
        fr_296 1 2 3 4 5 6 0
        .END
        .JOINTS
        #home 0 0 0 0 0 0
        #park 0 0 0 0 0 0
        .END
        .REALS
        sig_clr_12z1 = 2001
        flowrate = 58
        vazao = 10
        !gun2 = 1
        par = 3
        .END
        .STRINGS
        ${'$'}msg "ola"
        ${'$'}nada "x"
        .END
    """.trimIndent()

    private val inv = AsInventory.parse(backup)

    @Test
    fun le_programas_e_variaveis_por_tipo() {
        assertEquals(listOf("outzone", "pg101"), inv.programs.map { it.name })
        // a linha ".sig_clr_base = ..." é variável local, não começo de seção
        assertTrue(inv.program("outzone")!!.body.any { it.contains(".sig_clr_base") })
        assertEquals(4, inv.variables.count { it.kind == AsVarKind.POSE })
        assertEquals(listOf("#home", "#park"), inv.variables.filter { it.kind == AsVarKind.JOINT }.map { it.name })
        val flow = inv.variables.first { it.name == "flowrate" }
        assertEquals(AsVarKind.REAL, flow.kind)
        assertEquals("58", flow.value)
        assertEquals("\"ola\"", inv.variables.first { it.name == "\$msg" }.value)
        val fr = inv.variables.first { it.name == "fr_[101]" }
        assertEquals("fr_", fr.base)
        assertEquals("101", fr.index)
        // o log não entra nas outras seções; o .SYSDATA entra
        assertTrue(".SYSDATA" in inv.otherSections)
        assertFalse(inv.otherSections.keys.any { it.contains("LOG") })
    }

    @Test
    fun rastreia_onde_cada_variavel_e_usada() {
        val usage = AsVariableUsage.analyze(inv)
        fun used(name: String, kind: AsVarKind) = usage[inv.variables.first { it.name == name && it.kind == kind }.key]!!
        assertEquals(listOf("outzone"), used("sig_clr_12z1", AsVarKind.REAL))
        assertEquals(listOf(".SYSDATA"), used("flowrate", AsVarKind.REAL))
        assertEquals(listOf("pg101"), used("#home", AsVarKind.JOINT))
        assertEquals(listOf("pg101"), used("\$msg", AsVarKind.STRING))
        assertEquals(listOf("pg101"), used("fr_[101]", AsVarKind.POSE))
        // fr_[n+1]: índice calculado, pode ser o 102
        assertEquals(listOf("pg101"), used("fr_[102]", AsVarKind.POSE))
    }

    @Test
    fun sem_uso_ignora_comentario_texto_local_log_e_sistema() {
        val unused = AsVariableUsage.unused(inv).map { it.name }.toSet()
        // fr_295 só no comentário, vazao só entre aspas, par só como .par (local), fr_296 só no log
        assertEquals(setOf("fr_295", "fr_296", "#park", "vazao", "par", "\$nada"), unused)
        // "!gun2" é do sistema: nunca aparece para apagar
        assertFalse("!gun2" in unused)
    }

    @Test
    fun indice_literal_so_conta_o_mesmo_elemento() {
        val inv2 = AsInventory.parse(
            listOf(".PROGRAM a()", "  BASE fr_[100]", ".END", ".TRANS", "fr_[100] 0 0 0 0 0 0", "fr_[101] 0 0 0 0 0 0", ".END")
                .joinToString("\n")
        )
        assertEquals(listOf("fr_[101]"), AsVariableUsage.unused(inv2).map { it.name })
    }

    @Test
    fun compara_offline_e_robo() {
        val robot = AsInventory.parse(
            listOf(
                ".PROGRAM pg101()@26/10/07 08:00#9; 5955055 P2 G8-10 HEX Stick",
                "  BASE fr_[101]",
                "  LMOVE #home",
                "  PRINT \$msg",
                "  POINT fr_[n+1] = fr_[101]",
                ".END",
                ".PROGRAM pg200()",
                "  TWAIT 1",
                ".END",
                ".TRANS",
                "fr_[101] 1.000000 2.0 3 0 0 0",
                "fr_999 0 0 0 0 0 0",
                ".END",
                ".REALS",
                "flowrate = 60",
                ".END"
            ).joinToString("\n")
        )
        val c = AsBackupDiff.compare(inv, robot)
        assertEquals(listOf("pg200"), c.programs(DiffStatus.ONLY_ROBOT).map { it.name })
        assertEquals(listOf("outzone"), c.programs(DiffStatus.ONLY_OFFLINE).map { it.name })
        // pg101: mesmo código, só a data do cabeçalho mudou
        val pg101 = c.programs.first { it.name == "pg101" }
        assertEquals(DiffStatus.EQUAL, pg101.status)
        assertTrue(pg101.dateOnly)
        assertEquals(listOf("fr_999"), c.variables(DiffStatus.ONLY_ROBOT).map { it.name })
        // 1.000000 = 1.0: número comparado como número
        assertEquals(DiffStatus.EQUAL, c.variables.first { it.name == "fr_[101]" }.status)
        assertEquals(DiffStatus.DIFFERENT, c.variables.first { it.name == "flowrate" }.status)
    }

    @Test
    fun diferenca_de_linhas() {
        val a = listOf("  A", "  B", "  C", "  D")
        val b = listOf("  A", "  X", "  C", "  D", "  E")
        val d = AsBackupDiff.lines(a, b)
        assertEquals(
            listOf("=  A", "-  B", "+  X", "=  C", "=  D", "+  E"),
            d.map {
                when (it.kind) {
                    DiffLine.Kind.SAME -> "="
                    DiffLine.Kind.OFFLINE -> "-"
                    DiffLine.Kind.ROBOT -> "+"
                } + it.text
            }
        )
        assertEquals(5, d.last().robotLine)
        assertNull(d.last().offlineLine)
    }

    @Test
    fun comando_de_apagar_sem_o_forcado() {
        assertEquals("DELETE/P pg200", AsBackupDiff.deleteProgramCommand("pg200"))
        val v = inv.variables
        assertEquals("DELETE/L fr_[101]", AsBackupDiff.deleteVariableCommand(v.first { it.name == "fr_[101]" }))
        assertEquals("DELETE/L #park", AsBackupDiff.deleteVariableCommand(v.first { it.name == "#park" }))
        assertEquals("DELETE/R vazao", AsBackupDiff.deleteVariableCommand(v.first { it.name == "vazao" }))
        assertEquals("DELETE/S \$nada", AsBackupDiff.deleteVariableCommand(v.first { it.name == "\$nada" }))
    }
}
