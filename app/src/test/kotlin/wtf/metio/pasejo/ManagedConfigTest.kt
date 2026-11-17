// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedConfigTest {
    @Test
    fun valuesAreReadAndTrimmed() {
        val values = mapOf(
            "server_url" to " https://pasejo.example ",
            "organization" to "org",
            "genesis_hash" to "hash",
            "checkpoint_key" to "key",
            "max_unlock_seconds" to 60,
            "allow_copy" to false,
        )
        val config = ManagedConfig.parse(values::get)
        assertEquals("https://pasejo.example", config.serverUrl)
        assertEquals("org", config.organization)
        assertEquals("hash", config.genesisHash)
        assertEquals("key", config.checkpointKey)
        assertEquals(60, config.maxUnlockSeconds)
        assertFalse(config.allowCopy)
    }

    @Test
    fun absentOrMistypedValuesLeaveTheChoiceToThePerson() {
        val config = ManagedConfig.parse(mapOf("server_url" to "  ", "max_unlock_seconds" to "60", "allow_copy" to "no")::get)
        assertNull(config.serverUrl)
        assertNull(config.maxUnlockSeconds)
        assertTrue(config.allowCopy)
    }

    @Test
    fun thePolicyCapsTheUnlockPeriod() {
        assertEquals(300, ManagedConfig().unlockSeconds())
        assertEquals(60, ManagedConfig(maxUnlockSeconds = 60).unlockSeconds())
        assertEquals(300, ManagedConfig(maxUnlockSeconds = 900).unlockSeconds())
        assertEquals(ManagedConfig.MIN_UNLOCK_SECONDS, ManagedConfig(maxUnlockSeconds = 1).unlockSeconds())
        assertEquals(ManagedConfig.MAX_UNLOCK_SECONDS, ManagedConfig().unlockSeconds(100_000))
        assertEquals(ManagedConfig.MIN_UNLOCK_SECONDS, ManagedConfig().unlockSeconds(0))
    }
}
