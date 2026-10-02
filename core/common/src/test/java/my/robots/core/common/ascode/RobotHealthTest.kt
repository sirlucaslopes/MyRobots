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
    fun backupDeOutroRoboNaPasta_atencao() {
        val h = RobotHealth.evaluate(info, emptyList(), backupAt, listOf("R12_full.as" to "2503"), now = backupAt, zone = zone)
        assertEquals(RobotHealth.Level.ATTENTION, h.level)
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

/**
 * Dados por eixo: horas em movimento, deslocamento e temperatura do encoder.
 */
class AxisInfoTest {

    private val backup = listOf(
        ".ROBOTDATA1",
        "ZROBOT.TYPE    35   3   3 3772      -57256   KJ264-B001 ( 2026-04-08 13:53 )",
        ".END",
        ".ENCTEMPLOG",
        "=== MIN(deg C) ===",
        "  JT1  - [26/05/12 08:00:53]     16.750",
        "  JT2  - [26/02/13 14:56:52]      0.000",
        "=== MAX(deg C) ===",
        "  JT1  - [26/05/08 17:47:40]     50.000",
        "  JT3  - [26/03/17 15:36:33]     51.000",
        ".END",
        ".OPE_INFO1",
        "MOVE_TJT  82.7 89.4 89.8 0.0 0.0",
        "DIST_DJT  1555.800 2105.384 2295.800 0.000 0.000",
        "M_MOVE_TJT  83.5 90.1 90.4 0.0 0.0",
        ".END"
    ).joinToString("\n")

    @Test
    fun parse_listasPorEixoCortadasNosEixosDoRobo() {
        val info = AsRobotInfo.parse(backup)
        assertEquals(listOf(82.7, 89.4, 89.8), info.axisMoveHours)
        assertEquals(listOf(1555.8, 2105.384, 2295.8), info.axisDistance)
        assertEquals(3, info.encoderTemperatures.size)
        val jt1 = info.encoderTemperatures[0]
        assertEquals(16.75, jt1.minCelsius!!, 0.001)
        assertEquals("26/05/08 17:47:40", jt1.maxAt)
        assertEquals(null, info.encoderTemperatures[2].minCelsius)   // JT3 só tem máxima
        assertEquals(51.0, info.encoderTemperatures[2].maxCelsius!!, 0.001)
    }

    @Test
    fun axisOf_achaOEixoNaMensagem() {
        assertEquals(5, AsRobotInfo.axisOf("Jt 5 motor overloaded."))
        assertEquals(7, AsRobotInfo.axisOf("End point for Jt7 beyond motion range."))
        assertEquals(null, AsRobotInfo.axisOf("Safety fence is open."))
    }

    @Test
    fun axisMoveHoursLast_diferencaNoPeriodo() {
        val day = 24L * 60 * 60 * 1000
        val points = listOf(
            UsagePoint(0, null, 1.0, null, listOf(10.0, 20.0)),
            UsagePoint(40 * day, null, 1.0, null, listOf(15.0, 21.0)),
            UsagePoint(60 * day, null, 1.0, null, listOf(18.0, 20.5))
        )
        // 30 dias antes do último (dia 60) -> compara com o do dia 40
        assertEquals(listOf(3.0, 0.0), RobotUsageHistory.axisMoveHoursLast(points, 30))
        assertTrue(RobotUsageHistory.axisMoveHoursLast(points.take(1), 30).isEmpty())
    }
}

/**
 * Backups de outro controlador na mesma pasta não entram no gráfico.
 */
class UsageSameControllerTest {
    @Test
    fun daily_ignoraBackupDeOutroControlador_eIntervaloImpossivel() {
        val points = listOf(
            UsagePoint(at("2026-09-01T12:00:00"), 5000.0, 500.0, 100, serialNumber = "3772"),
            UsagePoint(at("2026-09-02T00:00:00"), 1.0, 1.0, 1, serialNumber = "2503"),   // simulador
            UsagePoint(at("2026-09-02T12:00:00"), 5024.0, 502.0, 110, serialNumber = "3772")
        )
        val days = RobotUsageHistory.daily(points, zone)
        assertEquals(1.0, days[0].operatingHours, 0.001)   // 2 h em 24 h, metade no primeiro dia
        assertEquals(24.0, days.sumOf { it.poweredHours }, 0.001)
    }

    @Test
    fun daily_maisHorasQueOTempo_ignorado() {
        val points = listOf(
            UsagePoint(at("2026-09-01T00:00:00"), 1.0, 1.0, 0),
            UsagePoint(at("2026-09-02T00:00:00"), 5000.0, 500.0, 10)
        )
        val days = RobotUsageHistory.daily(points, zone)
        assertEquals(0.0, days.sumOf { it.poweredHours + it.operatingHours }, 0.001)
    }

    @Test
    fun pointFrom_serieDoOpeinfo() {
        val p = RobotUsageHistory.pointFrom(0, "x.as", ".OPE_INFO1\nOPEINFO  35 3 7 3772  1740052448  ;(25/2/20) KJ264\nSERV_TIM  1.0\n.END", zone)
        assertEquals("3772", p.serialNumber)
    }
}
