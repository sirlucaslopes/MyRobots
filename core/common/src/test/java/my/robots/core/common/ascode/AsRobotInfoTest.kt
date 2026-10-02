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

