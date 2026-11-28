// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import wtf.metio.pasejo.core.StoreView
import wtf.metio.pasejo.core.TotpCode

/** What the activity shows; each screen gets everything it needs from here. */
sealed interface Route {
    data class Busy(val text: String) : Route
    data class Failed(val message: String, val retry: () -> Unit) : Route
    data class RestoreFailed(val reason: String) : Route
    data class Setup(val server: SetupField, val org: SetupField, val genesis: SetupField, val checkpoint: SetupField, val tier: Tier) : Route
    data class SignIn(val server: String) : Route
    data class Locked(val fingerprint: String) : Route
    data class Stores(val stores: List<StoreView>, val offline: Boolean, val offerAutofill: Boolean) : Route
    data class Entries(val store: StoreView) : Route
    data class Entry(val store: StoreView, val name: String, val allowCopy: Boolean) : Route
}

/**
 * The app's one activity: set-up, sign-in, enrollment, unlock, then the
 * stores, their entries and one entry at a time.
 */
class MainActivity : ComponentActivity() {
    private val app get() = application as PasejoApp
    private var store: StoreView? = null
    private var entry: String? = null

    private var route by mutableStateOf<Route>(Route.Busy(""))
    private var lockStatus by mutableStateOf<LockStatus?>(null)
    private var value by mutableStateOf<Shown>(Shown.Hidden)
    private var code by mutableStateOf<Shown>(Shown.Hidden)
    private var liveCode by mutableStateOf<TotpCode?>(null)
    private var codeGeneration by mutableIntStateOf(0)
    private var confirmingReset by mutableStateOf(false)

    // Device calls take the core's lock, which a running request holds, so
    // the main thread reads the device only while no request runs. The
    // screens read only the routes built here, never the device.
    private var working = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        secure()
        setContent { PasejoTheme { Content() } }
        handleRedirect(intent)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRedirect(intent)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onPause() {
        super.onPause()
        hideValues()
    }

    @Composable
    private fun Content() {
        BackHandler(enabled = store != null && route.let { it is Route.Entries || it is Route.Entry }) { back() }
        when (val current = route) {
            is Route.Busy -> Busy(current.text)
            is Route.Failed -> FailedScreen(current.message, stringResource(R.string.action_retry), current.retry)
            is Route.RestoreFailed -> FailedScreen(
                getString(R.string.restore_failed, current.reason),
                stringResource(R.string.action_reset),
            ) { confirmingReset = true }
            is Route.Setup -> SetupScreen(current.server, current.org, current.genesis, current.checkpoint, current.tier, ::create)
            is Route.SignIn -> SignInScreen(current.server, ::signIn) { confirmingReset = true }
            is Route.Locked -> LockedScreen(current.fingerprint, lockStatus, ::unlock) { confirmingReset = true }
            is Route.Stores -> StoresScreen(
                current.stores,
                current.offline,
                current.offerAutofill,
                onOpen = { store = it; render() },
                onAutofill = ::offerAutofill,
                onLock = { app.lock(); render() },
            )
            is Route.Entries -> EntriesScreen(current.store, onOpen = { entry = it; render() }, onBack = ::back)
            is Route.Entry -> {
                val live = liveCode
                EntryScreen(
                    name = current.name,
                    secret = current.name in current.store.secrets,
                    otp = current.name in current.store.otp,
                    allowCopy = current.allowCopy,
                    value = value,
                    code = if (live == null) code else Shown.Value(countdown(live.code, live.remainingSeconds.toLong(), codeGeneration, ::nextCode)),
                    onReveal = { reveal(current.store, current.name) },
                    onCopyPassword = { copyPassword(current.store, current.name) },
                    onShowCode = { showCode(current.store, current.name) },
                    onCopyCode = { copyCode(current.store, current.name) },
                    onBack = ::back,
                )
            }
        }
        if (confirmingReset) {
            ConfirmDialog(
                title = stringResource(R.string.reset_title),
                text = stringResource(R.string.reset_message),
                confirm = stringResource(R.string.action_reset),
                onConfirm = {
                    confirmingReset = false
                    work({ app.reset() }) { render() }
                },
                onDismiss = { confirmingReset = false },
            )
        }
    }

    private fun back() {
        if (entry != null) entry = null else store = null
        render()
    }

    private fun <T> work(task: () -> T, done: (Result<T>) -> Unit) {
        working = true
        app.background(task) { result ->
            working = false
            if (!isDestroyed) done(result)
        }
    }

    private fun busy(text: String) {
        route = Route.Busy(text)
    }

    private fun failed(error: Throwable) {
        route = Route.Failed(describe(error), ::render)
    }

    private fun hideValues() {
        value = Shown.Hidden
        code = Shown.Hidden
        liveCode = null
    }

    private fun render() {
        if (working) return
        hideValues()
        val device = app.device
        route = when {
            app.restoreError != null -> Route.RestoreFailed(app.restoreError.toString())
            device == null -> setupRoute()
            !device.signedIn() -> Route.SignIn(device.serverUrl())
            device.organization() == null -> return join()
            !app.unlocked() -> return locked()
            store == null -> Route.Stores(device.stores(), device.offline(), autofillMissing())
            entry == null -> Route.Entries(store!!)
            else -> Route.Entry(store!!, entry!!, app.managed.allowCopy)
        }
    }

