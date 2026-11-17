// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerprintsTest {
    private val chrome = "F0:FD:6C:5B:41:0F:25:CB:25:C3:B5:33:46:C8:97:2F:AE:30:F8:EE:74:11:DF:91:04:80:AD:6B:2D:60:DB:83"

    @Test
    fun spellingsNormalize() {
        val plain = chrome.replace(":", "")
        assertEquals(plain, Fingerprints.normalize(chrome))
        assertEquals(plain, Fingerprints.normalize(chrome.lowercase()))
        assertEquals(plain, Fingerprints.normalize(plain.chunked(2).joinToString(" ")))
    }

    @Test
    fun sha256IsUpperCaseHex() {
        assertEquals(
            "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
            Fingerprints.sha256("abc".toByteArray()),
        )
    }

    @Test
    fun listedBrowsersAreTrustedOnlyWithTheirCertificate() {
        assertTrue(Browsers.trusted("com.android.chrome", listOf(chrome)))
        assertTrue(Browsers.trusted("com.android.chrome", listOf("00", chrome.lowercase())))
        assertFalse(Browsers.trusted("com.android.chrome", listOf("00".repeat(32))))
        assertFalse(Browsers.trusted("com.example.chrome", listOf(chrome)))
        assertFalse(Browsers.trusted("com.android.chrome", emptyList()))
    }

    @Test
    fun spkiCarriesTheRawKey() {
        val raw = ByteArray(32) { it.toByte() }
        val encoded = Spki.x25519(raw)
        assertEquals(44, encoded.size)
        assertArrayEquals(raw, Spki.raw(encoded))
    }

    @Test(expected = IllegalArgumentException::class)
    fun spkiRefusesShortKeys() {
        Spki.x25519(ByteArray(31))
    }

    @Test
    fun theX25519EncodingMatchesTheJdk() {
        val pair = java.security.KeyPairGenerator.getInstance("X25519").generateKeyPair()
        val encoded = pair.public.encoded
        assertArrayEquals(encoded, Spki.x25519(Spki.raw(encoded)))
    }
}
