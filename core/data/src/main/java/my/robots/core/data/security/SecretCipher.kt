package my.robots.core.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifra e decifra segredos pequenos (a senha de login do controlador) antes de irem para o banco.
 */
interface SecretCipher {
    /** Texto guardado no banco. Valor vazio continua vazio; valor já cifrado não é cifrado de novo. */
    fun encrypt(plain: String): String

    /**
     * Texto original. Devolve o próprio valor se ele estiver em texto puro (versão antiga) e
     * null se não der para decifrar (ex.: banco restaurado de backup em outro celular — a chave
     * não sai do aparelho).
     */
    fun decrypt(stored: String): String?
}

/**
 * SecretCipher com uma chave AES-256 (GCM) do Android Keystore. A chave é criada na primeira
 * vez, fica presa ao aparelho e não entra no backup do Android: depois de trocar de celular ou
 * restaurar um backup, a senha precisa ser digitada de novo.
 */
class KeystoreSecretCipher : SecretCipher {

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "myrobots_controller_login"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: String): String {
        if (plain.isEmpty() || StoredSecret.isEncrypted(plain)) return plain
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return StoredSecret.encode(cipher.iv, cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    override fun decrypt(stored: String): String? {
        if (!StoredSecret.isEncrypted(stored)) return stored
        val parts = StoredSecret.decode(stored) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, parts.iv))
            String(cipher.doFinal(parts.cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
