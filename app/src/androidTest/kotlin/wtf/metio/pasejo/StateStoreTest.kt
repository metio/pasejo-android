// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import wtf.metio.pasejo.core.Device
import wtf.metio.pasejo.core.generateSecret
import wtf.metio.pasejo.core.softwareAgreement
import wtf.metio.pasejo.core.softwareSigner

@RunWith(AndroidJUnit4::class)
class StateStoreTest {
    private val alias = "pasejo.state.test"
    private lateinit var file: File
    private lateinit var states: StateStore

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.noBackupFilesDir, "test.state").apply { delete() }
        states = StateStore(file, alias)
    }

    @After
    fun tearDown() = states.delete()

    @Test
    fun aStateRoundTrips() {
        assertNull(states.load())
        states.save("""{"session":"token"}""")
        assertEquals("""{"session":"token"}""", states.load())
        states.save("second")
        assertEquals("second", StateStore(file, alias).load())
    }

    @Test
    fun concurrentSavesLeaveOneWholeState() {
        val first = "a".repeat(64 * 1024)
        val second = "b".repeat(64 * 1024)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads = listOf(first, second).map { state ->
            Thread {
                repeat(50) { runCatching { states.save(state) }.onFailure(failures::add) }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertEquals(emptyList<Throwable>(), failures.toList())
        val saved = states.load()
        assert(saved == first || saved == second) { "the saved state is neither: ${saved?.take(16)}" }
    }

    @Test
    fun theFileIsNotReadable() {
        states.save("session-token-in-the-clear")
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("session-token"))
    }

    @Test
    fun aTamperedFileIsRefused() {
        states.save("state")
        val bytes = file.readBytes()
        bytes[20] = (bytes[20].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        try {
            states.load()
            fail("a tampered state loaded")
        } catch (_: javax.crypto.AEADBadTagException) {
        }
    }

    @Test
    fun deletingRemovesTheKey() {
        states.save("state")
        states.delete()
        assertFalse(file.exists())
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
    }

    @Test
    fun aDeviceStateSurvivesTheStore() {
        val seed = generateSecret()
        val device = Device.create(
            "https://pasejo.example",
            "phone",
            softwareSigner(seed.copyOf()),
            softwareAgreement(generateSecret()).publicKey(),
            TrustAnchors.system(),
        )
        states.save(device.state())
        val restored = Device.restore(states.load()!!, softwareSigner(seed), TrustAnchors.system())
        assertEquals(device.deviceId(), restored.deviceId())
    }

    @Test
    fun thePhoneTrustsCertificateAuthorities() {
        val anchors = TrustAnchors.system()
        assertTrue("${anchors.size} anchors", anchors.size > 50)
        assertTrue(anchors.all { it[0] == 0x30.toByte() })
    }
}
