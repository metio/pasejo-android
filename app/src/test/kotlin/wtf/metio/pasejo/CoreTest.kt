// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import wtf.metio.pasejo.core.Device
import wtf.metio.pasejo.core.DeviceSigner
import wtf.metio.pasejo.core.MobileException
import wtf.metio.pasejo.core.generateSecret
import wtf.metio.pasejo.core.packageDomains
import wtf.metio.pasejo.core.recipient
import wtf.metio.pasejo.core.softwareAgreement
import wtf.metio.pasejo.core.softwareSigner
import wtf.metio.pasejo.core.verifyAppDomain

/** The bindings against the core built for this computer. */
class CoreTest {
    private val server = "https://pasejo.example"

    private fun device(signer: DeviceSigner = softwareSigner(generateSecret())) =
        Device.create(server, "test", signer, softwareAgreement(generateSecret()).publicKey(), emptyList())

    @Test
    fun agreementsMeet() {
        val alice = softwareAgreement(generateSecret())
        val bob = softwareAgreement(generateSecret())
        assertArrayEquals(alice.agree(bob.publicKey()), bob.agree(alice.publicKey()))
    }

    @Test
    fun secretsAreFresh() {
        assertEquals(32, generateSecret().size)
        assertFalse(generateSecret().contentEquals(generateSecret()))
    }

    @Test
    fun recipientsAreAgeRecipients() {
        assertTrue(recipient(softwareAgreement(generateSecret()).publicKey()).startsWith("age1"))
    }

    @Test(expected = MobileException.Key::class)
    fun shortKeysAreRefused() {
        softwareAgreement(ByteArray(31))
    }

    @Test
    fun packageNamesSpellDomains() {
        assertEquals(listOf("example.com"), packageDomains("com.example.mail"))
        assertEquals(listOf("example.co.uk"), packageDomains("uk.co.example.app"))
        assertEquals(emptyList<String>(), packageDomains("app"))
    }

    @Test
    fun onlyHttpsServersAreAccepted() {
        for (url in listOf("http://pasejo.example", "pasejo.example", "ftp://pasejo.example")) {
            try {
                Device.create(url, "test", softwareSigner(generateSecret()), softwareAgreement(generateSecret()).publicKey(), emptyList())
                fail("$url was accepted")
            } catch (_: MobileException.Invalid) {
            }
        }
    }

    @Test
    fun aSignerReturningInvalidSignaturesIsRefused() {
        val honest = softwareSigner(generateSecret())
        val lying = object : DeviceSigner {
            override fun publicKey() = honest.publicKey()
            override fun sign(message: ByteArray) = honest.sign(message).also { it[0] = (it[0].toInt() xor 1).toByte() }
        }
        try {
            device(lying)
            fail("a lying signer was accepted")
        } catch (_: MobileException.Key) {
        }
    }

    @Test
    fun aStateRestoresOnlyWithItsSigner() {
        val seed = generateSecret()
        val created = device(softwareSigner(seed.copyOf()))
        val restored = Device.restore(created.state(), softwareSigner(seed), emptyList())
        assertEquals(created.deviceId(), restored.deviceId())
        assertEquals(created.fingerprint(), restored.fingerprint())
        try {
            Device.restore(created.state(), softwareSigner(generateSecret()), emptyList())
            fail("another signer restored the device")
        } catch (_: MobileException) {
        }
    }

    @Test
    fun theStateCarriesNoPrivateKey() {
        val seed = generateSecret()
        val state = device(softwareSigner(seed.copyOf())).state()
        val encoded = java.util.Base64.getEncoder().encodeToString(seed)
        assertFalse(state.contains(encoded))
        assertFalse(state.contains(encoded.trimEnd('=')))
    }

    @Test
    fun eachDeviceIsNew() {
        assertNotEquals(device().deviceId(), device().deviceId())
    }

    @Test
    fun aNewDeviceIsSignedOutAndLocked() {
        val created = device()
        assertFalse(created.signedIn())
        assertFalse(created.unlocked())
        assertEquals(emptyList<Any>(), created.stores())
        try {
            created.enrollment()
            fail("a device without organization reported an enrollment")
        } catch (_: MobileException) {
        }
    }

    @Test
    fun theSignInGoesToTheServer() {
        val url = device().startSignIn(MainActivity.REDIRECT)
        assertTrue(url, url.startsWith("$server/"))
        assertTrue(url, url.contains("challenge="))
    }

    @Test
    fun aForeignRedirectFinishesNoSignIn() {
        val created = device()
        try {
            created.finishSignIn("wtf.metio.pasejo:/auth?code=x&state=y")
            fail("finished a sign-in that never started")
        } catch (_: MobileException.Invalid) {
        }
    }

    @Test
    fun appDomainsMustBeHostNames() {
        for (domain in listOf("", "example.com/evil", "user@example.com", "exa mple.com")) {
            try {
                verifyAppDomain(domain, "com.example", listOf("AB".repeat(32)))
                fail("$domain was accepted")
            } catch (_: MobileException.Invalid) {
            }
        }
    }
}
