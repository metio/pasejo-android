// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import android.app.Activity
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import wtf.metio.pasejo.core.StoreView

/**
 * Asks the person to authenticate and opens the store keys with the age
 * identity. The sealed tier binds the prompt to the cipher that unseals the
 * identity; the hardware tier's key accepts agreements for a short window
 * after any strong authentication.
 */
object Unlocker {
    fun unlock(activity: Activity, done: (Result<List<StoreView>>) -> Unit) {
        val app = activity.application as PasejoApp
        val device = app.device ?: return done(Result.failure(IllegalStateException("no device")))
        val cipher = try {
            app.keys.unsealCipher()
        } catch (error: Exception) {
            return done(Result.failure(error))
        }
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(activity.getString(R.string.unlock_title))
            .setSubtitle(activity.getString(R.string.unlock_subtitle))
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
            .build()
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                app.background({
                    val identity = app.keys.agreement(result.cryptoObject?.cipher)
                    device.unlock(identity)
                }) { opened ->
                    if (opened.isSuccess) {
                        app.opened(app.managed.unlockSeconds())
                        app.save()
                    }
                    done(opened)
                }
            }

            override fun onAuthenticationError(code: Int, message: CharSequence) {
                done(Result.failure(SecurityException(message.toString())))
            }
        }
        val cancel = CancellationSignal()
        if (cipher != null) {
            prompt.authenticate(BiometricPrompt.CryptoObject(cipher), cancel, activity.mainExecutor, callback)
        } else {
            prompt.authenticate(cancel, activity.mainExecutor, callback)
        }
    }
}
