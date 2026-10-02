package my.robots.core.common.ascode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leitura dos dados do robô de um backup SAVE/FULL (trechos de um backup real anonimizado).
 */
class AsRobotInfoTest {

    private val full = """
        .***************************************************************************
        .*=== AS GROUP ===         : ASE_K80000Z4M 2024/01/19 13:14
        .*USER IF AS               : UASEK80000Z4M 2024/01/19 13:14
        .*=== SERVO GROUP ===      : SVE_08000006E 2022/03/25 18:11
        .***************************************************************************
        .NETCONF     192.168.0.2,"timesys-",255.255.255.0,192.168.0.1,0.0.0.0,0.0.0.0," "
        .NETCONF2     172.20.32.45,255.255.255.0,192.168.11.1
        .ROBOTDATA1
        ZROBOT.TYPE    35   3   7 3772      -57256   KJ264-B001 ( 2026-04-08 13:53 )
        ZSYSTEM         1   5   1        -106
        .END
        .PROGRAM main()
          HOUR_MTR = 1
        .END
        .OPE_INFO1
        OPEINFO  35 3 7 3772  1740052448  ;(25/2/20 11:54:08) KJ264-B001
        CONT_TIM  5585.1
        SERV_TIM  289.9
        MTON_CNT  10930
        ESTP_CNT  214
        BRKE_CNT  12162
        M_CONT_TIM  5590.1
        M_SERV_TIM  291.5
        M_MTON_CNT  10968
        HOUR_MTR  5590.1
        .END
    """.trimIndent()

    @Test
    fun parse_backupCompleto() {
        val info = AsRobotInfo.parse(full)
        assertEquals("KJ264-B001", info.model)
        assertEquals("3772", info.serialNumber)
        assertEquals(7, info.axes)
        assertEquals(5590.1, info.hourMeterHours!!, 0.001)
        assertEquals(289.9, info.servoOnHours!!, 0.001)   // não pega o M_SERV_TIM
        assertEquals(10930, info.motorOnCount)
        assertEquals(214, info.emergencyStopCount)
        assertEquals(12162, info.brakeCount)
        assertEquals("ASE_K80000Z4M", info.asVersion)
        assertEquals("SVE_08000006E", info.servoVersion)
        assertEquals("172.20.32.45", info.controllerIp)
    }

    @Test
    fun parse_semHourMtrUsaContTim() {
        val info = AsRobotInfo.parse(".OPE_INFO1\nCONT_TIM  1.0\n.END")
        assertEquals(1.0, info.hourMeterHours!!, 0.001)
    }

    @Test
    fun parse_backupSoComProgramas_vemVazio() {
        val info = AsRobotInfo.parse(".PROGRAM main()\n  HOME\n.END")
        assertTrue(info.isEmpty)
        assertNull(info.model)
    }
}

class RobotHealthTest {

    private val zone = java.time.ZoneId.of("America/Sao_Paulo")
    private fun at(s: String) = java.time.LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()

    private fun error(timestamp: String) = RobotErrorLogEntry(
        index = "1", timestamp = timestamp, signal = "", speed = "", mode = "",
        errorCode = "E1326", errorMessage = "Safety fence is open.", operations = emptyList(),
        programs = emptyList(), currentPose = emptyList(), commandPose = emptyList(), endPose = emptyList(), raw = ""
    )

    private val info = RobotInfo(model = "KJ264-B001")
    private val backupAt = at("2026-09-29T17:30:00")

    @Test
    fun semErrosRecentes_ok() {
        val h = RobotHealth.evaluate(info, listOf(error("26/09/01 10:00:00")), backupAt, now = backupAt, zone = zone)
        assertEquals(RobotHealth.Level.OK, h.level)
        assertEquals(0, h.errorsLast7Days)
        assertEquals("E1326", h.lastError?.errorCode)
    }

    @Test
    fun erroNos7DiasAntesDoBackup_atencao() {
        val errors = listOf(error("26/09/29 15:03:58"), error("26/09/25 08:00:00"), error("26/09/10 08:00:00"))
        val h = RobotHealth.evaluate(info, errors, backupAt, now = backupAt, zone = zone)
        assertEquals(RobotHealth.Level.ATTENTION, h.level)
        assertEquals(2, h.errorsLast7Days)
        assertEquals(RobotHealth.FrequentError("E1326", "Safety fence is open.", 2), h.mostFrequentError)
    }

    @Test
    fun formatErrorTime_anoMesDiaParaDiaMesAno() {
        assertEquals("29/09/2026 15:03", RobotHealth.formatErrorTime("26/09/29 15:03:58"))
        assertEquals("texto estranho", RobotHealth.formatErrorTime("texto estranho"))
    }

    @Test
    fun backupAntigo_atencao() {
        val h = RobotHealth.evaluate(info, emptyList(), backupAt, now = at("2026-11-15T00:00:00"), zone = zone)
        assertEquals(RobotHealth.Level.ATTENTION, h.level)
        assertTrue(h.backupAgeDays > RobotHealth.STALE_BACKUP_DAYS)
    }

    @Test
    fun backupSemDadosDoControlador_semDados() {
        val h = RobotHealth.evaluate(RobotInfo(), emptyList(), backupAt, now = backupAt, zone = zone)
        assertEquals(RobotHealth.Level.NO_DATA, h.level)
    }
}
