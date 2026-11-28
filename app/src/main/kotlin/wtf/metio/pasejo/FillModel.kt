// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import wtf.metio.pasejo.core.DeviceInterface
import wtf.metio.pasejo.core.DomainVerifier
import wtf.metio.pasejo.core.FillCredential
import wtf.metio.pasejo.core.FillMatch
import wtf.metio.pasejo.core.FillRequest
import wtf.metio.pasejo.core.MobileException
import wtf.metio.pasejo.core.QueryResult

/** What the fill screen shows: a message, or the matches of one query with the match waiting for confirmation. */
data class FillUi(
    val text: String = "",
    val found: QueryResult? = null,
    val offline: Boolean = false,
    val confirming: FillMatch? = null,
)

/** What the fill screen needs beyond [Platform]. */
interface FillEnvironment<Id> : Platform {
    /** Whether the caller is a listed browser, whose reported web domain counts. */
    val browser: Boolean

    /** Whether `domain` lets the caller receive its credentials. */
    fun verified(domain: String): Boolean

    /** Hands the values to the form and closes the screen. */
    fun deliver(values: List<Pair<Id, String>>)

    fun close()
}

/** The credential's values for the form's fields, in the order username, password, code. */
fun <Id> fillValues(targets: Targets<Id>, credential: FillCredential): List<Pair<Id, String>> =
    listOf(targets.username to credential.username, targets.password to credential.password, targets.otp to credential.totp)
        .mapNotNull { (id, value) -> if (id != null && !value.isNullOrEmpty()) id to value else null }

/**
 * Fills one login form: builds the request (trusting a web domain only from
 * a listed browser), unlocks when needed, lists the matches and delivers the
 * chosen credential; secrets that ask for it are confirmed first.
 */
class FillModel<Id>(
    private val env: FillEnvironment<Id>,
    private val targets: Targets<Id>,
    private val caller: Caller,
    private val changed: (FillUi) -> Unit = {},
) {
    var ui = FillUi()
        private set(value) {
            field = value
            changed(value)
        }

    private val verifier = object : DomainVerifier {
        override fun verified(domain: String) = env.verified(domain)
    }

    fun start() {
        val device = env.device ?: return env.close()
        message(R.string.fill_checking)
        // Whether the stores are open is asked inside the request, where no
        // other request can hold the core's lock.
        env.background({ Requests.build(caller, env.browser, env::verified) to env.unlocked() }) { result ->
            val (request, open) = result.getOrNull() ?: return@background message(R.string.fill_nothing)
            if (request == null) return@background message(R.string.fill_nothing)
            if (open) {
                query(device, request)
            } else {
                env.unlockStores(device) { opened ->
                    opened.onFailure { ui = FillUi(text = env.describe(it)) }
                    opened.onSuccess { query(device, request) }
                }
            }
        }
    }

    /** Fills `match` at once, or asks first when its secret wants a confirmation. */
    fun choose(match: FillMatch) {
        if (match.confirm) ui = ui.copy(confirming = match) else fill(match)
    }

    fun confirm() {
        val match = ui.confirming ?: return
        ui = ui.copy(confirming = null)
        fill(match)
    }

    fun dismiss() {
        ui = ui.copy(confirming = null)
    }

    private fun query(device: DeviceInterface, request: FillRequest) {
        env.background({ device.query(request, verifier) to device.offline() }) { result ->
            result.onFailure { ui = FillUi(text = env.describe(it)) }
            result.onSuccess { (found, offline) -> ui = FillUi(found = found, offline = offline) }
        }
    }

    private fun fill(match: FillMatch) {
        val device = env.device ?: return env.close()
        val request = ui.found?.request ?: return
        if (env.requests.busy) return
        env.background({
            if (!env.unlocked()) throw MobileException.Locked()
            device.fill(request, match.store, match.name).also { env.save() }
        }) { result ->
            result.onFailure { ui = FillUi(text = env.describe(it)) }
            result.onSuccess { credential ->
                val values = fillValues(targets, credential)
                if (values.isEmpty()) message(R.string.fill_empty) else env.deliver(values)
            }
        }
    }

    private fun message(text: Int) {
        ui = FillUi(text = env.text(text))
    }
}
