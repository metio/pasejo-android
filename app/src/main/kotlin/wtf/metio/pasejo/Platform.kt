// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import wtf.metio.pasejo.core.DeviceInterface
import wtf.metio.pasejo.core.KeyAgreement
import wtf.metio.pasejo.core.MobileException

/** What every screen needs from the app and the platform to read the device. */
interface Platform {
    /** The requests that run in the whole app; finishing one may call the screen's redraw. */
    val requests: InFlight
    val device: DeviceInterface?

    /** How often the stores were locked so far. */
    val locks: Int
    fun unlocked(): Boolean
    fun lock()
    fun save()
    fun text(id: Int, vararg args: Any): String
    fun describe(error: Throwable): String

    /** Asks the person to authenticate and hands back the age identity, to be opened off the main thread. */
    fun authenticate(done: (Result<() -> KeyAgreement>) -> Unit)

    /** Starts the unlock period after the stores opened. */
    fun opened()

    /** Runs `work` off the main thread as one of [requests] and hands its result to `done` on it. */
    fun <T> background(work: () -> T, done: (Result<T>) -> Unit)
}

/**
 * Authenticates, opens the stores of `device` as one request and starts the
 * unlock period. A lock while the request ran, such as the screen turning
 * off, wins: the stores close again and the unlock fails.
 */
fun Platform.unlockStores(device: DeviceInterface, done: (Result<Unit>) -> Unit) = authenticate { authenticated ->
    authenticated.fold(
        { identity ->
            val before = locks
            background({
                device.unlock(identity())
                save()
            }) { result ->
                if (result.isSuccess && locks != before) {
                    lock()
                    return@background done(Result.failure(MobileException.Locked()))
                }
                result.onSuccess { opened() }
                done(result)
            }
        },
        { error -> done(Result.failure(error)) },
    )
}
