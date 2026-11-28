// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import wtf.metio.pasejo.core.DeviceInterface

/** When the opened store keys must be dropped. */
class UnlockClock(private val now: () -> Long = System::currentTimeMillis) {
    @Volatile
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

/**
 * Whether the stores are open: never once the period ended, and never
 * without a session, which closes them through `close`. The device is not
 * asked once the period ended, so the answer needs no core lock then.
 */
fun storesOpen(period: Boolean, device: DeviceInterface?, close: () -> Unit): Boolean {
    if (device == null || !period) return false
    if (!device.signedIn()) {
        close()
        return false
    }
    return device.unlocked()
}