    private val setupPreferences get() = getSharedPreferences("setup", MODE_PRIVATE)

    private fun setupRoute(): Route.Setup {
        val managed = app.managed
        val saved = setupPreferences
        fun field(policy: String?, key: String) = SetupField(policy ?: saved.getString(key, null).orEmpty(), policy != null)
        return Route.Setup(
            server = field(managed.serverUrl, "server"),
            org = field(managed.organization, "org"),
            genesis = field(managed.genesisHash, "genesis"),
            checkpoint = field(managed.checkpointKey, "checkpoint"),
            tier = app.supportedTier(),
        )
    }

    private fun create(values: SetupValues) {
        setupPreferences.edit()
            .putString("server", values.server)
            .putString("org", values.org)
            .putString("genesis", values.genesis)
            .putString("checkpoint", values.checkpoint)
            .apply()
        busy(getString(R.string.setup_creating))
        work({
            val device = app.create(values.server)
            device.connect(values.checkpoint.ifEmpty { null })
            app.save()
        }) { result ->
            result.onFailure { error -> work({ app.reset() }) { failed(error) } }
            result.onSuccess { render() }
        }
    }

    private fun signIn() {
        val device = app.device ?: return
        val url = device.startSignIn(REDIRECT)
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    }

    private fun handleRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != REDIRECT_SCHEME) return
        val device = app.device ?: return
        setIntent(Intent(this, MainActivity::class.java))
        busy(getString(R.string.sign_in_finishing))
        work({
            device.finishSignIn(data.toString())
            app.save()
        }) { result ->
            result.onFailure { failed(it) }
            result.onSuccess { render() }
        }
    }

    private fun join() {
        val managed = app.managed
        val org = managed.organization ?: setupPreferences.getString("org", null).orEmpty()
        val genesis = managed.genesisHash ?: setupPreferences.getString("genesis", null).orEmpty()
        busy(getString(R.string.join_running))
        work({
            val enrollment = app.device!!.join(org, genesis)
            app.save()
            enrollment
        }) { result ->
            result.onFailure { failed(it) }
            result.onSuccess { render() }
        }
    }

    private fun locked() {
        val device = app.device ?: return
        route = Route.Locked(device.fingerprint())
        lockStatus = null
        work({ device.enrollment() to device.refresh() }) { result ->
            result.onFailure { lockStatus = LockStatus(describe(it), canUnlock = false, Tone.ERROR) }
            result.onSuccess { (enrollment, org) ->
                app.save()
                lockStatus = when {
                    org.active -> LockStatus(getString(R.string.locked_active, org.name), canUnlock = true)
                    enrollment.pending -> LockStatus(getString(R.string.locked_pending, org.name), canUnlock = false)
                    else -> LockStatus(getString(R.string.locked_inactive, org.name), canUnlock = false, Tone.WARNING)
                }
            }
        }
    }

    private fun unlock() = Unlocker.unlock(this) { result ->
        result.onFailure { failed(it) }
        result.onSuccess { render() }
    }

    private fun autofillMissing(): Boolean {
        val autofill = getSystemService(AutofillManager::class.java)
        return autofill != null && autofill.isAutofillSupported && !autofill.hasEnabledAutofillServices()
    }

    private fun offerAutofill() = startActivity(
        Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(Uri.parse("package:$packageName")),
    )

    private fun reveal(store: StoreView, name: String) {
        val device = app.device ?: return
        work({ device.reveal(store.id, name).also { app.save() } }) { result ->
            result.onFailure { value = Shown.Message(describe(it)) }
            result.onSuccess { value = Shown.Value(it) }
        }
    }

    private fun copyPassword(store: StoreView, name: String) {
        val device = app.device ?: return
        work({ device.reveal(store.id, name).also { app.save() }.lineSequence().first() }) { result ->
            result.onFailure { value = Shown.Message(describe(it)) }
            result.onSuccess {
                Clipboard.copy(this, CLIP_LABEL, it)
                value = Shown.Message(getString(R.string.entry_copied, Clipboard.CLEAR_SECONDS))
            }
        }
    }

    private fun showCode(store: StoreView, name: String) {
        val device = app.device ?: return
        work({ device.totp(store.id, name).also { app.save() } }) { result ->
            result.onFailure {
                liveCode = null
                code = Shown.Message(describe(it))
            }
            result.onSuccess {
                codeGeneration++
                liveCode = it
            }
        }
    }

    /** Fetches the next code once the shown one expires, while the stores stay open. */
    private fun nextCode() {
        val current = route as? Route.Entry ?: return
        if (app.unlocked()) showCode(current.store, current.name) else render()
    }

    private fun copyCode(store: StoreView, name: String) {
        val device = app.device ?: return
        work({ device.totp(store.id, name).also { app.save() } }) { result ->
            liveCode = null
            result.onFailure { code = Shown.Message(describe(it)) }
            result.onSuccess {
                Clipboard.copy(this, CLIP_LABEL, it.code)
                code = Shown.Message(getString(R.string.entry_copied, Clipboard.CLEAR_SECONDS))
            }
        }
    }

    companion object {
        const val REDIRECT_SCHEME = "wtf.metio.pasejo"
        const val REDIRECT = "$REDIRECT_SCHEME:/auth"
        private const val CLIP_LABEL = "pasejo"
    }
}
