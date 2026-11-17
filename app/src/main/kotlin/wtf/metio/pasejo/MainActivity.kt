// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.LinearLayout
import android.widget.TextView
import wtf.metio.pasejo.core.StoreView

/**
 * The app's one activity: set-up, sign-in, enrollment, unlock, then the
 * stores, their entries and one entry at a time.
 */
class MainActivity : Activity() {
    private val app get() = application as PasejoApp
    private val main = Handler(Looper.getMainLooper())
    private var store: StoreView? = null
    private var entry: String? = null
    private var ticker: Runnable? = null

    // Device calls take the core's lock, which a running request holds, so
    // the main thread reads the device only while no request runs.
    private var working = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secure()
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
        stopTicker()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            entry != null -> entry = null
            store != null -> store = null
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
                return
            }
        }
        render()
    }

    private fun <T> work(task: () -> T, done: (Result<T>) -> Unit) {
        working = true
        app.background(task) { result ->
            working = false
            if (!isDestroyed) done(result)
        }
    }

    private fun render() {
        if (working) return
        stopTicker()
        val device = app.device
        when {
            app.restoreError != null -> restoreFailed()
            device == null -> setup()
            !device.signedIn() -> signIn()
            device.organization() == null -> join()
            !app.unlocked() -> locked()
            store == null -> stores()
            entry == null -> entries(store!!)
            else -> entry(store!!, entry!!)
        }
    }

    private fun busy(text: String) = Screen(this).apply {
        title(getString(R.string.app_name))
        text(text)
    }.show()

    private fun failed(error: Throwable, retry: () -> Unit = ::render) = Screen(this).apply {
        title(getString(R.string.error_title))
        text(describe(error))
        button(getString(R.string.action_retry)) { retry() }
    }.show()

    private fun restoreFailed() = Screen(this).apply {
        title(getString(R.string.error_title))
        text(getString(R.string.restore_failed, app.restoreError))
        button(getString(R.string.action_reset)) { confirmReset() }
    }.show()

    private val setupPreferences get() = getSharedPreferences("setup", MODE_PRIVATE)

    private fun setup() {
        val managed = app.managed
        val saved = setupPreferences
        val screen = Screen(this)
        screen.title(getString(R.string.setup_title))
        screen.text(getString(R.string.setup_explanation))
        val server = screen.field(getString(R.string.setup_server), managed.serverUrl ?: saved.getString("server", null), managed.serverUrl != null)
        val org = screen.field(getString(R.string.setup_org), managed.organization ?: saved.getString("org", null), managed.organization != null)
        val genesis = screen.field(getString(R.string.setup_genesis), managed.genesisHash ?: saved.getString("genesis", null), managed.genesisHash != null)
        val checkpoint = screen.field(getString(R.string.setup_checkpoint), managed.checkpointKey ?: saved.getString("checkpoint", null), managed.checkpointKey != null)
        screen.text(getString(if (app.supportedTier() == Tier.HARDWARE) R.string.tier_hardware else R.string.tier_sealed))
        screen.button(getString(R.string.action_continue)) {
            val values = listOf(server, org, genesis, checkpoint).map { it.text.toString().trim() }
            saved.edit()
                .putString("server", values[0])
                .putString("org", values[1])
                .putString("genesis", values[2])
                .putString("checkpoint", values[3])
                .apply()
            busy(getString(R.string.setup_creating))
            work({
                val device = app.create(values[0])
                device.connect(values[3].ifEmpty { null })
                app.save()
            }) { result ->
                result.onFailure { error -> work({ app.reset() }) { failed(error) } }
                result.onSuccess { render() }
            }
        }
        screen.show()
    }

    private fun signIn() = Screen(this).apply {
        val device = app.device ?: return@apply
        title(getString(R.string.sign_in_title))
        text(getString(R.string.sign_in_explanation, device.serverUrl()))
        button(getString(R.string.action_sign_in)) {
            val url = device.startSignIn(REDIRECT)
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        }
        button(getString(R.string.action_reset)) { confirmReset() }
    }.show()

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
        val screen = Screen(this)
        screen.title(getString(R.string.locked_title))
        val status = screen.text(getString(R.string.locked_checking))
        screen.text(getString(R.string.locked_fingerprint))
        screen.text(device.fingerprint(), monospace = true)
        val unlock = screen.button(getString(R.string.action_unlock)) {
            Unlocker.unlock(this) { result ->
                result.onFailure { failed(it) }
                result.onSuccess { render() }
            }
        }
        unlock.isEnabled = false
        screen.button(getString(R.string.action_reset)) { confirmReset() }
        screen.show()
        work({
            device.enrollment() to device.refresh()
        }) { result ->
            result.onFailure { status.text = describe(it) }
            result.onSuccess { (enrollment, org) ->
                app.save()
                status.text = when {
                    org.active -> getString(R.string.locked_active, org.name)
                    enrollment.pending -> getString(R.string.locked_pending, org.name)
                    else -> getString(R.string.locked_inactive, org.name)
                }
                unlock.isEnabled = org.active
            }
        }
    }

    private fun stores() {
        val device = app.device ?: return
        val screen = Screen(this)
        screen.title(getString(R.string.stores_title))
        if (device.offline()) screen.text(getString(R.string.offline_notice))
        val stores = device.stores()
        if (stores.isEmpty()) screen.text(getString(R.string.stores_empty))
        for (each in stores) {
            screen.button(each.label) {
                store = each
                render()
            }
        }
        val autofill = getSystemService(android.view.autofill.AutofillManager::class.java)
        if (autofill != null && autofill.isAutofillSupported && !autofill.hasEnabledAutofillServices()) {
            screen.text(getString(R.string.autofill_setup))
            screen.button(getString(R.string.action_autofill)) {
                startActivity(
                    Intent(android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
                        .setData(Uri.parse("package:$packageName")),
                )
            }
        }
        screen.button(getString(R.string.action_lock)) {
            app.lock()
            render()
        }
        screen.show()
    }

    private fun entries(store: StoreView) {
        val screen = Screen(this)
        screen.title(store.label)
        val filter = screen.field(getString(R.string.entries_filter), null)
        val list = screen.add(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL })
        val names = (store.secrets + store.otp).distinct().sorted()
        fun fill(query: String) {
            list.removeAllViews()
            for (name in names.filter { it.contains(query, ignoreCase = true) }) {
                list.addView(android.widget.Button(this).apply {
                    text = name
                    isAllCaps = false
                    setOnClickListener {
                        entry = name
                        render()
                    }
                })
            }
        }
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = fill(s?.toString().orEmpty())
        })
        fill("")
        screen.show()
    }

    private fun entry(store: StoreView, name: String) {
        val device = app.device ?: return
        val allowCopy = app.managed.allowCopy
        val screen = Screen(this)
        screen.title(name)
        if (name in store.secrets) {
            val value = screen.text(getString(R.string.entry_hidden), monospace = true)
            screen.button(getString(R.string.action_reveal)) {
                work({ device.reveal(store.id, name).also { app.save() } }) { result ->
                    result.onFailure { value.text = describe(it) }
                    result.onSuccess { value.text = it }
                }
            }
            if (allowCopy) {
                screen.button(getString(R.string.action_copy_password)) {
                    work({ device.reveal(store.id, name).also { app.save() }.lineSequence().first() }) { result ->
                        result.onFailure { value.text = describe(it) }
                        result.onSuccess {
                            Clipboard.copy(this, CLIP_LABEL, it)
                            value.text = getString(R.string.entry_copied, Clipboard.CLEAR_SECONDS)
                        }
                    }
                }
            }
        }
        if (name in store.otp) {
            val code = screen.text(getString(R.string.entry_hidden), monospace = true)
            screen.button(getString(R.string.action_show_code)) { showCode(code, store, name) }
            if (allowCopy) {
                screen.button(getString(R.string.action_copy_code)) {
                    work({ device.totp(store.id, name).also { app.save() } }) { result ->
                        result.onFailure { code.text = describe(it) }
                        result.onSuccess {
                            Clipboard.copy(this, CLIP_LABEL, it.code)
                            code.text = getString(R.string.entry_copied, Clipboard.CLEAR_SECONDS)
                        }
                    }
                }
            }
        }
        screen.show()
    }

    private fun showCode(view: TextView, store: StoreView, name: String) {
        val device = app.device ?: return
        work({ device.totp(store.id, name).also { app.save() } }) { result ->
            result.onFailure { view.text = describe(it) }
            result.onSuccess { code ->
                view.text = getString(R.string.entry_code, code.code, code.remainingSeconds.toLong())
                stopTicker()
                val again = Runnable { if (app.unlocked()) showCode(view, store, name) else render() }
                ticker = again
                main.postDelayed(again, code.remainingSeconds.toLong().coerceAtLeast(1) * 1000L)
            }
        }
    }

    private fun stopTicker() {
        ticker?.let(main::removeCallbacks)
        ticker = null
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_title)
            .setMessage(R.string.reset_message)
            .setPositiveButton(R.string.action_reset) { _, _ ->
                work({ app.reset() }) { render() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val REDIRECT_SCHEME = "wtf.metio.pasejo"
        const val REDIRECT = "$REDIRECT_SCHEME:/auth"
        private const val CLIP_LABEL = "pasejo"
    }
}
