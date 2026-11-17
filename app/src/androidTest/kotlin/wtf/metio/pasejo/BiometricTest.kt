// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import wtf.metio.pasejo.core.generateSecret
import wtf.metio.pasejo.core.softwareAgreement

/**
 * The age identity of both tiers opens only after the person authenticates
 * in the system prompt. A person at a phone with a secure lock screen runs
 * it with `-Pandroid.testInstrumentationRunnerArguments.biometric=true` and
 * authenticates whenever the prompt appears; otherwise every test skips.
 */
@RunWith(AndroidJUnit4::class)
class BiometricTest {
    private lateinit var dir: File
    private lateinit var keys: DeviceKeys

    @Before
    fun setUp() {
        assumeTrue(
            "needs a person at the phone",
            InstrumentationRegistry.getArguments().getString("biometric") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        dir = File(context.noBackupFilesDir, "biometric-keys").apply { deleteRecursively() }
        keys = DeviceKeys(dir)
    }

    @After
    fun tearDown() {
        if (::keys.isInitialized) keys.destroy()
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    private fun authenticate(cipher: Cipher?): Cipher? {
        val done = CountDownLatch(1)
        var outcome: Result<Cipher?> = Result.failure(IllegalStateException("no answer"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                Unlocker.authenticate(activity, cipher) {
                    outcome = it
                    done.countDown()
                }
            }
            assertTrue("nobody authenticated", done.await(PROMPT_SECONDS, TimeUnit.SECONDS))
        }
        return outcome.getOrThrow()
    }

    @Test
    fun theHardwareTierAgreesOnlyAfterAnAuthentication() {
        keys.create(Tier.HARDWARE)
        val peer = softwareAgreement(generateSecret())
        // An earlier unlock of the phone opens the key for the window too.
        Thread.sleep((DeviceKeys.AUTHENTICATION_WINDOW_SECONDS + 1) * 1000L)
        assertThrows(Exception::class.java) { keys.agreement(null).agree(peer.publicKey()) }
        authenticate(null)
        assertArrayEquals(peer.agree(keys.agreementPublicKey()), keys.agreement(null).agree(peer.publicKey()))
    }

    @Test
    fun theSealedTierOpensOnlyWithTheAuthenticatedCipher() {
        keys.create(Tier.SEALED)
        val peer = softwareAgreement(generateSecret())
        assertThrows(Exception::class.java) { keys.agreement(keys.unsealCipher()) }
        val cipher = authenticate(keys.unsealCipher())
        assertArrayEquals(peer.agree(keys.agreementPublicKey()), keys.agreement(cipher).agree(peer.publicKey()))
    }

    private companion object {
        const val PROMPT_SECONDS = 60L
    }
}
