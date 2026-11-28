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
import wtf.metio.pasejo.core.DomainVerifier
import wtf.metio.pasejo.core.FillCredential
import wtf.metio.pasejo.core.FillMatch
import wtf.metio.pasejo.core.FillRequest
import wtf.metio.pasejo.core.QueryResult

/**
 * Fills one login form: reads the form again from the structure the
 * framework attached, builds the request (trusting a web domain only from a
 * listed browser), unlocks when needed, lists the matches and returns the
 * chosen credential as a dataset. Secrets that ask for it are confirmed
 * first.
 */
class FillActivity : ComponentActivity() {
    private val app get() = application as PasejoApp

    /** A message, or the matches of one query with the match waiting for confirmation. */
    private var text by mutableStateOf<String?>(null)
    private var found by mutableStateOf<QueryResult?>(null)
    private var confirming by mutableStateOf<FillMatch?>(null)
    private var offline by mutableStateOf(false)
    private var form: Form? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        secure()
        setResult(RESULT_CANCELED)
        setContent { PasejoTheme { Content() } }
        val structure = intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE, AssistStructure::class.java)
        val form = structure?.let { Forms.read(this, it) }
        if (form == null || app.device == null) return finish()
        this.form = form
        message(getString(R.string.fill_checking))
        val browser = Browsers.trusted(form.caller.packageName, form.caller.certificates)
        app.background({
            Requests.build(form.caller, browser) { domain ->
                app.links.verified(domain, form.caller.packageName, form.caller.certificates)
            }
        }) { result ->
            val request = result.getOrNull()
            when {
                request == null -> message(getString(R.string.fill_nothing))
                app.unlocked() -> query(form, request)
                else -> Unlocker.unlock(this) { opened ->
                    opened.onFailure { message(describe(it)) }
                    opened.onSuccess { query(form, request) }
                }
            }
        }
    }

    @Composable
    private fun Content() {
        val result = found
        val form = form
        when {
            result != null && form != null -> FillPickerScreen(
                request = describeRequest(result.request),
                offline = offline,
                matches = result.matches,
                lookalikes = result.lookalikes,
                onChoose = { choose(form, result.request, it) },
                onCancel = ::finish,
            )
            else -> FillMessageScreen(text.orEmpty(), ::finish)
        }
        val match = confirming
        if (match != null && result != null && form != null) {
            ConfirmDialog(
                title = stringResource(R.string.fill_confirm_title),
                text = stringResource(R.string.fill_confirm_message, match.name, describeRequest(result.request)),
                confirm = stringResource(R.string.action_fill),
                onConfirm = {
                    confirming = null
                    fill(form, result.request, match)
                },
                onDismiss = { confirming = null },
            )
        }
    }

    private fun message(text: String) {
        found = null
        this.text = text
    }

    private fun query(form: Form, request: FillRequest) {
        val device = app.device ?: return finish()
        val caller = form.caller
        val verifier = object : DomainVerifier {
            override fun verified(domain: String) = app.links.verified(domain, caller.packageName, caller.certificates)
        }
        app.background({ device.query(request, verifier) }) { result ->
            result.onFailure { message(describe(it)) }
            result.onSuccess {
                offline = device.offline()
                found = it
            }
        }
    }

    private fun describeRequest(request: FillRequest): String {
        val app = request.app
        return if (app == null) {
            getString(R.string.fill_for_site, request.domains.joinToString(", "))
        } else {
            getString(R.string.fill_for_app, app.`package`)
        }
    }

    private fun choose(form: Form, request: FillRequest, match: FillMatch) {
        if (match.confirm) confirming = match else fill(form, request, match)
    }

    private fun fill(form: Form, request: FillRequest, match: FillMatch) {
        val device = app.device ?: return finish()
        app.background({ device.fill(request, match.store, match.name).also { app.save() } }) { result ->
            result.onFailure { message(describe(it)) }
            result.onSuccess { credential ->
                val dataset = dataset(form.targets, credential) ?: return@onSuccess message(getString(R.string.fill_empty))
                setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset))
                finish()
            }
        }
    }

    /** The credential's values for the form's fields, or `null` when none fits. */
    private fun dataset(targets: Targets<AutofillId>, credential: FillCredential): Dataset? {
        val builder = Dataset.Builder()
        var filled = 0
        fun put(id: AutofillId?, value: String?) {
            if (id != null && !value.isNullOrEmpty()) {
                builder.setField(id, Field.Builder().setValue(AutofillValue.forText(value)).build())
                filled++
            }
        }
        put(targets.username, credential.username)
        put(targets.password, credential.password)
        put(targets.otp, credential.totp)
        return builder.build().takeIf { filled > 0 }
    }
}
