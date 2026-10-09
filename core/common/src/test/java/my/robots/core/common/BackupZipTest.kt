package my.robots.core.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** "Enviar backups": um .zip com um arquivo separado por robô. */
class BackupZipTest {

    @Test
    fun um_arquivo_por_robo_com_o_conteudo_igual() {
        val entries = listOf(
            BackupZip.Entry("R10", "R10_20261005_0857.as", ".PROGRAM pg100()\n.END\n".toByteArray()),
            BackupZip.Entry("R11", "R11_20261005_0900.as", "fr_[100] 1 2 3\n".toByteArray())
        )
        val out = ByteArrayOutputStream()
        BackupZip.write(entries, out)

        val lidos = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                lidos[e.name] = zip.readBytes().decodeToString()
            }
        }
        assertEquals(listOf("R10_20261005_0857.as", "R11_20261005_0900.as"), lidos.keys.toList())
        assertEquals(".PROGRAM pg100()\n.END\n", lidos["R10_20261005_0857.as"])
        assertEquals("fr_[100] 1 2 3\n", lidos["R11_20261005_0900.as"])
    }

    @Test
    fun nome_do_robo_na_frente_e_sem_repetir() {
        val nomes = BackupZip.entryNames(
            listOf(
                "R12" to "backup_full.as",       // não começa com o robô: ganha o nome dele
                "R13" to "backup_full.as",
                "R10" to "R10_20261005_0857.as",
                "R10" to "R10_20261005_0857.as", // repetido: _2
                "C 01" to "../x/C01 a.as"         // só o nome, limpo
            )
        )
        assertEquals(
            listOf("R12_backup_full.as", "R13_backup_full.as", "R10_20261005_0857.as", "R10_20261005_0857_2.as", "C_01_C01_a.as"),
            nomes
        )
    }
}
