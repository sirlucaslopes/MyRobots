package my.robots.core.common.ascode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Resposta do comando FREE (texto real do K-ROSET).
 */
class AsFreeMemoryTest {

    @Test
    fun parseLast_respostaDoKRoset() {
        val text = "> FREE\nFREE\nTotal memory, 8192 KBbytes.\nAvailable memory size 8175 KBbytes.( 99 %)\n>"
        val m = AsFreeMemory.parseLast(text, readAt = 5)
        assertEquals(ControllerMemory(8192, 8175, 5), m)
        assertEquals(99, m!!.freePercent)
        assertEquals(1, AsFreeMemory.countAnswers(text))
    }

    @Test
    fun parseLast_pegaAUltimaEConverteBytes() {
        val text = "Total memory, 8192 KBbytes.\nAvailable memory size 8175 KBbytes.\n" +
            "Total memory, 2097152 bytes.\nAvailable memory size 1048576 bytes."
        val m = AsFreeMemory.parseLast(text, readAt = 0)
        assertEquals(2048L, m!!.totalKb)
        assertEquals(1024L, m.freeKb)
        assertEquals(50, m.freePercent)
    }

    @Test
    fun parseLast_respostaIncompleta_null() {
        assertNull(AsFreeMemory.parseLast("> FREE\nTotal memory, 8192 KBbytes."))
    }
}
