package my.robots.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

object FileUtil {
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
     * Sanitiza o nome do arquivo para garantir que contenha apenas caracteres alfanuméricos e sublinhados (_).
     * Caracteres especiais como [, {, (, etc., são substituídos por _.
     */
    fun sanitizeFileName(fileName: String): String {
        val lastDotIndex = fileName.lastIndexOf('.')
        return if (lastDotIndex != -1) {
            val namePart = fileName.substring(0, lastDotIndex)
            val extensionPart = fileName.substring(lastDotIndex) // Mantém a extensão (ex: .as)
            val sanitizedName = namePart.replace(Regex("[^a-zA-Z0-9_]"), "_")
            sanitizedName + extensionPart
        } else {
            fileName.replace(Regex("[^a-zA-Z0-9_]"), "_")
        }
    }
}
