package my.robots.core.common.ascode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Respostas reais do K-ROSET ao ID e ao TIME.
 */
class AsControllerRepliesTest {

    @Test
    fun parseSerial_respostaDoId() {
        val text = "> ID\nID\n        Robot name: KJ264-A001   Num of axes 7   Serial No. 2503\n  Slave port  :     version\n>"
        assertEquals("2503", AsControllerReplies.parseSerial(text))
        assertNull(AsControllerReplies.parseSerial("> ID\n>"))
    }

    @Test
    fun parseClock_respostaDoTime_pegaAUltima() {
        val text = ">time\nTIME      26-10-03(Sat) 08:03:28\nChange? (If not, Press RETURN only.)\n" +
            "26/10/03 20:04:00\nTIME      26-10-04(Sun) 08:04:00\nChange? (If not, Press RETURN only.)"
        assertEquals(LocalDateTime.of(2026, 10, 4, 8, 4, 0), AsControllerReplies.parseClock(text))
        assertTrue(AsControllerReplies.CHANGE_PROMPT.containsMatchIn(text))
        assertEquals(2, AsControllerReplies.CLOCK_REPLY.findAll(text).count())
        assertNull(AsControllerReplies.parseClock("TIME      26-13-40(Sat) 99:99:99"))
    }

    @Test
    fun parseLoadErrors_respostaDoLoad() {
        val ok = "> LOAD db_1.as\nLoading...(db_1.as)\nSPRAY DATABANK\nFile load completed. (0 errors)\n>"
        assertEquals(0, AsControllerReplies.parseLoadErrors(ok))
        assertEquals(2, AsControllerReplies.parseLoadErrors("File load completed. (2 errors)"))
        assertNull(AsControllerReplies.parseLoadErrors("> LOAD x.as\n>"))
    }

    @Test
    fun setClockCommand_formatoDoManual() {
        assertEquals("TIME 26-10-02 19:45:07", AsControllerReplies.setClockCommand(LocalDateTime.of(2026, 10, 2, 19, 45, 7)))
    }
}
