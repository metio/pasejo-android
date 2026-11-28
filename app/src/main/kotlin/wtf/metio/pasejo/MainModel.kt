// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import wtf.metio.pasejo.core.DeviceInterface
import wtf.metio.pasejo.core.MobileException
import wtf.metio.pasejo.core.StoreView
import wtf.metio.pasejo.core.TotpCode

/** What the activity shows; each screen gets everything it needs from here. */
sealed interface Route {
    data class Busy(val text: String) : Route
    data class Failed(val message: String) : Route
    data class RestoreFailed(val reason: String) : Route
    data class Setup(val server: SetupField, val org: SetupField, val genesis: SetupField, val checkpoint: SetupField, val tier: Tier) : Route
    data class SignIn(val server: String) : Route
    data class Locked(val fingerprint: String, val command: String, val tier: Tier?) : Route
    data class Stores(
        val stores: List<StoreView>,
        val offline: Boolean,
        val offerAutofill: Boolean,
        val unlockChoices: List<Int>,
        val unlockSeconds: Int,
    ) : Route
    data class Entries(val store: StoreView) : Route
    data class Entry(val store: StoreView, val name: String, val allowCopy: Boolean) : Route
}

/** Everything the main screen shows at one moment. */
data class MainUi(
    val route: Route = Route.Busy(""),
    val lockStatus: LockStatus? = null,
    val value: Shown = Shown.Hidden,
    val code: Shown = Shown.Hidden,
    val liveCode: TotpCode? = null,
    /** Changes with every code fetched, so a countdown restarts even for an equal code. */
    val codeGeneration: Int = 0,
) {
    val navigable: Boolean get() = route is Route.Entries || route is Route.Entry
}

/** What the main screen needs from the app and the platform. */
interface MainEnvironment : Platform {
    val restoreError: String?

    /** Tries the saved device again, setting or clearing [restoreError]. */
    fun restore()
    val managed: ManagedConfig
    val keyTier: Tier?
    val supportedTier: Tier
    var chosenUnlockSeconds: Int
    var setup: SetupValues
    fun unlockSeconds(): Int
    fun reset()
    fun create(server: String): DeviceInterface
    fun autofillMissing(): Boolean
    fun copy(value: String)

    /** Opens `url` in a browser tab; fails when the phone has no browser. */
    fun browse(url: String)
}

/**
 * The main screen's state and actions. Device calls take the core's lock,
 * which a running request holds, so the device is read on the main thread
 * only while no request of the app runs; a redraw asked for meanwhile runs
 * once the last one finished.
 */
class MainModel(private val env: MainEnvironment, private val changed: (MainUi) -> Unit = {}) {
    var ui = MainUi()
        private set(value) {
            field = value
            changed(value)
        }

    private var store: StoreView? = null
    private var entry: String? = null

    /** Whether a request runs, during which the device must not be read here. */
    val working: Boolean get() = env.requests.busy

    /** Draws the screen the app's state calls for, hiding every shown value. */
    fun render() {
        if (working) {
            env.requests.redrawLater()
            return
        }
        val device = env.device
        val error = env.restoreError
        val route = when {
            error != null -> Route.RestoreFailed(error)
            device == null -> setupRoute()
            !device.signedIn() -> Route.SignIn(device.serverUrl())
            device.organization() == null -> return join(device)
            !env.unlocked() -> return locked(device)
            else -> when (val open = store) {
                null -> Route.Stores(device.stores(), device.offline(), env.autofillMissing(), env.managed.unlockChoices(), env.unlockSeconds())
                else -> when (val name = entry) {
                    null -> Route.Entries(open)
                    else -> Route.Entry(open, name, env.managed.allowCopy)
                }
            }
        }
        ui = MainUi(route = route)
    }

    /** Hides shown values and codes, as when the screen goes out of sight. */
    fun hide() {
        ui = ui.copy(value = Shown.Hidden, code = Shown.Hidden, liveCode = null)
    }

    fun back() {
        if (entry != null) entry = null else store = null
        render()
    }

    fun openStore(open: StoreView) {
        store = open
        render()
    }

    fun openEntry(name: String) {
        entry = name
        render()
    }

    fun chooseUnlock(seconds: Int) {
        env.chosenUnlockSeconds = seconds
        render()
    }

    fun lock() {
        env.lock()
        render()
    }

    fun reset() = work({ env.reset() }) { render() }

    fun retryRestore() = work({ env.restore() }) { render() }

    fun create(values: SetupValues) {
        env.setup = values
        busy(R.string.setup_creating)
        // The device is saved only once its server checked out, and a failed
        // set-up is undone inside the request, so neither depends on this
        // screen still being there when it finishes.
        work({
            try {
                env.create(values.server).connect(values.checkpoint.ifEmpty { null })
                env.save()
            } catch (error: Exception) {
                env.reset()
                throw error
            }
        }) { result ->
            result.onFailure(::failed)
            result.onSuccess { render() }
        }
    }

