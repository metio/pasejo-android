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
    fun aLowerCapShortensARunningPeriod() {
        clock.unlock(300)
        clock.shorten(60)
        assertEquals(60_000, clock.remainingMillis())
    }

    @Test
    fun aHigherCapKeepsAShorterPeriod() {
        clock.unlock(30)
        clock.shorten(60)
        assertEquals(30_000, clock.remainingMillis())
    }

    @Test
    fun aCapOpensNoClosedPeriod() {
        clock.shorten(60)
        assertFalse(clock.open())
    }

    @Test
    fun lockingEndsThePeriodAtOnce() {
        clock.unlock(300)
        clock.lock()
        assertFalse(clock.open())
    }

    @Test
    fun anEndedPeriodAnswersWithoutTheDevice() {
        val device = FakeDevice().apply { held = true }
        assertEquals(false, storesOpen(period = false, device = device) { error("not closed") })
        assertEquals(emptyList<String>(), device.outsideCalls)
    }

    @Test
    fun aLostSessionClosesTheStores() {
        var closed = false
        assertEquals(false, storesOpen(true, FakeDevice().apply { signedIn = false }) { closed = true })
        assertEquals(true, closed)
    }

    @Test
    fun aRunningPeriodAsksTheDevice() {
        assertEquals(true, storesOpen(true, FakeDevice()) { error("not closed") })
        assertEquals(false, storesOpen(true, FakeDevice().apply { unlocked = false }) { error("not closed") })
    }
}
