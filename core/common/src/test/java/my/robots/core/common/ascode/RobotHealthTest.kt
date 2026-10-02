package my.robots.core.common.ascode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

private val zone = ZoneId.of("America/Sao_Paulo")
private fun at(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()

/**
 * Status geral: só alarmes graves (ou backup antigo) ligam o "Atenção".
 */
class RobotHealthTest {

    private fun error(timestamp: String, code: String = "E1326", message: String = "Safety fence is open.") =
        RobotErrorLogEntry(
            index = "1", timestamp = timestamp, signal = "", speed = "", mode = "",
            errorCode = code, errorMessage = message, operations = emptyList(),
            programs = emptyList(), currentPose = emptyList(), commandPose = emptyList(), endPose = emptyList(), raw = ""
        )

    private val info = RobotInfo(model = "KJ264-B001")
    private val backupAt = at("2026-09-29T17:30:00")

    @Test
    fun soAlarmesDeRotinaEProcesso_ok() {
        val errors = listOf(
            error("26/09/29 15:03:58"), error("26/09/28 10:00:00"),
            error("26/09/27 10:00:00", "E1088", "Destination is out of motion range.")
        )
        val h = RobotHealth.evaluate(info, errors, backupAt, now = backupAt, zone = zone)
        assertEquals(RobotHealth.Level.OK, h.level)
        assertEquals(0, h.attentionCount)
        // agrupado por código, mais grave primeiro: processo antes de rotina
        assertEquals(listOf("E1088", "E1326"), h.errorGroups.map { it.code })
        assertEquals(2, h.errorGroups[1].count)
        assertEquals("26/09/29 15:03:58", h.errorGroups[1].lastTimestamp)
    }

    @Test
    fun alarmeGraveNos7Dias_atencao_eForaDaJanelaNaoConta() {
        val errors = listOf(
            error("26/09/25 08:00:00", "E0952", "Encoder rotation data is abnormal."),
            error("26/09/10 08:00:00", "E0953", "Encoder communication error.")
        )
        val h = RobotHealth.evaluate(info, errors, backupAt, now = backupAt, zone = zone)
        assertEquals(RobotHealth.Level.ATTENTION, h.level)
        assertEquals(listOf("E0952"), h.seriousGroups.map { it.code })
    }

    @Test
    fun backupAntigo_atencao() {
        val h = RobotHealth.evaluate(info, emptyList(), backupAt, now = at("2026-11-15T00:00:00"), zone = zone)
        assertEquals(RobotHealth.Level.ATTENTION, h.level)
        assertTrue(h.isBackupStale)
        assertEquals(1, h.attentionCount)
    }

    @Test
    fun backupSemDadosDoControlador_semDados() {
        val h = RobotHealth.evaluate(RobotInfo(), emptyList(), backupAt, now = backupAt, zone = zone)
        assertEquals(RobotHealth.Level.NO_DATA, h.level)
    }

    @Test
    fun formatErrorTime_anoMesDiaParaDiaMesAno() {
        assertEquals("29/09/2026 15:03", RobotHealth.formatErrorTime("26/09/29 15:03:58"))
        assertEquals("texto estranho", RobotHealth.formatErrorTime("texto estranho"))
    }
}

/**
 * Classificação dos alarmes (códigos e mensagens reais do .ERRLOG).
 */
class AsErrorSeverityTest {
    @Test
    fun rotina_processo_grave() {
        assertEquals(ErrorSeverity.ROUTINE, AsErrorSeverity.classify("E1326", "Safety fence is open."))
        assertEquals(ErrorSeverity.ROUTINE, AsErrorSeverity.classify("", ""))
        assertEquals(ErrorSeverity.ROUTINE, AsErrorSeverity.classify("D1561", "[Power sequence board]AC primary power OFF."))
        assertEquals(ErrorSeverity.PROCESS, AsErrorSeverity.classify("E1088", "Destination is out of motion range."))
        assertEquals(ErrorSeverity.PROCESS, AsErrorSeverity.classify("E6008", "Wrist can't be bent any more (Singular point 2)."))
        assertEquals(ErrorSeverity.SERIOUS, AsErrorSeverity.classify("E0952", "Encoder rotation data is abnormal."))
        assertEquals(ErrorSeverity.SERIOUS, AsErrorSeverity.classify("D1522", "Mismatch in cond. of safety circuit enabling device."))
    }
}

/**
 * Uso por dia montado a partir dos contadores de vários backups.
 */
class RobotUsageHistoryTest {

    @Test
    fun pointFrom_usaDataDoNomeDoArquivo() {
        val p = RobotUsageHistory.pointFrom(
            at("2026-09-20T13:06:00"), "R10_20260919_0810.as",
            ".OPE_INFO1\nSERV_TIM  271.2\nMTON_CNT  9290\nHOUR_MTR  5368.6\n.END", zone
        )
        assertEquals(at("2026-09-19T08:10:00"), p.timestamp)
        assertEquals(271.2, p.operatingHours!!, 0.001)
        assertEquals(5368.6, p.poweredHours!!, 0.001)
        assertEquals(9290, p.motorOnCount)
    }

    @Test
    fun pointFrom_nomeForaDoPadrao_usaDataDoBanco() {
        val p = RobotUsageHistory.pointFrom(at("2026-09-20T13:06:00"), "R10_full.as", "", zone)
        assertEquals(at("2026-09-20T13:06:00"), p.timestamp)
    }

    @Test
    fun daily_divideODeltaEntreOsDias() {
        val points = listOf(
            UsagePoint(at("2026-09-01T12:00:00"), 100.0, 10.0, 0),
            UsagePoint(at("2026-09-02T12:00:00"), 124.0, 14.0, 10)
        )
        val days = RobotUsageHistory.daily(points, zone)
        assertEquals(2, days.size)
        assertEquals(2.0, days[0].operatingHours, 0.001)   // metade do intervalo em cada dia
        assertEquals(2.0, days[1].operatingHours, 0.001)
        assertEquals(12.0, days[0].poweredHours, 0.001)
        assertEquals(5.0, days[1].motorOnCount, 0.001)
        assertTrue(days.none { it.estimated })
    }

    @Test
    fun daily_contadorQueDiminuiEIgnorado_eIntervaloLongoEEstimado() {
        val points = listOf(
            UsagePoint(at("2026-09-01T00:00:00"), 100.0, 10.0, 50),
            UsagePoint(at("2026-09-05T00:00:00"), 196.0, 2.0, 60)
        )
        val days = RobotUsageHistory.daily(points, zone)
        assertEquals(4, days.size)
        assertTrue(days.all { it.estimated })
        assertEquals(0.0, days.sumOf { it.operatingHours }, 0.001)   // servo diminuiu: ignorado
        assertEquals(24.0, days[0].poweredHours, 0.001)
    }

    @Test
    fun daily_menosDeDoisBackups_vazio() {
        assertTrue(RobotUsageHistory.daily(listOf(UsagePoint(0, 1.0, 1.0, 1)), zone).isEmpty())
    }
}
