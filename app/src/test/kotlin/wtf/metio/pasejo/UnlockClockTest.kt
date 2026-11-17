// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockClockTest {
    private var now = 1_000L
    private val clock = UnlockClock { now }

    @Test
    fun itStartsLocked() {
        assertFalse(clock.open())
        assertEquals(0, clock.remainingMillis())
    }

    @Test
    fun thePeriodEnds() {
        clock.unlock(10)
        assertTrue(clock.open())
        now += 9_999
        assertEquals(1, clock.remainingMillis())
        assertTrue(clock.open())
        now += 1
        assertFalse(clock.open())
    }

    @Test
    fun lockingEndsThePeriodAtOnce() {
        clock.unlock(300)
        clock.lock()
        assertFalse(clock.open())
    }
}
