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

    /**
     * Seções do backup que o app sabe ler. Qualquer outra seção de nível superior
     * (começa com ".") é considerada estranha ao idioma AS e é ignorada por
     * [sanitizeAsContent] ao montar a visão de leitura.
     */
    private val KNOWN_SECTIONS = setOf(
        ".TRANS", ".REALS", ".STRINGS", ".INTEGER", ".POS", ".JOINT", ".POINT",
        ".SPRDB", ".ERRLOG", ".OPELOG", ".PGM_EDT_LOG"
    )

    /**
     * Monta uma VISÃO do texto do backup, só para leitura, sem as seções que o
     * controlador às vezes anexa e que não são do idioma AS — ex.: ".pdump", ".dmesg",
     * ".messages", ".odt", ".interrupts" (despejos de diagnóstico do sistema Linux do
     * controlador, vistos em backups Full de controladores mais novos). Essas seções não
     * têm ".END" e podem ter dezenas de milhares de linhas, o que atrapalha quem tenta
     * contar/extrair os programas e variáveis reais do backup.
     *
     * IMPORTANTE: isso NUNCA é salvo de volta no banco ou no arquivo — o texto original
     * do backup fica sempre intacto, do jeito que o robô mandou. Esta função só serve
     * para o app "olhar através" das seções estranhas na hora de contar/extrair dados
     * (ex.: RobotRepository.calculateAndApplyMetadata, RobotDashboardViewModel).
     *
     * Como funciona: rastreia o início e o fim de cada seção. Mantém ".PROGRAM ... .END"
     * sempre inteiro, as seções conhecidas ([KNOWN_SECTIONS]) e qualquer linha fora de
     * uma seção. Uma seção desconhecida é pulada até a próxima seção que o app reconhece
     * (não depende de achar ".END", já que essas não têm um).
     */
    fun sanitizeAsContent(content: String): String {
        val result = StringBuilder()
        var isInsideProgram = false
        var isSkippingUnknownSection = false

        content.lineSequence().forEach { line ->
            val trimmed = line.trim()

            if (trimmed.startsWith(".PROGRAM", ignoreCase = true)) {
                isInsideProgram = true
                isSkippingUnknownSection = false
                result.append(line).append("\n")
                return@forEach
            }

            if (isInsideProgram) {
                result.append(line).append("\n")
                if (trimmed.equals(".END", ignoreCase = true)) isInsideProgram = false
                return@forEach
            }

            if (trimmed.startsWith(".")) {
                if (trimmed.equals(".END", ignoreCase = true)) {
                    isSkippingUnknownSection = false
                    result.append(line).append("\n")
                    return@forEach
                }
                val header = trimmed.takeWhile { !it.isWhitespace() }.uppercase()
                isSkippingUnknownSection = header !in KNOWN_SECTIONS
                if (!isSkippingUnknownSection) {
                    result.append(line).append("\n")
                }
                return@forEach
            }

            if (!isSkippingUnknownSection) {
                result.append(line).append("\n")
            }
        }

        return result.toString().trimEnd('\n')
    }
}
