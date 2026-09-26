package my.robots.core.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import my.robots.core.network.RobotFileInfo
import my.robots.core.network.RobotFileStore
import java.io.OutputStream

/**
 * Onde os arquivos dos robôs estão sendo gravados.
 * - Default: Documentos/MyRobots (MediaStore), sem nenhuma permissão.
 * - Folder: pasta escolhida pelo usuário (SAF). `available` = false quando a pasta sumiu ou a
 *   permissão foi revogada; enquanto isso, o app grava na pasta padrão e a tela avisa.
 */
sealed interface StorageLocation {
    data object Default : StorageLocation
    data class Folder(val uri: Uri, val available: Boolean) : StorageLocation
}

/**
 * A "pasta dos arquivos" do app: escolhe entre a pasta padrão (MediaStore) e a pasta escolhida
 * pelo usuário (SAF), e lembra a escolha entre aberturas do app. Uma instância só, criada no
 * MyRobotsApp e usada pelo repositório e pelo terminal.
 */
class RobotFilesStorage(context: Context) : RobotFileStore {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("storage", Context.MODE_PRIVATE)
    private val mediaStore = MediaStoreRobotFileStore(appContext)
    private var saf: SafRobotFileStore? = prefs.getString(KEY_TREE_URI, null)
        ?.let { SafRobotFileStore(appContext, Uri.parse(it)) }

    private val _location = MutableStateFlow(currentLocation())
    /** Local atual, para a tela mostrar. */
    val location: StateFlow<StorageLocation> = _location.asStateFlow()

    private companion object {
        const val KEY_TREE_URI = "tree_uri"
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        const val FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }

    private fun currentLocation(): StorageLocation =
        saf?.let { StorageLocation.Folder(it.treeUri, it.isAvailable()) } ?: StorageLocation.Default

    /** Reconfere se a pasta escolhida continua disponível (ex.: ao abrir a tela). */
    fun refresh() {
        _location.value = currentLocation()
    }

    /** Passa a usar a pasta escolhida no seletor (Intent.ACTION_OPEN_DOCUMENT_TREE). */
    fun useFolder(treeUri: Uri) {
        val resolver = appContext.contentResolver
        resolver.takePersistableUriPermission(treeUri, FLAGS)
        saf?.treeUri?.takeIf { it != treeUri }?.let { releasePermission(it) }
        prefs.edit().putString(KEY_TREE_URI, treeUri.toString()).apply()
        saf = SafRobotFileStore(appContext, treeUri)
        refresh()
    }

    /** Volta para a pasta padrão Documentos/MyRobots. */
    fun useDefault() {
        saf?.treeUri?.let { releasePermission(it) }
        prefs.edit().remove(KEY_TREE_URI).apply()
        saf = null
        refresh()
    }

    private fun releasePermission(uri: Uri) {
        try {
            appContext.contentResolver.releasePersistableUriPermission(uri, FLAGS)
        } catch (e: SecurityException) {
            // já não tinha a permissão: nada a fazer
        }
    }

    /**
     * Uri para abrir a pasta no gerenciador de arquivos do Android (botão "Arquivos").
     * Na pasta padrão é o caminho conhecido de Documentos/MyRobots; o gerenciador pode não
     * conseguir abrir se a pasta ainda não tiver nenhum arquivo.
     */
    fun folderViewUri(): Uri {
        val folder = saf?.takeIf { it.isAvailable() }
        return if (folder != null) {
            DocumentsContract.buildDocumentUriUsingTree(folder.treeUri, DocumentsContract.getTreeDocumentId(folder.treeUri))
        } else {
            DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:Documents/MyRobots")
        }
    }

    /** A pasta que vale agora: a escolhida, se ainda estiver disponível; senão, a padrão. */
    private fun active(): RobotFileStore = saf?.takeIf { it.isAvailable() } ?: mediaStore

    override fun list(robotName: String): List<RobotFileInfo> = active().list(robotName)
    override fun read(robotName: String, fileName: String): ByteArray? = active().read(robotName, fileName)
    override fun openOutput(robotName: String, fileName: String): OutputStream = active().openOutput(robotName, fileName)
    override fun delete(robotName: String, fileName: String): Boolean = active().delete(robotName, fileName)
}
