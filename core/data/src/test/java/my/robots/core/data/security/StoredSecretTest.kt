package my.robots.core.data.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoredSecretTest {

    @Test
    fun encodeEDecodeVoltamAoOriginal() {
        val iv = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        val cipherText = byteArrayOf(-1, 0, 42, 127, -128)
        val stored = StoredSecret.encode(iv, cipherText)

        assertEquals(true, StoredSecret.isEncrypted(stored))
        val parts = StoredSecret.decode(stored)!!
        assertArrayEquals(iv, parts.iv)
        assertArrayEquals(cipherText, parts.cipherText)
    }

    @Test
    fun textoPuroDeVersaoAntigaNaoECifrado() {
        assertEquals(false, StoredSecret.isEncrypted("senha123"))
        assertEquals(false, StoredSecret.isEncrypted(""))
        assertNull(StoredSecret.decode("senha123"))
    }

    @Test
    fun valorCorrompidoDevolveNull() {
        assertNull(StoredSecret.decode("enc1:"))
        assertNull(StoredSecret.decode("enc1:semSeparador"))
        assertNull(StoredSecret.decode("enc1:AAAA:"))
        assertNull(StoredSecret.decode("enc1:!!!:@@@"))
    }
}
