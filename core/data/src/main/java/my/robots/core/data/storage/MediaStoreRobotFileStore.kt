package my.robots.core.data.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import my.robots.core.network.RobotFileInfo
import my.robots.core.network.RobotFileStore
import my.robots.core.network.TransferFileNames
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Pasta padrão dos arquivos: Documentos/MyRobots/<robô>/, gravada pelo MediaStore.
 *
 * Não precisa de nenhuma permissão de armazenamento (Android 10+). Limite importante do
 * MediaStore: o app só enxerga os arquivos que ELE MESMO criou. Um .as copiado para a pasta
 * pelo PC ou outro app não aparece aqui, e depois de desinstalar/reinstalar o app perde a
 * posse dos arquivos antigos. Para ler tudo o que está na pasta, o usuário conecta a pasta
 * pelo SAF (SafRobotFileStore).
 */
class MediaStoreRobotFileStore(private val context: Context) : RobotFileStore {

    private val resolver get() = context.contentResolver
    private val collection: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    companion object {
        /** "application/octet-stream" para o MediaStore não trocar a extensão .as (ex.: .as.txt). */
        private const val MIME = "application/octet-stream"
    }

    private fun relativePath(robotName: String) =
        "${Environment.DIRECTORY_DOCUMENTS}/MyRobots/${RobotFileStore.robotDirName(robotName)}/"

    /** Uri do arquivo (só os criados por este app), ou null se não existir. */
    private fun find(robotName: String, fileName: String): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        resolver.query(collection, projection, selection, arrayOf(relativePath(robotName), fileName), null)?.use { cursor ->
            if (cursor.moveToFirst()) return ContentUris.withAppendedId(collection, cursor.getLong(0))
        }
        return null
    }

    override fun list(robotName: String): List<RobotFileInfo> {
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_MODIFIED)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val result = mutableListOf<RobotFileInfo>()
        try {
            resolver.query(collection, projection, selection, arrayOf(relativePath(robotName)), null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    if (!name.endsWith(".as", ignoreCase = true)) continue
                    result.add(RobotFileInfo(name, cursor.getLong(1) * 1000))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    override fun read(robotName: String, fileName: String): ByteArray? {
        val safe = TransferFileNames.safeName(fileName) ?: return null
        val uri = find(robotName, safe) ?: return null
        return try {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun openOutput(robotName: String, fileName: String): OutputStream {
        val safe = RobotFileStore.requireSafeName(fileName)
        find(robotName, safe)?.let { existing ->
            return resolver.openOutputStream(existing, "wt")
                ?: throw IOException("Não foi possível abrir $safe para gravar")
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(robotName))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Não foi possível criar $safe em Documentos/MyRobots")
        val out = resolver.openOutputStream(uri, "w")
        if (out == null) {
            resolver.delete(uri, null, null)
            throw IOException("Não foi possível abrir $safe para gravar")
        }
        // enquanto IS_PENDING = 1 o arquivo fica invisível para os outros apps; libera ao fechar
        return object : FilterOutputStream(out) {
            private var closed = false
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                if (closed) return
                closed = true
                super.close()
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        }
    }

    override fun delete(robotName: String, fileName: String): Boolean {
        val safe = TransferFileNames.safeName(fileName) ?: return false
        val uri = find(robotName, safe) ?: return false
        return try {
            resolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

