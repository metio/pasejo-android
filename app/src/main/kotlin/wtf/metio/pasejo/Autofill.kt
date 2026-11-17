// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.Presentations
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.widget.RemoteViews

/** One form, read from the structure the autofill framework passed. */
data class Form(val targets: Targets<AutofillId>, val caller: Caller)

/** Reads forms and callers out of autofill structures. */
object Forms {
    fun read(context: Context, structure: AssistStructure): Form? {
        val fields = mutableListOf<Pair<AutofillId, FieldInfo>>()
        var webDomain: String? = null
        fun visit(node: AssistStructure.ViewNode) {
            if (webDomain == null) webDomain = node.webDomain
            val id = node.autofillId
            if (id != null && node.autofillType == android.view.View.AUTOFILL_TYPE_TEXT) {
                fields += id to FieldInfo(
                    hints = node.autofillHints?.toList().orEmpty(),
                    inputType = node.inputType,
                    htmlType = node.htmlInfo?.attributes?.firstOrNull { it.first == "type" }?.second,
                    htmlAutocomplete = node.htmlInfo?.attributes?.firstOrNull { it.first == "autocomplete" }?.second,
                    idEntry = node.idEntry,
                    hint = node.hint,
                )
            }
            for (index in 0 until node.childCount) visit(node.getChildAt(index))
        }
        for (index in 0 until structure.windowNodeCount) visit(structure.getWindowNodeAt(index).rootViewNode)
        val targets = Targets.pick(fields) ?: return null
        val packageName = structure.activityComponent.packageName
        if (packageName == context.packageName) return null
        return Form(targets, Caller(packageName, certificates(context, packageName), webDomain))
    }

    /** SHA-256 fingerprints of the certificates the package's contents are signed with now. */
    fun certificates(context: Context, packageName: String): List<String> = try {
        context.packageManager
            .getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            .signingInfo
            ?.apkContentsSigners
            ?.map { Fingerprints.sha256(it.toByteArray()) }
            .orEmpty()
    } catch (_: PackageManager.NameNotFoundException) {
        emptyList()
    }
}

/**
 * Offers one entry, "Fill with pasejo", on every login form. Choosing it
 * opens [FillActivity], which authenticates, lists the matches and fills
 * the chosen credential; the service itself reads no secrets.
 */
class PasejoAutofillService : AutofillService() {
    override fun onFillRequest(request: FillRequest, cancellation: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess(null)
        val form = Forms.read(this, structure) ?: return callback.onSuccess(null)
        val intent = Intent(this, FillActivity::class.java)
        val sender = PendingIntent.getActivity(
            this,
            request.id,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
        ).intentSender
        val label = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
            setTextViewText(android.R.id.text1, getString(R.string.autofill_entry))
        }
        val response = FillResponse.Builder()
            .setAuthentication(form.targets.ids.toTypedArray(), sender, Presentations.Builder().setMenuPresentation(label).build())
            .build()
        callback.onSuccess(response)
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        callback.onFailure(getString(R.string.autofill_no_save))
    }
}