    fun signIn() {
        if (working) return
        val device = env.device ?: return
        runCatching { env.browse(device.startSignIn(MainActivity.REDIRECT)) }.onFailure(::failed)
    }

    fun finishSignIn(redirect: String) {
        val device = env.device ?: return
        busy(R.string.sign_in_finishing)
        work({
            device.finishSignIn(redirect)
            env.save()
        }) { result ->
            result.onFailure(::failed)
            result.onSuccess { render() }
        }
    }

    fun unlock() {
        if (working) return
        val device = env.device ?: return
        env.unlockStores(device) { result ->
            result.onFailure(::failed)
            result.onSuccess { render() }
        }
    }

    fun reveal(store: StoreView, name: String) = read({ it.reveal(store.id, name) }) { result ->
        ui = ui.copy(value = result.fold({ Shown.Value(it) }, { Shown.Message(env.describe(it)) }))
    }

    fun copyPassword(store: StoreView, name: String) = read({ it.reveal(store.id, name).lineSequence().first() }) { result ->
        result.onSuccess(env::copy)
        ui = ui.copy(value = copied(result))
    }

    fun showCode(store: StoreView, name: String) = read({ it.totp(store.id, name) }) { result ->
        ui = result.fold(
            { ui.copy(liveCode = it, code = Shown.Hidden, codeGeneration = ui.codeGeneration + 1) },
            { ui.copy(liveCode = null, code = Shown.Message(env.describe(it))) },
        )
    }

    /** Fetches the next code once the shown one expired, while the entry stays open. */
    fun nextCode() {
        val current = ui.route as? Route.Entry ?: return
        if (working) {
            // The expired code must not stay; the person asks for a new one.
            ui = ui.copy(liveCode = null, code = Shown.Hidden)
            return
        }
        showCode(current.store, current.name)
    }

    fun copyCode(store: StoreView, name: String) = read({ it.totp(store.id, name).code }) { result ->
        result.onSuccess(env::copy)
        ui = ui.copy(liveCode = null, code = copied(result))
    }

    private fun copied(result: Result<String>): Shown = result.fold(
        { Shown.Message(env.text(R.string.entry_copied, Clipboard.CLEAR_SECONDS)) },
        { Shown.Message(env.describe(it)) },
    )

    /**
     * Reads from the open stores and hands the result to `shown`, unless the
     * entry was left meanwhile; renders the locked screen instead when the
     * stores closed.
     */
    private fun <T> read(task: (DeviceInterface) -> T, shown: (Result<T>) -> Unit) {
        if (working) return
        val device = env.device
        if (device == null || !env.unlocked()) return render()
        val route = ui.route
        work({ task(device).also { env.save() } }) { result ->
            if (ui.route == route) shown(result)
        }
    }

    private fun setupRoute(): Route.Setup {
        val managed = env.managed
        val saved = env.setup
        fun field(policy: String?, value: String) = SetupField(policy ?: value, policy != null)
        return Route.Setup(
            server = field(managed.serverUrl, saved.server),
            org = field(managed.organization, saved.org),
            genesis = field(managed.genesisHash, saved.genesis),
            checkpoint = field(managed.checkpointKey, saved.checkpoint),
            tier = env.supportedTier,
        )
    }

    private fun join(device: DeviceInterface) {
        val org = env.managed.organization ?: env.setup.org
        val genesis = env.managed.genesisHash ?: env.setup.genesis
        busy(R.string.join_running)
        work({
            device.join(org, genesis).also { env.save() }
        }) { result ->
            result.onFailure(::failed)
            result.onSuccess { render() }
        }
    }

    private fun locked(device: DeviceInterface) {
        val fingerprint = device.fingerprint()
        val route = Route.Locked(fingerprint, approveCommand(device.deviceId(), fingerprint, device.organization()), env.keyTier)
        ui = MainUi(route = route)
        work({ (device.enrollment() to device.refresh()).also { env.save() } }) { result ->
            if (ui.route != route) return@work
            val status = result.fold(
                { (enrollment, org) ->
                    when {
                        org.active -> LockStatus(env.text(R.string.locked_active, org.name), canUnlock = true)
                        enrollment.pending -> LockStatus(env.text(R.string.locked_pending, org.name), canUnlock = false)
                        else -> LockStatus(env.text(R.string.locked_inactive, org.name), canUnlock = false, Tone.WARNING)
                    }
                },
                // Without the server, unlocking opens the offline copy where
                // the organization allows one; the core decides.
                { error ->
                    val offline = error is MobileException.Network
                    LockStatus(env.describe(error), canUnlock = offline, if (offline) Tone.WARNING else Tone.ERROR)
                },
            )
            ui = ui.copy(lockStatus = status)
        }
    }

    private fun busy(text: Int) {
        ui = MainUi(route = Route.Busy(env.text(text)))
    }

    private fun failed(error: Throwable) {
        ui = MainUi(route = Route.Failed(env.describe(error)))
    }

    private fun <T> work(task: () -> T, done: (Result<T>) -> Unit) = env.background(task, done)
}
