package my.robots.core.data.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import my.robots.core.network.RobotFileInfo
import my.robots.core.network.RobotFileStore
import my.robots.core.network.TransferFileNames
import java.io.IOException
import java.io.OutputStream

/**
 * Pasta escolhida pelo usuário pelo seletor de pastas do Android (Storage Access Framework).
 * A estrutura é a mesma da pasta padrão: <pasta escolhida>/<robô>/<arquivo>.as.
 *
 * Diferente do MediaStore, aqui o app enxerga TUDO o que está na pasta (inclusive .as copiados
 * pelo PC). A permissão é guardada com takePersistableUriPermission (ver RobotFilesStorage);
 * depois de reinstalar o app, basta escolher a mesma pasta de novo para voltar a ver tudo.
 */
class SafRobotFileStore(private val context: Context, val treeUri: Uri) : RobotFileStore {

    private val resolver get() = context.contentResolver

    private companion object {
        /** Mesmo motivo do MediaStore: não deixar o provedor trocar a extensão .as. */
        const val MIME = "application/octet-stream"
    }

    /** Raiz escolhida, ou null se a permissão foi perdida (pasta apagada ou revogada). */
    private fun root(): DocumentFile? =
        DocumentFile.fromTreeUri(context, treeUri)?.takeIf { it.exists() && it.canWrite() }

    /** true se a pasta ainda existe e o app ainda pode gravar nela. */
    fun isAvailable(): Boolean = root() != null

    private fun robotDir(robotName: String, create: Boolean): DocumentFile? {
        val root = root() ?: return null
        val dirName = RobotFileStore.robotDirName(robotName)
        root.findFile(dirName)?.takeIf { it.isDirectory }?.let { return it }
        return if (create) root.createDirectory(dirName) else null
    }

    private fun findFile(robotName: String, fileName: String): DocumentFile? =
        robotDir(robotName, create = false)?.findFile(fileName)?.takeIf { it.isFile }

    override fun list(robotName: String): List<RobotFileInfo> = try {
        robotDir(robotName, create = false)?.listFiles()
            ?.filter { it.isFile && it.name?.endsWith(".as", ignoreCase = true) == true }
            ?.map { RobotFileInfo(it.name!!, it.lastModified()) }
            ?: emptyList()
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }

    override fun read(robotName: String, fileName: String): ByteArray? {
        val safe = TransferFileNames.safeName(fileName) ?: return null
        val file = findFile(robotName, safe) ?: return null
        return try {
            resolver.openInputStream(file.uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun openOutput(robotName: String, fileName: String): OutputStream {
        val safe = RobotFileStore.requireSafeName(fileName)
        val dir = robotDir(robotName, create = true)
            ?: throw IOException("A pasta escolhida não está mais disponível. Escolha a pasta de novo.")
        val file = dir.findFile(safe)?.takeIf { it.isFile }
            ?: dir.createFile(MIME, safe)
            ?: throw IOException("Não foi possível criar $safe na pasta escolhida")
        return resolver.openOutputStream(file.uri, "wt")
            ?: throw IOException("Não foi possível abrir $safe para gravar")
    }

    override fun delete(robotName: String, fileName: String): Boolean {
        val safe = TransferFileNames.safeName(fileName) ?: return false
        return try {
            findFile(robotName, safe)?.delete() ?: false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
