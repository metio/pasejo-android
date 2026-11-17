// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkCacheTest {
    private var now = 0L
    private var calls = 0
    private var answer: () -> Boolean = { true }
    private val cache = LinkCache({ _, _, _ ->
        calls++
        answer()
    }, { now })

    @Test
    fun answersAreRememberedForADay() {
        assertTrue(cache.verified("example.com", "com.example", listOf("aa")))
        now += 24 * 60 * 60 * 1000L - 1
        assertTrue(cache.verified("example.com", "com.example", listOf("AA")))
        assertEquals(1, calls)
        now += 1
        answer = { false }
        assertFalse(cache.verified("example.com", "com.example", listOf("aa")))
        assertEquals(2, calls)
    }

    @Test
    fun keysIncludePackageAndCertificates() {
        cache.verified("example.com", "com.example", listOf("aa"))
        cache.verified("example.com", "com.other", listOf("aa"))
        cache.verified("example.com", "com.example", listOf("bb"))
        assertEquals(3, calls)
    }

    @Test
    fun failuresAreNotRemembered() {
        answer = { error("offline") }
        assertFalse(cache.verified("example.com", "com.example", listOf("aa")))
        answer = { true }
        assertTrue(cache.verified("example.com", "com.example", listOf("aa")))
        assertEquals(2, calls)
    }
}
