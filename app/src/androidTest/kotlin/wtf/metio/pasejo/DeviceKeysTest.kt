// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import wtf.metio.pasejo.core.Device
import wtf.metio.pasejo.core.generateSecret
import wtf.metio.pasejo.core.recipient
import wtf.metio.pasejo.core.softwareAgreement

/**
 * Runs on a phone or emulator without a lock screen, so the keys are
 * created without the authentication requirement the app sets. Emulators
 * keep keys in software, so only [everyKeyIsInSecureHardware] and
 * [softwareKeysAreRefused] require or refuse secure hardware.
 */
@RunWith(AndroidJUnit4::class)
class DeviceKeysTest {
    private lateinit var dir: File
    private lateinit var keys: DeviceKeys

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        dir = File(context.noBackupFilesDir, "test-keys").apply { deleteRecursively() }
        keys = DeviceKeys(dir, requireAuthentication = false, requireHardware = false)
    }

    @After
    fun tearDown() {
        keys.destroy()
        dir.deleteRecursively()
    }

    private fun secure(level: Int) =
        level == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT || level == KeyProperties.SECURITY_LEVEL_STRONGBOX

    @Test
    fun everyKeyIsInSecureHardware() {
        val strict = DeviceKeys(dir, requireAuthentication = false)
        for (tier in Tier.entries) {
            try {
                strict.create(tier)
            } catch (error: InsecureKeyStoreException) {
                org.junit.Assume.assumeNoException(error)
            }
            for ((alias, level) in strict.securityLevels()) {
                assertTrue("$alias has security level $level", secure(level))
            }
        }
    }

    @Test
    fun softwareKeysAreRefused() {
        keys.create(Tier.SEALED)
        org.junit.Assume.assumeFalse(keys.securityLevels().values.all(::secure))
        val strict = DeviceKeys(dir, requireAuthentication = false)
        for (tier in Tier.entries) {
            try {
                strict.create(tier)
                fail("$tier keys in software were accepted")
            } catch (_: InsecureKeyStoreException) {
            }
            assertNull(strict.tier)
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun hardwareKeysSignForADevice() {
        keys.create(Tier.HARDWARE)
        assertEquals(Tier.HARDWARE, keys.tier)
        val signer = keys.signer()
        assertEquals(32, signer.publicKey().size)
        Device.create("https://pasejo.example", "phone", signer, keys.agreementPublicKey(), emptyList())
    }

    @Test
    fun hardwareAgreementMatchesSoftwareAgreement() {
        keys.create(Tier.HARDWARE)
        val peerSecret = generateSecret()
        val peer = softwareAgreement(peerSecret)
        val agreement = keys.agreement(keys.unsealCipher())
        val inside = agreement.agree(peer.publicKey())
        val outside = peer.agree(keys.agreementPublicKey())
        assertArrayEquals(outside, inside)
    }

    @Test
    fun sealedKeysSurviveANewInstance() {
        keys.create(Tier.SEALED)
        val first = keys.signer().publicKey()
        val reopened = DeviceKeys(dir, requireAuthentication = false)
        assertEquals(Tier.SEALED, reopened.tier)
        assertArrayEquals(first, reopened.signer().publicKey())
        Device.create("https://pasejo.example", "phone", reopened.signer(), reopened.agreementPublicKey(), emptyList())
    }

    @Test
    fun sealedAgreementOpensToTheAdvertisedKey() {
        keys.create(Tier.SEALED)
        val cipher = keys.unsealCipher()
        assertNotNull(cipher)
        val agreement = keys.agreement(cipher)
        assertArrayEquals(keys.agreementPublicKey(), agreement.publicKey())
        val peer = softwareAgreement(generateSecret())
        assertArrayEquals(peer.agree(keys.agreementPublicKey()), agreement.agree(peer.publicKey()))
    }

    @Test
    fun theRecipientIsAnAgeRecipient() {
        keys.create(Tier.SEALED)
        assertTrue(recipient(keys.agreementPublicKey()).startsWith("age1"))
    }

    @Test
    fun theSealedTierKeepsNoSecretInTheClear() {
        keys.create(Tier.SEALED)
        val secretFiles = dir.listFiles().orEmpty().filter { it.name.endsWith(".sealed") }
        assertEquals(2, secretFiles.size)
        // A sealed seed carries a 12-byte IV and a 16-byte tag around the
        // 32 secret bytes; an RSA-OAEP block is 384 bytes.
        assertEquals(60, File(dir, "signing.sealed").length())
        assertEquals(384, File(dir, "agreement.sealed").length())
    }

    @Test
    fun theSealedTierNeedsTheAuthenticatedCipher() {
        keys.create(Tier.SEALED)
        try {
            keys.agreement(null)
            fail("the sealed tier opened without a cipher")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun aTamperedSealedSeedFails() {
        keys.create(Tier.SEALED)
        val file = File(dir, "signing.sealed")
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        try {
            keys.signer()
            fail("a tampered seed unsealed")
        } catch (_: javax.crypto.AEADBadTagException) {
        }
    }

    @Test
    fun destroyRemovesEveryKey() {
        keys.create(Tier.HARDWARE)
        keys.destroy()
        assertNull(keys.tier)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        for (alias in listOf(DeviceKeys.SIGNING, DeviceKeys.AGREEMENT, DeviceKeys.SEAL_SIGNING, DeviceKeys.SEAL_AGREEMENT)) {
            assertFalse(alias, store.containsAlias(alias))
        }
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aSignerWithoutKeysFails() {
        try {
            keys.signer()
            fail("signed without keys")
        } catch (_: IllegalStateException) {
        }
    }
}
