package my.robots.core.common.ascode

import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Planilha (CSV) do uso do robô por dia, para abrir no Excel.
 *
 * No formato do Excel em português: colunas separadas por ";", vírgula decimal e um BOM UTF-8
 * no começo (sem ele, o Excel lê os acentos errado). Uma linha por dia, do mais antigo para o
 * mais novo, com as mesmas contas do gráfico "Uso do robô" ([RobotUsageHistory.daily]).
 */
object UsageCsv {
    private const val BOM = "﻿"
    private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val PT_BR = Locale.forLanguageTag("pt-BR")

    fun build(robotName: String, days: List<DailyUsage>): String = buildString {
        append(BOM)
        append("Robô;Data;Em operação (h);Ligado (h);Motor ligado (vezes);Valor estimado\r\n")
        days.forEach { d ->
            append(robotName.replace(";", ",")).append(';')
            append(d.date.format(DATE)).append(';')
            append(number(d.operatingHours, 2)).append(';')
            append(number(d.poweredHours, 2)).append(';')
            append(number(d.motorOnCount, 0)).append(';')
            append(if (d.estimated) "sim" else "não")
            append("\r\n")
        }
    }

    /** Nome do arquivo: uso_<robô>_<aaaammdd>.csv (data de hoje). */
    fun fileName(robotName: String, today: java.time.LocalDate = java.time.LocalDate.now()): String =
        "uso_${robotName.replace(Regex("[^A-Za-z0-9_-]"), "_")}_${today.format(DateTimeFormatter.BASIC_ISO_DATE)}.csv"

    private fun number(v: Double, decimals: Int) = String.format(PT_BR, "%.${decimals}f", v)
}
