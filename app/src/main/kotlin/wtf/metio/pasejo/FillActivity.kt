// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import wtf.metio.pasejo.core.DeviceInterface
import wtf.metio.pasejo.core.FillRequest
import wtf.metio.pasejo.core.KeyAgreement

/**
 * Fills one login form: reads the form again from the structure the
 * framework attached and lets [FillModel] find, unlock and choose; the
 * chosen credential goes back as a dataset.
 */
class FillActivity : ComponentActivity() {
    private val app get() = application as PasejoApp
    private var ui by mutableStateOf(FillUi())
    private var model: FillModel<AutofillId>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        secure()
        setResult(RESULT_CANCELED)
        setContent { PasejoTheme { Content() } }
        val structure = intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE, AssistStructure::class.java)
        val form = structure?.let { Forms.read(this, it) } ?: return finish()
        model = FillModel(Environment(form.caller), form.targets, form.caller) { ui = it }.also { it.start() }
    }

    @Composable
    private fun Content() {
        val model = model
        val ui = ui
        val found = ui.found
        if (model == null || found == null) {
            FillMessageScreen(ui.text, ::finish)
            return
        }
        FillPickerScreen(
            request = describe(found.request),
            offline = ui.offline,
            matches = found.matches,
            lookalikes = found.lookalikes,
            onChoose = model::choose,
            onCancel = ::finish,
        )
        val match = ui.confirming
        if (match != null) {
            ConfirmDialog(
                title = stringResource(R.string.fill_confirm_title),
                text = stringResource(R.string.fill_confirm_message, match.name, describe(found.request)),
                confirm = stringResource(R.string.action_fill),
                onConfirm = model::confirm,
                onDismiss = model::dismiss,
            )
        }
    }

    private fun describe(request: FillRequest): String {
        val app = request.app
        return if (app == null) {
            getString(R.string.fill_for_site, request.domains.joinToString(", "))
        } else {
            getString(R.string.fill_for_app, app.`package`)
        }
    }

    private inner class Environment(private val caller: Caller) : FillEnvironment<AutofillId> {
        override val requests: InFlight get() = app.requests
        override val device: DeviceInterface? get() = app.device
        override val locks: Int get() = app.locks
        override fun lock() = app.lock()
        override val browser = Browsers.trusted(caller.packageName, caller.certificates)
        override fun verified(domain: String) = app.links.verified(domain, caller.packageName, caller.certificates)
        override fun unlocked() = app.unlocked()
        override fun save() = app.save()
        override fun opened() = app.opened(app.unlockSeconds())
        override fun text(id: Int, vararg args: Any) = getString(id, *args)
        override fun describe(error: Throwable) = this@FillActivity.describe(error)
        override fun authenticate(done: (Result<() -> KeyAgreement>) -> Unit) = Unlocker.identity(this@FillActivity, done)
        override fun close() = finish()

        override fun <T> background(work: () -> T, done: (Result<T>) -> Unit) = app.background(work, done)

        override fun deliver(values: List<Pair<AutofillId, String>>) {
            val dataset = Dataset.Builder().apply {
                for ((id, value) in values) setField(id, Field.Builder().setValue(AutofillValue.forText(value)).build())
            }.build()
            setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset))
            finish()
        }
    }
}
