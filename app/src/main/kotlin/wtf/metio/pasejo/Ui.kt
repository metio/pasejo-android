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
import android.view.View
import android.view.WindowManager
import wtf.metio.pasejo.core.MobileException

/**
 * Keeps secrets off screenshots, recents and screen sharing, and keeps
 * autofill services, pasejo's own included, away from pasejo's fields.
 */
fun Activity.secure() {
    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
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
