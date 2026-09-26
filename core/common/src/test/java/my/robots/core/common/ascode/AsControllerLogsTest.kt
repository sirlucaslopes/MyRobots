package my.robots.core.common.ascode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes de caracterização: fixam o que os parsers de log fazem hoje (v1.1), com trechos no
 * formato documentado no código. Trocar por trechos de um SAVE/FULL real quando houver.
 */
class AsControllerLogsTest {

    private val backup = """
        .PROGRAM main()
        .END
        .OPELOG
        12 - [26/07/12 08:15:26] [TP] Connect
        13 - [26/07/12 08:16:00] [AUX1] SAVE/FULL
          detalhe da operação 13

        15 - [26/07/12 08:20:00] [TP] RESET
        .PGM_EDT_LOG
        1 - [26/07/12 09:00:00] pg1 Step addition 5
        .ERRLOG
        7 - [26/07/12 08:15:26 SIGNAL:ON MON.SPEED : 10 REPEAT MODE]
        (E1234) EMERGENCY STOP pressed
        OPERATION1:[26/07/12 08:15:26] ( EMERGENCY STOP )
        OPERATION2:[26/07/12 08:15:27] ( MOTOR OFF )
        ROBOT1:
        PROGRAM:pg9996 Step:0 Cur_Step:30 STATUS:STOP
        PC1 PROGRAM: pc2_main Step No: 16 STATUS: STOP
        Current Pose
        JT1 JT2 JT3 JT4 JT5 JT6
        0.0 10.5 -20 30 40 50
        Command Pose
        1 2 3 4 5 6
        End Pose
        8 - [texto que não segue o formato]
        (E0102) outra coisa
        .END
    """.trimIndent().lines()

    @Test
    fun parseLogSection_separaEntradasEJuntaDetalhes() {
        val entries = AsControllerLogs.parseLogSection(backup, ".OPELOG")

        assertEquals(listOf("12", "13", "15"), entries.map { it.index })
        assertEquals("26/07/12 08:15:26", entries[0].timestamp)
        assertEquals("13 - [26/07/12 08:16:00] [AUX1] SAVE/FULL\ndetalhe da operação 13", entries[1].raw)
    }

    @Test
    fun parseLogSection_terminaNaProximaSecao() {
        val entries = AsControllerLogs.parseLogSection(backup, ".PGM_EDT_LOG")
        assertEquals(1, entries.size)
        assertEquals("1 - [26/07/12 09:00:00] pg1 Step addition 5", entries[0].raw)
    }

    @Test
    fun parseLogSection_semASecaoDevolveVazio() {
        assertTrue(AsControllerLogs.parseLogSection(listOf(".PROGRAM a()", ".END"), ".OPELOG").isEmpty())
    }

    @Test
    fun parseErrorLog_separaOsCamposDaEntrada() {
        val entry = AsControllerLogs.parseErrorLog(backup).first()

        assertEquals("7", entry.index)
        assertEquals("26/07/12 08:15:26", entry.timestamp)
        assertEquals("ON", entry.signal)
        assertEquals("10", entry.speed)
        assertEquals("REPEAT MODE", entry.mode)
        assertEquals("E1234", entry.errorCode)
        assertEquals("EMERGENCY STOP pressed", entry.errorMessage)
        assertEquals(
            listOf(
                RobotErrorLogOperation("OPERATION1", "26/07/12 08:15:26", "EMERGENCY STOP"),
                RobotErrorLogOperation("OPERATION2", "26/07/12 08:15:27", "MOTOR OFF")
            ),
            entry.operations
        )
        assertEquals(
            listOf(
                RobotErrorLogProgram("ROBOT1", "pg9996", "30", "STOP"),
                RobotErrorLogProgram("PC1", "pc2_main", "16", "STOP")
            ),
            entry.programs
        )
        assertEquals(listOf("0.0", "10.5", "-20", "30", "40", "50"), entry.currentPose)
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), entry.commandPose)
        assertEquals(emptyList<String>(), entry.endPose)
    }

    @Test
    fun parseErrorLog_formatoDesconhecidoCaiNoTextoCru() {
        val entries = AsControllerLogs.parseErrorLog(backup)
        assertEquals(2, entries.size)

        val odd = entries[1]
        assertEquals("8", odd.index)
        // sem data/hora reconhecida, o timestamp é a própria linha de cabeçalho
        assertEquals("8 - [texto que não segue o formato]", odd.timestamp)
        assertEquals("E0102", odd.errorCode)
        assertEquals("8 - [texto que não segue o formato]\n(E0102) outra coisa", odd.raw)
    }
}
