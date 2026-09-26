package my.robots.core.common

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Lê com segurança um arquivo AS que vem de FORA do app (outro app pedindo "abrir com" ou o
 * seletor de arquivos da importação).
 *
 * O que é conferido:
 * - só URIs `content://` (um `file://` de outro app poderia apontar para os arquivos privados
 *   do próprio MyRobots, como o banco com as senhas);
 * - tamanho máximo (MAX_BYTES): ler um arquivo enorme inteiro na memória derrubava o app;
 * - texto de verdade (sem byte nulo);
 * - extensão .as/.pg, quando `requireAsExtension` é true (caso do "abrir com").
 */
object ExternalAsFile {

    /** Maior arquivo aceito. Um SAVE/FULL costuma ter poucos MB. */
    const val MAX_BYTES: Long = 20L * 1024 * 1024

    private val AS_EXTENSIONS = setOf("as", "pg")

    /** Resultado da leitura: o arquivo lido, ou o motivo (em português, para mostrar) da recusa. */
    sealed interface Result {
        data class Ok(val fileName: String, val content: String) : Result
        data class Rejected(val reason: String) : Result
    }

    /** true se o nome termina em .as ou .pg (sem diferenciar maiúsculas). */
    fun hasAsExtension(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in AS_EXTENSIONS

    /**
     * Lê no máximo `maxBytes` bytes. Devolve null se o arquivo for maior que isso (sem
     * carregar o resto na memória).
     */
    fun readLimited(input: InputStream, maxBytes: Long = MAX_BYTES): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * Transforma os bytes em texto (UTF-8, como o app sempre leu). Devolve null se houver byte
     * nulo, sinal de arquivo binário.
     */
    fun decodeText(bytes: ByteArray): String? {
        if (bytes.any { it == 0.toByte() }) return null
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Lê o arquivo do `uri` aplicando todas as conferências. Faz E/S: chame fora da thread
     * principal (Dispatchers.IO).
     */
    fun read(context: Context, uri: Uri, requireAsExtension: Boolean): Result {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return Result.Rejected("Só é possível abrir arquivos compartilhados por outro app (content://).")
        }
        val resolver = context.contentResolver
        val fileName = FileUtil.getFileName(context, uri) ?: "arquivo.as"
        if (requireAsExtension && !hasAsExtension(fileName)) {
            return Result.Rejected("\"$fileName\" não é um arquivo AS (.as ou .pg).")
        }

        val declaredSize = try {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index != -1 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else null
            }
        } catch (e: Exception) {
            null
        }
        if (declaredSize != null && declaredSize > MAX_BYTES) {
            return Result.Rejected(tooBigMessage(fileName))
        }

        val bytes = try {
            resolver.openInputStream(uri)?.use { readLimited(it) }
                ?: return Result.Rejected("Não foi possível abrir \"$fileName\".")
        } catch (e: Exception) {
            return Result.Rejected("Não foi possível ler \"$fileName\": ${e.message ?: "erro de leitura"}.")
        }
        if (bytes == null) return Result.Rejected(tooBigMessage(fileName))

        val content = decodeText(bytes)
            ?: return Result.Rejected("\"$fileName\" não parece ser um arquivo de texto AS.")
        return Result.Ok(fileName, content)
    }

    private fun tooBigMessage(fileName: String) =
        "\"$fileName\" é maior que ${MAX_BYTES / (1024 * 1024)} MB e não foi aberto."
}
