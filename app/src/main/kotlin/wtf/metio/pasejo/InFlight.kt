// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

/**
 * Counts the device requests that run, for every screen of the app, and
 * remembers a redraw asked for while one ran. Device calls take the core's
 * lock, which a running request holds, so a screen reads the device only
 * while nothing runs. Used on the main thread only.
 */
class InFlight {
    private var running = 0
    private var redraw = false

    val busy: Boolean get() = running > 0

    fun started() {
        running++
    }

    /** Asks for a redraw once the last running request finished. */
    fun redrawLater() {
        redraw = true
    }

    /** Ends a request: `done` sees its result, then `redraw` runs if one was asked for and nothing else runs. */
    fun finished(done: () -> Unit, redraw: () -> Unit) {
        running--
        done()
        if (running == 0 && this.redraw) {
            this.redraw = false
            redraw()
        }
    }
}
