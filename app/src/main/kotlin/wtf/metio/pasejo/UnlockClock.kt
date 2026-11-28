// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

/** When the opened store keys must be dropped. */
class UnlockClock(private val now: () -> Long = System::currentTimeMillis) {
    private var until = 0L

    fun unlock(seconds: Int) {
        until = now() + seconds * 1000L
    }

    fun lock() {
        until = 0L
    }

    /** Ends a running period after `seconds` at the latest; a shorter one stays. */
    fun shorten(seconds: Int) {
        until = minOf(until, now() + seconds * 1000L)
    }

    /** Whether the unlock period still runs. */
    fun open(): Boolean = now() < until

    fun remainingMillis(): Long = (until - now()).coerceAtLeast(0)
}
