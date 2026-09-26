package my.robots.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TransferFileNamesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun safeName_aceitaNomesNormaisDoControlador() {
        assertEquals("r10_20260925_1000.as", TransferFileNames.safeName("r10_20260925_1000.as"))
        assertEquals("BACKUP.AS", TransferFileNames.safeName("  BACKUP.AS "))
        assertEquals("transfer_pg-1.as", TransferFileNames.safeName("transfer_pg-1.as"))
    }

    @Test
    fun safeName_recusaCaminhos() {
        assertNull(TransferFileNames.safeName("../../data/data/my.robots/databases/robot_database"))
        assertNull(TransferFileNames.safeName(".."))
        assertNull(TransferFileNames.safeName("a..b"))
        assertNull(TransferFileNames.safeName("/sdcard/x.as"))
        assertNull(TransferFileNames.safeName("pasta/x.as"))
        assertNull(TransferFileNames.safeName("pasta\\x.as"))
        assertNull(TransferFileNames.safeName("C:x.as"))
    }

    @Test
    fun safeName_recusaVazioOcultoLongoEEspaco() {
        assertNull(TransferFileNames.safeName(""))
        assertNull(TransferFileNames.safeName("   "))
        assertNull(TransferFileNames.safeName(".oculto"))
        assertNull(TransferFileNames.safeName("meu arquivo.as"))
        assertNull(TransferFileNames.safeName("a".repeat(TransferFileNames.MAX_LENGTH + 1)))
        assertNull(TransferFileNames.safeName("nulo\u0000.as"))
    }

    @Test
    fun resolveInside_ficaDentroDaPasta() {
        val dir = tmp.newFolder("r10")
        assertEquals(File(dir, "r10.as").canonicalPath, TransferFileNames.resolveInside(dir, "r10.as")!!.canonicalPath)
        assertNull(TransferFileNames.resolveInside(dir, "../outro/r11.as"))
        assertNull(TransferFileNames.resolveInside(dir, "../../../../etc/passwd"))
    }
}
