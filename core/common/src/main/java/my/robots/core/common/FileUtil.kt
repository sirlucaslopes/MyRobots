package my.robots.core.common

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Ferramentas simples para lidar com nomes de arquivos.
 * Usada por várias partes do app (importar, salvar e enviar arquivos).
 */
object FileUtil {
    /**
     * Descobre o nome de um arquivo a partir do endereço dele (Uri).
     *
     * Como funciona:
     * 1. Se o endereço é do tipo "content://", pergunta o nome para o Android.
     * 2. Se não conseguir, pega o pedaço depois da última "/" do caminho.
     * Devolve null se não achar nenhum nome.
     */
    fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    /**
     * Deixa o nome do arquivo "limpo" para o robô aceitar.
     *
     * Só permite letras, números e sublinhado (_) no nome. Qualquer outro
     * caractere (espaço, colchete, parênteses...) vira "_".
     * A extensão (ex.: ".as") é preservada.
     * Exemplo: "meu robo[1].as" vira "meu_robo_1_.as".
     */
    fun sanitizeFileName(fileName: String): String {
        val lastDotIndex = fileName.lastIndexOf('.')
        return if (lastDotIndex != -1) {
            val namePart = fileName.substring(0, lastDotIndex)
            val extensionPart = fileName.substring(lastDotIndex) // guarda a extensão à parte (ex.: .as), pois ela não pode ser mexida
            val sanitizedName = namePart.replace(Regex("[^a-zA-Z0-9_]"), "_")
            sanitizedName + extensionPart
        } else {
            fileName.replace(Regex("[^a-zA-Z0-9_]"), "_")
        }
    }
}
