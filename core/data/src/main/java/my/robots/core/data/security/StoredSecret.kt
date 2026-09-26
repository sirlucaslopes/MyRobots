package my.robots.core.data.security

import java.util.Base64

/**
 * Formato de um segredo cifrado guardado no banco: "enc1:<iv em base64>:<texto cifrado em base64>".
 *
 * Um valor sem o prefixo é texto puro de versões antigas (até a v1.1 a senha do controlador
 * ficava assim) e é cifrado na primeira abertura da v1.2 (RobotRepository.encryptLegacyPasswords).
 */
object StoredSecret {
    private const val PREFIX = "enc1:"

    /** Um valor cifrado, separado em vetor de inicialização (iv) e texto cifrado. */
    class Parts(val iv: ByteArray, val cipherText: ByteArray)

    /** true se o valor já está no formato cifrado. */
    fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

    /** Monta o texto guardado no banco a partir do iv e do texto cifrado. */
    fun encode(iv: ByteArray, cipherText: ByteArray): String {
        val encoder = Base64.getEncoder()
        return PREFIX + encoder.encodeToString(iv) + ":" + encoder.encodeToString(cipherText)
    }

    /** Separa um valor cifrado. Devolve null se não estiver no formato (ou estiver corrompido). */
    fun decode(stored: String): Parts? {
        if (!isEncrypted(stored)) return null
        val body = stored.removePrefix(PREFIX)
        val separator = body.indexOf(':')
        if (separator <= 0 || separator == body.lastIndex) return null
        return try {
            val decoder = Base64.getDecoder()
            Parts(decoder.decode(body.substring(0, separator)), decoder.decode(body.substring(separator + 1)))
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
