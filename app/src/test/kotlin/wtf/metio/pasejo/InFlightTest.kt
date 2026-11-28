// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InFlightTest {
    private val requests = InFlight()
    private val events = mutableListOf<String>()

    @Test
    fun aRedrawAskedForDuringARequestFollowsItsResult() {
        requests.started()
        requests.redrawLater()
        requests.finished({ events += "done" }) { events += "redraw" }
        assertEquals(listOf("done", "redraw"), events)
        assertFalse(requests.busy)
    }

    @Test
    fun withoutAnAskNoRedrawFollows() {
        requests.started()
        requests.finished({ events += "done" }) { events += "redraw" }
        assertEquals(listOf("done"), events)
    }

    @Test
    fun theRedrawWaitsForTheLastRequest() {
        requests.started()
        requests.started()
        requests.redrawLater()
        requests.finished({ events += "first" }) { events += "redraw" }
        assertTrue(requests.busy)
        requests.finished({ events += "second" }) { events += "redraw" }
        assertEquals(listOf("first", "second", "redraw"), events)
    }

    @Test
    fun aRequestStartedByAResultDelaysTheRedraw() {
        requests.started()
        requests.redrawLater()
        requests.finished({ requests.started() }) { events += "redraw" }
        assertEquals(emptyList<String>(), events)
        requests.finished({}) { events += "redraw" }
        assertEquals(listOf("redraw"), events)
    }

    @Test
    fun aRedrawIsAskedForOnce() {
        requests.started()
        requests.redrawLater()
        requests.finished({}) { events += "redraw" }
        requests.started()
        requests.finished({}) { events += "redraw" }
        assertEquals(listOf("redraw"), events)
    }
}
