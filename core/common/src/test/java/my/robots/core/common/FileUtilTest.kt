package my.robots.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

class FileUtilTest {

    @Test
    fun sanitizeFileName_limpaNomeEMantemExtensao() {
        assertEquals("meu_robo_1_.as", FileUtil.sanitizeFileName("meu robo[1].as"))
        assertEquals("r10_20260925_1000.as", FileUtil.sanitizeFileName("r10_20260925_1000.as"))
        assertEquals("BACKUP.AS", FileUtil.sanitizeFileName("BACKUP.AS"))
        assertEquals("sem_extensao", FileUtil.sanitizeFileName("sem extensao"))
    }

    @Test
    fun sanitizeFileName_naoDeixaBarraNaExtensao() {
        assertEquals("x.a_b", FileUtil.sanitizeFileName("x.a/b"))
        assertEquals("x.a_b", FileUtil.sanitizeFileName("x.a\\b"))
    }

    @Test
    fun sanitizeFileName_naoDeixaSubirPasta() {
        val result = FileUtil.sanitizeFileName("../../databases/robot_database.as")
        assertEquals(false, result.contains("/"))
        assertEquals(false, result.contains(".."))
        assertEquals(true, result.endsWith(".as"))
    }
}
