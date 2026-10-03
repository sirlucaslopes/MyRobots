package my.robots.core.common.ascode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Catálogo de instruções: linhas reais de backup de robô de pintura.
 */
class AsInstructionsTest {

    /** Lê e regrava sem mudar nada: a linha tem que sair igual. */
    private fun roundTrip(line: String) {
        val p = AsInstructions.parse(line) ?: error("não reconheceu: $line")
        assertEquals(line, p.render())
    }

    @Test
    fun parse_eRegrava_linhasReais() {
        listOf(
            "  SPRAY_SPEED 500mm/s",
            "  AIRCUT_SPEED 650mm/s",
            "  SPRAY_JSPEED 100%",
            "  AIRCUT_JSPEED 100%",
            "  SPRAY #1,ON",
            "  SPRAY #2,OFF",
            "  PRE_SPRAY #1,100mm,ON",
            "  DOUT #4,OFF",
            "  ACCEL 100%",
            "  SMOOTH_RANGE 20mm",
            "  CALL_DBK 3",
            "  CALL_PGM 9998",
            "  TWAIT 0.5",
            "  TIMER_WAIT 0.001sec",
            "  UC_JUMP LABEL 300",
            "  LABEL 300",
            "  GUN 2",
            "  LMOVE XYZ1 0004,-118.1,2692.9,-475.4,96.41,91.84,-92.94,-0,0,0",
            "  JMOVE JOINT 0000,-89,1.79,-65,248,79,-54,0,0,0",
            "  LMOVE XYZ2 0000,862.6,184.3,1213.7,40.9,-36.2,38.04,6942,0,0",
            "  TWAIT spray_time"
        ).forEach(::roundTrip)
    }

    @Test
    fun parse_guardaComentario_eLeValores() {
        val p = AsInstructions.parse("    SPRAY #1,ON ; abre a pistola")!!
        assertEquals("SPRAY", p.def.keyword)
        assertEquals(listOf("1", "ON"), p.values)
        assertEquals("    SPRAY #1,OFF ; abre a pistola", p.render(newValues = listOf("1", "OFF")))
    }

    @Test
    fun lmove_separaBlocoECoordenadas() {
        val p = AsInstructions.parse("LMOVE XYZ1 0004,-118.1,2692.9,-475.4,96.41,91.84,-92.94,-0,0,0")!!
        assertEquals("0004", p.values[0])
        assertEquals("2692.9", p.values[2])   // Y
        val moved = p.values.toMutableList().also { it[2] = "2700" }
        assertEquals("LMOVE XYZ1 0004,-118.1,2700,-475.4,96.41,91.84,-92.94,-0,0,0", p.render(newValues = moved))
    }

    @Test
    fun trocarDentroDoGrupo_levaOValor() {
        val p = AsInstructions.parse("SPRAY_SPEED 500mm/s")!!
        val aircut = AsInstructions.all.first { it.keyword == "AIRCUT_SPEED" }
        assertEquals("AIRCUT_SPEED 500mm/s", p.render(aircut, AsInstructions.carryValues(p, aircut)))

        val spray = AsInstructions.parse("SPRAY #2,OFF")!!
        val pre = AsInstructions.all.first { it.keyword == "PRE_SPRAY" }
        assertEquals("PRE_SPRAY #2,0mm,OFF", spray.render(pre, AsInstructions.carryValues(spray, pre)))
    }

    @Test
    fun lmove_trocaXyz1PorXyz2_mantemCoordenadas() {
        val p = AsInstructions.parse("LMOVE XYZ1 0004,-118.1,2692.9,-475.4,96.41,91.84,-92.94,-0,0,0")!!
        val xyz2 = AsInstructions.all.first { it.keyword == "LMOVE" && it.label.contains("XYZ2") }
        assertEquals(
            "LMOVE XYZ2 0004,-118.1,2692.9,-475.4,96.41,91.84,-92.94,-0,0,0",
            p.render(xyz2, AsInstructions.carryValues(p, xyz2))
        )
    }

    @Test
    fun linhasQueNaoSaoDoCatalogo_null() {
        assertNull(AsInstructions.parse("  IF TASK(1002)==2 THEN"))
        assertNull(AsInstructions.parse("  ; SPRAY #1,ON"))
        assertNull(AsInstructions.parse("  LMOVE p1"))
        assertNull(AsInstructions.parse(""))
    }
}
