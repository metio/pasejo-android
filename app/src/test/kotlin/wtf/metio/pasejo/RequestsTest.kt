// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import wtf.metio.pasejo.core.AppRequest
import wtf.metio.pasejo.core.FillRequest

class RequestsTest {
    private val cert = "AB".repeat(32)

    @Test
    fun aBrowserAsksForItsWebDomain() {
        val caller = Caller("com.android.chrome", listOf(cert), " Login.Example.COM ")
        assertEquals(FillRequest(listOf("login.example.com"), null), Requests.build(caller, browser = true) { true })
    }

    @Test
    fun aBrowserWithoutWebDomainAsksForNothing() {
        assertNull(Requests.build(Caller("com.android.chrome", listOf(cert), null), browser = true) { true })
        assertNull(Requests.build(Caller("com.android.chrome", listOf(cert), " "), browser = true) { true })
    }

    @Test
    fun anAppsWebDomainIsIgnored() {
        val caller = Caller("com.example.mail", listOf(cert), "bank.example")
        val request = Requests.build(caller, browser = false) { false }
        assertEquals(FillRequest(emptyList(), AppRequest("com.example.mail", listOf(cert))), request)
    }

    @Test
    fun anAppGetsOnlyVerifiedDomainsOfItsPackageName() {
        val asked = mutableListOf<String>()
        val request = Requests.build(Caller("com.example.mail", listOf(cert), null), browser = false) {
            asked += it
            true
        }
        assertEquals(listOf("example.com"), asked)
        assertEquals(listOf("example.com"), request!!.domains)
        val refused = Requests.build(Caller("com.example.mail", listOf(cert), null), browser = false) { false }
        assertEquals(emptyList<String>(), refused!!.domains)
    }

    @Test
    fun anAppWithoutCertificatesAsksForNothing() {
        assertNull(Requests.build(Caller("com.example.mail", emptyList(), null), browser = false) { true })
    }
}
