// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import wtf.metio.pasejo.core.Device
import wtf.metio.pasejo.core.FillRequest
import wtf.metio.pasejo.core.MobileException
import wtf.metio.pasejo.core.generateSecret
import wtf.metio.pasejo.core.softwareAgreement
import wtf.metio.pasejo.core.softwareSigner

/**
 * The bindings against a pasejo server this test starts: the parts of the
 * flow that need no identity provider. The flows behind sign-in are
 * covered against the same server in `crates/pasejo-server/tests/mobile.rs`.
 */
class ServerTest {
    companion object {
        private lateinit var dir: File
        private lateinit var server: Process
        private lateinit var url: String

        private fun binary() = File(requireNotNull(System.getProperty("pasejo.server")) { "pasejo.server is not set" })

        @BeforeClass
        @JvmStatic
        fun start() {
            dir = Files.createTempDirectory("pasejo-server").toFile()
            val key = ProcessBuilder(binary().path, "keygen").start().run {
                val text = inputStream.bufferedReader().readText().trim()
                check(waitFor() == 0) { "keygen failed" }
                text
            }
            File(dir, "master.key").writeText(key)
            val port = ServerSocket(0).use { it.localPort }
            url = "http://127.0.0.1:$port"
            File(dir, "pasejo-server.toml").writeText(
                """
                listen = "127.0.0.1:$port"
                public_url = "$url"
                database_url = "sqlite://${File(dir, "pasejo.db").path}?mode=rwc"
                master_key_file = "${File(dir, "master.key").path}"
                """.trimIndent(),
            )
            server = ProcessBuilder(binary().path, "--config", File(dir, "pasejo-server.toml").path, "serve")
                .redirectErrorStream(true)
                .redirectOutput(File(dir, "server.log"))
                .start()
            val deadline = System.currentTimeMillis() + 30_000
            while (!ready()) {
                check(server.isAlive) { "the server exited: ${File(dir, "server.log").readText()}" }
                check(System.currentTimeMillis() < deadline) { "the server did not become ready" }
                Thread.sleep(100)
            }
        }

        private fun ready(): Boolean = try {
            val connection = URI("$url/readyz").toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 500
            connection.responseCode == 200
        } catch (_: java.io.IOException) {
            false
        }

        @AfterClass
        @JvmStatic
        fun stop() {
            server.destroy()
            server.waitFor()
            dir.deleteRecursively()
        }
    }

    private fun device(seed: ByteArray = generateSecret()) =
        Device.create(url, "test", softwareSigner(seed), softwareAgreement(generateSecret()).publicKey(), emptyList())

    @Test
    fun theServerDescribesItselfAndItsCheckpointKeyIsPinned() {
        val seed = generateSecret()
        val phone = device(seed.copyOf())
        val info = phone.connect(null)
        assertTrue(info.version.isNotEmpty())
        assertEquals(info, phone.connect(info.checkpointKey))
        val restored = Device.restore(phone.state(), softwareSigner(seed), emptyList())
        assertEquals(info.checkpointKey, restored.connect(null).checkpointKey)
    }

    @Test
    fun anotherCheckpointKeyIsRefused() {
        val other = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })
        try {
            device().connect(other)
            fail("a foreign checkpoint key was accepted")
        } catch (_: MobileException.Verification) {
        }
    }

    private fun status(url: String) = (URI(url).toURL().openConnection() as HttpURLConnection).run {
        instanceFollowRedirects = false
        responseCode
    }

    @Test
    fun theServerAcceptsOnlyTheAppsRedirect() {
        val phone = device()
        val start = phone.startSignIn(MainActivity.REDIRECT)
        assertTrue(start.startsWith("$url/auth/app/start?"))
        // The redirect and the PKCE challenge pass; the server then has no
        // identity provider to send the browser to.
        assertEquals(503, status(start))
        assertEquals(400, status(phone.startSignIn("https://attacker.example/auth")))
    }

    @Test
    fun aMadeUpCodeIsRefusedByTheServer() {
        val phone = device()
        val start = phone.startSignIn(MainActivity.REDIRECT)
        val state = URI(start).rawQuery.split('&').map { it.split('=', limit = 2) }
            .first { it[0] == "state" }[1].let { URLDecoder.decode(it, Charsets.UTF_8) }
        try {
            phone.finishSignIn("${MainActivity.REDIRECT}?code=made-up&state=$state")
            fail("the server accepted a made-up code")
        } catch (error: MobileException) {
            assertFalse(error is MobileException.Network)
        }
        assertFalse(phone.signedIn())
    }

    @Test
    fun withoutSessionTheServerRefusesToEnroll() {
        val org = "0".repeat(32)
        val genesis = "A".repeat(43)
        try {
            device().join(org, genesis)
            fail("enrolled without a session")
        } catch (error: MobileException) {
            assertFalse("$error", error is MobileException.Network)
        }
    }

    @Test
    fun aLockedDeviceAnswersNothing() {
        try {
            device().query(FillRequest(listOf("example.com"), null), null)
            fail("a locked device answered a query")
        } catch (_: MobileException.Locked) {
        }
    }

    @Test
    fun anUnreachableServerIsANetworkError() {
        val port = ServerSocket(0).use { it.localPort }
        val phone = Device.create("http://127.0.0.1:$port", "test", softwareSigner(generateSecret()), softwareAgreement(generateSecret()).publicKey(), emptyList())
        try {
            phone.connect(null)
            fail("connected to a closed port")
        } catch (_: MobileException.Network) {
        }
    }
}
