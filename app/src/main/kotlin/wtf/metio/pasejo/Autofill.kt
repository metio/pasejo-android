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

/** One view of an autofill structure, without Android types; `id` is set for text fields. */
data class Node<Id>(
    val id: Id? = null,
    val info: FieldInfo = FieldInfo(),
    val webDomain: String? = null,
    val children: List<Node<Id>> = emptyList(),
)

/** The fields of one form and the web domain they were reported under. */
data class Found<Id>(val targets: Targets<Id>, val webDomain: String?)

/** Reads forms and callers out of autofill structures. */
object Forms {
    /**
     * The fields to fill and their web domain. Every field takes the domain
     * of its nearest node that reports one, so a frame from another site
     * keeps its own domain; a form whose fields come from different domains
     * is not filled.
     */
    fun <Id> find(roots: List<Node<Id>>): Found<Id>? {
        val fields = mutableListOf<Pair<Id, FieldInfo>>()
        val domains = HashMap<Id, String?>()
        fun visit(node: Node<Id>, inherited: String?) {
            val domain = node.webDomain ?: inherited
            node.id?.let { id ->
                fields += id to node.info
                domains[id] = domain
            }
            node.children.forEach { visit(it, domain) }
        }
        roots.forEach { visit(it, null) }
        val targets = Targets.pick(fields) ?: return null
        val chosen = targets.ids.map { domains.getValue(it) }.toSet()
        return if (chosen.size == 1) Found(targets, chosen.single()) else null
    }

    fun read(context: Context, structure: AssistStructure): Form? {
        val packageName = structure.activityComponent.packageName
        if (packageName == context.packageName) return null
        val roots = (0 until structure.windowNodeCount).map { node(structure.getWindowNodeAt(it).rootViewNode) }
        val found = find(roots) ?: return null
        return Form(found.targets, Caller(packageName, certificates(context, packageName), found.webDomain))
    }

    private fun node(view: AssistStructure.ViewNode): Node<AutofillId> {
        fun attribute(name: String) = view.htmlInfo?.attributes?.firstOrNull { it.first == name }?.second
        val text = view.autofillId != null && view.autofillType == android.view.View.AUTOFILL_TYPE_TEXT
        return Node(
            id = view.autofillId.takeIf { text },
            info = FieldInfo(
                hints = view.autofillHints?.toList().orEmpty(),
                inputType = view.inputType,
                htmlType = attribute("type"),
                htmlAutocomplete = attribute("autocomplete"),
                idEntry = view.idEntry,
                hint = view.hint,
            ),
            webDomain = view.webDomain,
            children = (0 until view.childCount).map { node(view.getChildAt(it)) },
        )
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
