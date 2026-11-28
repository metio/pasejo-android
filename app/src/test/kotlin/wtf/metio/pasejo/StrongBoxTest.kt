// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import java.security.GeneralSecurityException
import java.security.ProviderException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StrongBoxTest {
    private fun refusedWith(error: Exception): List<Boolean> {
        val tries = mutableListOf<Boolean>()
        preferStrongBox { inStrongBox ->
            tries += inStrongBox
            if (inStrongBox) throw error
        }
        return tries
    }

    @Test
    fun aKeyStrongBoxTakesStaysThere() {
        val tries = mutableListOf<Boolean>()
        assertEquals("key", preferStrongBox { tries += it; "key" })
        assertEquals(listOf(true), tries)
    }

    @Test
    fun aProviderRefusalFallsBackToTheTrustedEnvironment() =
        assertEquals(listOf(true, false), refusedWith(ProviderException("unsupported size")))

    @Test
    fun aSecurityRefusalFallsBackToTheTrustedEnvironment() =
        assertEquals(listOf(true, false), refusedWith(GeneralSecurityException("unsupported algorithm")))

    @Test
    fun otherFailuresAreNotRetried() {
        assertThrows(IllegalStateException::class.java) { refusedWith(IllegalStateException("broken")) }
    }
}
