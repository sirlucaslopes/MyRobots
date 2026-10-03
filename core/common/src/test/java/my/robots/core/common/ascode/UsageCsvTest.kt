package my.robots.core.common.ascode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Planilha do uso: formato do Excel em português.
 */
class UsageCsvTest {

    @Test
    fun build_separadorPontoEVirgula_virgulaDecimal_bom() {
        val csv = UsageCsv.build(
            "R10",
            listOf(
                DailyUsage(LocalDate.of(2026, 9, 29), 2.25, 24.0, 12.6, estimated = false),
                DailyUsage(LocalDate.of(2026, 9, 30), 1.5, 23.75, 3.0, estimated = true)
            )
        )
        assertTrue(csv.startsWith("﻿"))
        val lines = csv.removePrefix("﻿").trimEnd().split("\r\n")
        assertEquals("Robô;Data;Em operação (h);Ligado (h);Motor ligado (vezes);Valor estimado", lines[0])
        assertEquals("R10;29/09/2026;2,25;24,00;13;não", lines[1])
        assertEquals("R10;30/09/2026;1,50;23,75;3;sim", lines[2])
    }

    @Test
    fun fileName_semCaracteresEstranhos() {
        assertEquals("uso_R10_A_20261002.csv", UsageCsv.fileName("R10/A", LocalDate.of(2026, 10, 2)))
    }
}
