// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import org.junit.Assert.assertEquals
import org.junit.Test

class ClearScheduleTest {
    private val posted = mutableListOf<Pair<Runnable, Long>>()
    private val schedule = ClearSchedule({ runnable, delay -> posted += runnable to delay }, { runnable -> posted.removeAll { it.first == runnable } })

    @Test
    fun aCopyIsClearedAfterItsDelay() {
        var clears = 0
        schedule.copied(45_000) { clears++ }
        assertEquals(listOf(45_000L), posted.map { it.second })
        posted.single().first.run()
        assertEquals(1, clears)
    }

    @Test
    fun aNewerCopyCancelsTheOlderClear() {
        val cleared = mutableListOf<String>()
        schedule.copied(45_000) { cleared += "first" }
        schedule.copied(45_000) { cleared += "second" }
        posted.toList().forEach { it.first.run() }
        assertEquals(listOf("second"), cleared)
    }

    @Test
    fun aCopyAfterAClearIsClearedAgain() {
        var clears = 0
        schedule.copied(45_000) { clears++ }
        posted.removeFirst().first.run()
        schedule.copied(45_000) { clears++ }
        posted.removeFirst().first.run()
        assertEquals(2, clears)
    }
}
