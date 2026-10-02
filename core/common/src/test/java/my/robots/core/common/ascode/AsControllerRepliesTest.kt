package my.robots.core.common.ascode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

/**
 * Respostas reais do K-ROSET ao ID e ao comando de relógio.
 */
class AsControllerRepliesTest {

    @Test
    fun parseSerial_respostaDoId() {
        val text = "> ID\nID\n        Robot name: KJ264-A001   Num of axes 7   Serial No. 2503\n  Slave port  :     version\n>"
        assertEquals("2503", AsControllerReplies.parseSerial(text))
        assertNull(AsControllerReplies.parseSerial("> ID\n>"))
    }

    @Test
    fun parseClock_respostaDoPrint() {
        val text = "> PRINT \$DATE(3),\" \",\$TIME\nPRINT \$DATE(3),\" \",\$TIME\n2026/10/03 07:45:47\n>"
        assertEquals(LocalDateTime.of(2026, 10, 3, 7, 45, 47), AsControllerReplies.parseClock(text))
        assertNull(AsControllerReplies.parseClock("2026/13/40 99:99:99"))
    }

    @Test
    fun setClockCommand_formatoDoTime() {
        assertEquals("TIME 26-10-02 19:45:07", AsControllerReplies.setClockCommand(LocalDateTime.of(2026, 10, 2, 19, 45, 7)))
    }
}
