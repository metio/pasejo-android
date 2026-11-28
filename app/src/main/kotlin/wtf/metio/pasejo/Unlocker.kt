// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import android.app.Activity
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import javax.crypto.Cipher
import wtf.metio.pasejo.core.KeyAgreement

/**
 * Asks the person to authenticate for the age identity. The sealed tier
 * binds the prompt to the cipher that unseals the identity; the hardware
 * tier's key accepts agreements for a short window after any strong
 * authentication.
 */
object Unlocker {
    /**
     * Asks the person to authenticate and hands back the age identity, which
     * is to be opened off the main thread.
     */
    fun identity(activity: Activity, done: (Result<() -> KeyAgreement>) -> Unit) {
        val keys = (activity.application as PasejoApp).keys
        val cipher = try {
            keys.unsealCipher()
        } catch (error: Exception) {
            return done(Result.failure(error))
        }
        authenticate(activity, cipher) { authenticated ->
            done(authenticated.map { unsealer -> { keys.agreement(unsealer) } })
        }
    }

    /**
     * Shows the system's authentication prompt, bound to `cipher` when there
     * is one, and hands back the cipher the authentication unlocked.
     */
    fun authenticate(activity: Activity, cipher: Cipher?, done: (Result<Cipher?>) -> Unit) {
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(activity.getString(R.string.unlock_title))
            .setSubtitle(activity.getString(R.string.unlock_subtitle))
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
            .build()
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                done(Result.success(result.cryptoObject?.cipher))
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
