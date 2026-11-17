// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import android.app.Activity
import android.app.AlertDialog
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import wtf.metio.pasejo.core.FillCredential
import wtf.metio.pasejo.core.FillMatch
import wtf.metio.pasejo.core.FillRequest

/**
 * Fills one login form: reads the form again from the structure the
 * framework attached, builds the request (trusting a web domain only from a
 * listed browser), unlocks when needed, lists the matches and returns the
 * chosen credential as a dataset. Secrets that ask for it are confirmed
 * first.
 */
class FillActivity : Activity() {
    private val app get() = application as PasejoApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secure()
        setResult(RESULT_CANCELED)
        val structure = intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE, AssistStructure::class.java)
        val form = structure?.let { Forms.read(this, it) }
        if (form == null || app.device == null) return finish()
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

    private fun message(text: String) = Screen(this).apply {
        title(getString(R.string.app_name))
        text(text)
        button(getString(android.R.string.cancel)) { finish() }
    }.show()

    private fun query(form: Form, request: FillRequest) {
        val device = app.device ?: return finish()
        app.background({ device.query(request) }) { result ->
            result.onFailure { message(describe(it)) }
            result.onSuccess { found ->
                val screen = Screen(this)
                screen.title(getString(R.string.fill_title))
                screen.text(describeRequest(request))
                if (found.matches.isEmpty()) {
                    screen.text(getString(R.string.fill_no_matches))
                    if (found.lookalikes.isNotEmpty()) {
                        screen.text(getString(R.string.fill_lookalikes, found.lookalikes.joinToString(", ")))
                    }
                }
                for (match in found.matches) {
                    val label = buildString {
                        append(match.name)
                        if (match.username.isNotEmpty()) append(" — ").append(match.username)
                        append(" (").append(match.storeLabel).append(')')
                    }
                    screen.button(label) { choose(form, request, match) }
                }
                screen.button(getString(android.R.string.cancel)) { finish() }
                screen.show()
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
        if (!match.confirm) return fill(form, request, match)
        AlertDialog.Builder(this)
            .setTitle(R.string.fill_confirm_title)
            .setMessage(getString(R.string.fill_confirm_message, match.name, describeRequest(request)))
            .setPositiveButton(R.string.action_fill) { _, _ -> fill(form, request, match) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun fill(form: Form, request: FillRequest, match: FillMatch) {
        val device = app.device ?: return finish()
        app.background({ device.fill(request, match.store, match.name) }) { result ->
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
