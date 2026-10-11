package my.robots.core.render3d

import java.io.File
import java.text.Normalizer
import java.util.Locale

/** Robô montado salvo no aparelho: a pasta ([id]) e o nome. */
data class SavedRobot(val id: String, val name: String)

/**
 * Os robôs montados guardados no aparelho, em `<files>/robos3d/<id>/`: o `modelo.json`
 * ([RobotAssembly.toJson]) e uma cópia do `robo.glb`. O [id] é o nome do robô sem acento nem
 * espaço ([slug]). Usado pelo montador (salvar, abrir) e pela cabine 3D (desenhar os robôs).
 * Leitura e escrita de arquivo: chamar fora da thread da tela.
 */
class RobotLibrary(filesDir: File) {

    private val root = File(filesDir, "robos3d")

    /** Robô salvo aberto: o modelo e os bytes do .glb. */
    class Entry(val id: String, val assembly: RobotAssembly, val glb: ByteArray)

    fun list(): List<SavedRobot> =
        root.listFiles()?.filter { File(it, MODEL).isFile }?.mapNotNull { dir ->
            val name = runCatching { RobotAssembly.fromJson(File(dir, MODEL).readText()).name }.getOrNull()
            name?.let { SavedRobot(dir.name, it) }
        }?.sortedBy { it.name.lowercase() }.orEmpty()

    /** Lê o robô [id]; erro se a pasta ou os arquivos não existem. */
    fun load(id: String): Entry {
        val dir = dirOf(id)
        val assembly = RobotAssembly.fromJson(File(dir, MODEL).readText())
        return Entry(id, assembly, File(dir, GLB).readBytes())
    }

    /** Só o modelo (sem o .glb), para listas; null se não existe ou não lê. */
    fun loadAssembly(id: String): RobotAssembly? =
        runCatching { RobotAssembly.fromJson(File(dirOf(id), MODEL).readText()) }.getOrNull()

    /** Salva (substitui um salvo com o mesmo nome). Devolve o id e se já existia. */
    fun save(assembly: RobotAssembly, glb: ByteArray): Pair<String, Boolean> {
        val id = slug(assembly.name)
        val dir = dirOf(id)
        val existed = dir.exists()
        dir.mkdirs()
        File(dir, GLB).writeBytes(glb)
        // escreve num temporário e troca: um salvamento cortado não estraga o anterior
        val tmp = File(dir, "$MODEL.tmp")
        tmp.writeText(assembly.toJson())
        val target = File(dir, MODEL)
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        return id to existed
    }

    fun delete(id: String) {
        dirOf(id).deleteRecursively()
    }

    /** O id vem de [slug] ou de [list]: nunca sai de `robos3d/`. */
    private fun dirOf(id: String): File {
        require(id.isNotBlank() && id == slug(id)) { "Robô salvo inválido: $id" }
        return File(root, id)
    }

    companion object {
        private const val MODEL = "modelo.json"
        private const val GLB = "robo.glb"

        /** Nome de pasta seguro a partir do nome do robô. */
        fun slug(name: String): String {
            val plain = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            return plain.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40).ifBlank { "robo" }
        }
    }
}
