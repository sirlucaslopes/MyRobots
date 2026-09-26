package my.robots.core.common

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class ExternalAsFileTest {

    @Test
    fun hasAsExtension_aceitaSoAsEPg() {
        assertEquals(true, ExternalAsFile.hasAsExtension("r10.as"))
        assertEquals(true, ExternalAsFile.hasAsExtension("R10.AS"))
        assertEquals(true, ExternalAsFile.hasAsExtension("prog.pg"))
        assertEquals(false, ExternalAsFile.hasAsExtension("foto.jpg"))
        assertEquals(false, ExternalAsFile.hasAsExtension("robot_database"))
        assertEquals(false, ExternalAsFile.hasAsExtension("r10.as.txt"))
    }

    @Test
    fun readLimited_leAteOLimite() {
        val data = ByteArray(10_000) { 'a'.code.toByte() }
        assertArrayEquals(data, ExternalAsFile.readLimited(ByteArrayInputStream(data), maxBytes = 10_000))
    }

    @Test
    fun readLimited_recusaAcimaDoLimite() {
        val data = ByteArray(10_001) { 'a'.code.toByte() }
        assertNull(ExternalAsFile.readLimited(ByteArrayInputStream(data), maxBytes = 10_000))
    }

    @Test
    fun decodeText_recusaBinario() {
        assertEquals(".PROGRAM main()\n.END", ExternalAsFile.decodeText(".PROGRAM main()\n.END".toByteArray()))
        assertNull(ExternalAsFile.decodeText(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x14)))
    }
}
