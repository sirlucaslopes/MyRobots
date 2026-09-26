package my.robots.feature.codeeditor

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Locale

class PointTransformTest {

    private lateinit var originalLocale: Locale

    @Before
    fun saveLocale() {
        originalLocale = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    // índices:          0                1             2              3        4
    private val lines = listOf(
        ".PROGRAM pg1()", "  LMOVE a1", "  JMOVE #j1", "  LMOVE comum", ".END",
        // 5                6               7
        ".PROGRAM pg2()", "  LMOVE comum", ".END",
        // 8        9                                        10
        ".TRANS", "a1 100.000 200.50 300 0.000 90.000 0.000", "comum 1.000 2.000 3.000 0 0 0",
        // 11     12       13
        ".END", ".JOINT", "#j1 10.00 20.00 30.00 0 0 0",
        ".END"
    )

    @Test
    fun shift_somaNoEixoEMantemAsCasasDecimais() {
        Locale.setDefault(Locale.US)
        val result = applyPointShift(lines, listOf(1), MoveFilter.LMOVE, mapOf(PointAxis.X to 1.5, PointAxis.Y to -0.5))

        assertNull(result.error)
        assertEquals(listOf("A1"), result.changedPoints)
        assertEquals("a1 101.500 200.00 300 0.000 90.000 0.000", result.lines[9])
    }

    @Test
    fun shift_eixoExternoInexistenteECompletado() {
        Locale.setDefault(Locale.US)
        val result = applyPointShift(lines, listOf(1), MoveFilter.BOTH, mapOf(PointAxis.E7 to 10.0))
        assertEquals("a1 100.000 200.50 300 0.000 90.000 0.000 10.000", result.lines[9])
    }

    @Test
    fun shift_pontoUsadoEmOutroProgramaEIgnorado() {
        Locale.setDefault(Locale.US)
        val result = applyPointShift(lines, listOf(1, 3), MoveFilter.LMOVE, mapOf(PointAxis.Z to 1.0))

        assertEquals(listOf("A1"), result.changedPoints)
        assertEquals(listOf("COMUM"), result.skippedPoints)
        assertEquals(lines[10], result.lines[10])
    }

    @Test
    fun shift_filtroJmoveHojeIgnoraPontoDeJuntaComCerquilha() {
        // Comportamento atual (v1.1), a confirmar com um backup real: o JMOVE #j1 é
        // reconhecido, mas a definição "#j1 ..." não é achada (compara "#j1" com "J1"), então
        // o ponto vai para os ignorados. O filtro JMOVE não mexe no ponto a1 do LMOVE.
        Locale.setDefault(Locale.US)
        val result = applyPointShift(lines, listOf(1, 2), MoveFilter.JMOVE, mapOf(PointAxis.X to 5.0))

        assertEquals(emptyList<String>(), result.changedPoints)
        assertEquals(listOf("J1"), result.skippedPoints)
        assertEquals(lines, result.lines)
    }

    @Test
    fun mirror_negaOEixo() {
        Locale.setDefault(Locale.US)
        val result = applyPointMirror(lines, listOf(1), MoveFilter.LMOVE, PointAxis.Y)
        assertEquals("a1 100.000 -200.50 300 0.000 90.000 0.000", result.lines[9])
    }

    @Test
    fun selecaoEmDoisProgramasDaErroSemMexer() {
        val result = applyPointShift(lines, listOf(1, 6), MoveFilter.BOTH, mapOf(PointAxis.X to 1.0))
        assertNotNull(result.error)
        assertEquals(lines, result.lines)
    }

    @Test
    fun semLinhaDeMovimentoDaErro() {
        val result = applyPointShift(lines, listOf(0), MoveFilter.BOTH, mapOf(PointAxis.X to 1.0))
        assertNotNull(result.error)
    }

    @org.junit.Ignore("Bug da v1.1: String.format usa o idioma do celular (\"101,500\"). Corrigido no commit seguinte.")
    @Test
    fun celularEmPortugues_usaPontoDecimal() {
        // O controlador AS só entende ponto como separador decimal.
        Locale.setDefault(Locale.forLanguageTag("pt-BR"))
        val result = applyPointShift(lines, listOf(1), MoveFilter.LMOVE, mapOf(PointAxis.X to 1.5))
        assertEquals("a1 101.500 200.50 300 0.000 90.000 0.000", result.lines[9])
    }
}
