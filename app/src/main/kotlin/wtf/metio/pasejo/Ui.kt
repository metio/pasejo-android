// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import wtf.metio.pasejo.core.MobileException

/** Builds one screen of plain framework views, top to bottom. */
class Screen(private val activity: Activity) {
    private val column = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(16)
        setPadding(pad, pad, pad, pad)
    }
    val root: View = ScrollView(activity).apply {
        addView(column)
        setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun dp(value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), activity.resources.displayMetrics).toInt()

    fun title(text: String) = add(TextView(activity).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setPadding(0, 0, 0, dp(12))
    })

    fun text(text: String, monospace: Boolean = false) = add(TextView(activity).apply {
        this.text = text
        setTextIsSelectable(false)
        if (monospace) typeface = android.graphics.Typeface.MONOSPACE
        setPadding(0, dp(4), 0, dp(4))
    })

    fun field(hint: String, value: String?, locked: Boolean = false, secret: Boolean = false) = add(EditText(activity).apply {
        this.hint = hint
        setText(value.orEmpty())
        isEnabled = !locked
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    })

    fun button(label: String, onClick: () -> Unit) = add(Button(activity).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    })

    fun <T : View> add(view: T): T {
        column.addView(view)
        return view
    }

    fun show() {
        activity.setContentView(root)
    }
}

/** Keeps secrets off screenshots, recents and screen sharing. */
fun Activity.secure() {
    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    setRecentsScreenshotEnabled(false)
}

/** A message for an error, without the internals of unexpected ones. */
fun Activity.describe(error: Throwable): String = when (error) {
    is MobileException.Network -> getString(R.string.error_network, error.reason)
    is MobileException.Refused -> getString(R.string.error_refused, error.reason)
    is MobileException.Verification -> getString(R.string.error_verification, error.reason)
    is MobileException.NotSignedIn -> getString(R.string.error_not_signed_in)
    is MobileException.Locked -> getString(R.string.error_locked)
    is MobileException.NotFound -> getString(R.string.error_not_found)
    is MobileException.Key -> getString(R.string.error_key, error.reason)
    is MobileException.Invalid -> error.reason
    is InsecureKeyStoreException -> getString(R.string.error_insecure_key_store)
    is NoScreenLockException -> getString(R.string.error_no_screen_lock)
    else -> error.message ?: error.javaClass.simpleName
}

/** Copies `value` as sensitive and clears it after `seconds` unless replaced. */
object Clipboard {
    const val CLEAR_SECONDS = 45
    private val main = Handler(Looper.getMainLooper())

    fun copy(activity: Activity, label: String, value: String) {
        val manager = activity.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(label, value).apply {
            description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        manager.setPrimaryClip(clip)
        main.postDelayed({
            runCatching {
                val current = manager.primaryClip
                if (current == null || current.description.label == label) manager.clearPrimaryClip()
            }
        }, CLEAR_SECONDS * 1000L)
    }
}
