package my.robots.core.common

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Um arquivo .zip com vários backups, um arquivo separado por robô ("Enviar backups" das ações
 * em grupo). O .zip abre direto no Windows, no 7-Zip e no WhatsApp, sem biblioteca extra.
 */
object BackupZip {

    /** Um backup a empacotar: o robô e o nome do arquivo dele (ex.: "R10_20261005_0857.as"). */
    data class Entry(val robotName: String, val fileName: String, val bytes: ByteArray)

    /**
     * Nome de cada arquivo dentro do .zip: o nome do backup, com o nome do robô na frente se ele
     * ainda não começar com ele (para saber de quem é), e "_2", "_3"… se repetir.
     */
    fun entryNames(entries: List<Pair<String, String>>): List<String> {
        val used = mutableSetOf<String>()
        return entries.map { (robot, file) ->
            val safeRobot = FileUtil.sanitizeFileName(robot).removeSuffix(".as")
            // só o nome, limpo: quem descompactar não recebe pasta nem caractere estranho
            val base = FileUtil.sanitizeFileName(file.substringAfterLast('/').substringAfterLast('\\').ifBlank { "$safeRobot.as" })
            val named = if (base.startsWith(safeRobot, ignoreCase = true)) base else "${safeRobot}_$base"
            val stem = named.substringBeforeLast('.', named)
            val ext = if ('.' in named) "." + named.substringAfterLast('.') else ""
            var candidate = named
            var n = 2
            while (!used.add(candidate.lowercase())) candidate = "${stem}_${n++}$ext"
            candidate
        }
    }

    /** Escreve o .zip em [out] (não fecha [out]). */
    fun write(entries: List<Entry>, out: OutputStream) {
        val names = entryNames(entries.map { it.robotName to it.fileName })
        val zip = ZipOutputStream(out)
        entries.zip(names).forEach { (e, name) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(e.bytes)
            zip.closeEntry()
        }
        zip.finish()
    }
}
