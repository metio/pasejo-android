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
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import wtf.metio.pasejo.core.DeviceInterface
import wtf.metio.pasejo.core.KeyAgreement

/**
 * The app's one activity: set-up, sign-in, enrollment, unlock, then the
 * stores, their entries and one entry at a time. [MainModel] decides what it
 * shows; the activity supplies the platform.
 */
class MainActivity : ComponentActivity() {
    private val app get() = application as PasejoApp
    private var ui by mutableStateOf(MainUi())
    private var confirmingReset by mutableStateOf(false)
    private lateinit var model: MainModel

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        secure()
        model = MainModel(Environment()) { ui = it }
        setContent { PasejoTheme { Content() } }
        handleRedirect(intent)
        model.render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRedirect(intent)
    }

    override fun onResume() {
        super.onResume()
        app.onChange = model::render
        model.render()
    }

    override fun onPause() {
        super.onPause()
        app.onChange = null
        model.hide()
    }

    @Composable
    private fun Content() {
        val ui = ui
        BackHandler(enabled = ui.navigable) { model.back() }
        when (val current = ui.route) {
            is Route.Busy -> Busy(current.text)
            // A failure can come from values that never work, such as a
            // mistyped organization, so the way back to set-up stays open.
            is Route.Failed -> FailedScreen(current.message, stringResource(R.string.action_retry), model::render) {
                confirmingReset = true
            }
            is Route.RestoreFailed -> FailedScreen(
                stringResource(R.string.restore_failed, current.reason),
                stringResource(R.string.action_reset),
                onAction = { confirmingReset = true },
            )
            is Route.Setup -> SetupScreen(current.server, current.org, current.genesis, current.checkpoint, current.tier, model::create)
            is Route.SignIn -> SignInScreen(current.server, model::signIn) { confirmingReset = true }
            is Route.Locked -> LockedScreen(current.fingerprint, current.command, current.tier, ui.lockStatus, model::unlock) {
                confirmingReset = true
            }
            is Route.Stores -> StoresScreen(
                current.stores,
                current.offline,
                current.offerAutofill,
                current.unlockChoices,
                current.unlockSeconds,
                onChooseUnlock = model::chooseUnlock,
                onOpen = model::openStore,
                onAutofill = ::offerAutofill,
                onLock = model::lock,
            )
            is Route.Entries -> EntriesScreen(current.store, onOpen = model::openEntry, onBack = model::back)
            is Route.Entry -> {
                val live = ui.liveCode
                EntryScreen(
                    name = current.name,
                    secret = current.name in current.store.secrets,
                    otp = current.name in current.store.otp,
                    allowCopy = current.allowCopy,
                    value = ui.value,
                    code = if (live == null) {
                        ui.code
                    } else {
                        Shown.Value(countdown(live.code, live.remainingSeconds.toLong(), ui.codeGeneration, model::nextCode))
                    },
                    onReveal = { model.reveal(current.store, current.name) },
                    onCopyPassword = { model.copyPassword(current.store, current.name) },
                    onShowCode = { model.showCode(current.store, current.name) },
                    onCopyCode = { model.copyCode(current.store, current.name) },
                    onBack = model::back,
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
                    model.reset()
                },
                onDismiss = { confirmingReset = false },
            )
        }
    }

    private fun handleRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != REDIRECT_SCHEME) return
        setIntent(Intent(this, MainActivity::class.java))
        model.finishSignIn(data.toString())
    }

    private fun offerAutofill() = startActivity(
        Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(Uri.parse("package:$packageName")),
    )

    private inner class Environment : MainEnvironment {
        private val preferences get() = getSharedPreferences("setup", MODE_PRIVATE)

        override val requests: InFlight get() = app.requests
        override val device: DeviceInterface? get() = app.device
        override val locks: Int get() = app.locks
        override val restoreError: String? get() = app.restoreError
        override val managed: ManagedConfig get() = app.managed
        override val keyTier: Tier? get() = app.keys.tier
        override val supportedTier: Tier get() = app.supportedTier()
        override var chosenUnlockSeconds: Int
            get() = app.chosenUnlockSeconds
            set(seconds) {
                app.chosenUnlockSeconds = seconds
            }
        override var setup: SetupValues
            get() = preferences.run {
                SetupValues(
                    server = getString("server", null).orEmpty(),
                    org = getString("org", null).orEmpty(),
                    genesis = getString("genesis", null).orEmpty(),
                    checkpoint = getString("checkpoint", null).orEmpty(),
                )
            }
            set(values) {
                preferences.edit()
                    .putString("server", values.server)
                    .putString("org", values.org)
                    .putString("genesis", values.genesis)
                    .putString("checkpoint", values.checkpoint)
                    .apply()
            }

        override fun unlocked() = app.unlocked()
        override fun unlockSeconds() = app.unlockSeconds()
        override fun lock() = app.lock()
        override fun reset() = app.reset()
        override fun create(server: String): DeviceInterface = app.create(server)
        override fun save() = app.save()
        override fun opened() = app.opened(app.unlockSeconds())
        override fun copy(value: String) = Clipboard.copy(this@MainActivity, CLIP_LABEL, value)
        override fun text(id: Int, vararg args: Any) = getString(id, *args)
        override fun describe(error: Throwable) = this@MainActivity.describe(error)

        override fun autofillMissing(): Boolean {
            val autofill = getSystemService(AutofillManager::class.java)
            return autofill != null && autofill.isAutofillSupported && !autofill.hasEnabledAutofillServices()
        }

        override fun authenticate(done: (Result<() -> KeyAgreement>) -> Unit) = Unlocker.identity(this@MainActivity, done)

        // Results reach the model even after the activity is gone, so an
        // unlock that finishes after a rotation still starts its period.
        override fun <T> background(work: () -> T, done: (Result<T>) -> Unit) = app.background(work, done)

        // A Custom Tab, or the browser where none supports it; the server
        // redirects to REDIRECT, which the browser hands back to this activity.
        override fun browse(url: String) = CustomTabsIntent.Builder().build().launchUrl(this@MainActivity, Uri.parse(url))
    }

    companion object {
        const val REDIRECT_SCHEME = "wtf.metio.pasejo"
        const val REDIRECT = "$REDIRECT_SCHEME:/auth"
        private const val CLIP_LABEL = "pasejo"
    }
}
