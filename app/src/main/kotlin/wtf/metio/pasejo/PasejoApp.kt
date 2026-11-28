// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.RestrictionsManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors
import wtf.metio.pasejo.core.Device
import wtf.metio.pasejo.core.verifyAppDomain

/**
 * Holds the one device of this installation, its keys and the unlock
 * period. Store keys stay in memory only while the period runs; it ends
 * early when the screen turns off.
 */
class PasejoApp : Application() {
    lateinit var keys: DeviceKeys
        private set
    lateinit var states: StateStore
        private set
    val clock = UnlockClock()
    val links = LinkCache(::verifyAppDomain)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val expire = Runnable { lock() }

    /** Called on the main thread when the managed configuration changed. */
    var onPolicyChange: (() -> Unit)? = null

    @Volatile
    var device: Device? = null
        private set

    /** Why the saved device could not be restored, if it could not. */
    var restoreError: String? = null
        private set

    val managed: ManagedConfig
        get() {
            val restrictions = getSystemService(RestrictionsManager::class.java).applicationRestrictions
            @Suppress("DEPRECATION")
            return ManagedConfig.parse { key -> restrictions.get(key) }
        }

    override fun onCreate() {
        super.onCreate()
        keys = DeviceKeys(File(noBackupFilesDir, "keys"))
        states = StateStore(File(noBackupFilesDir, "device.state"))
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = lock()
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
        )
        // Only a running app receives restriction changes; one that starts
        // later reads the current restrictions anyway.
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = policyChanged()
            },
            IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
        try {
            device = states.load()?.let { Device.restore(it, keys.signer(), TrustAnchors.system()) }
        } catch (error: Exception) {
            restoreError = error.message ?: error.javaClass.simpleName
        }
    }

    /** The tier this phone supports; Curve25519 in KeyMint needs KeyMint 2. */
    fun supportedTier(): Tier =
        DeviceKeys.supportedTier(packageManager.hasSystemFeature(PackageManager.FEATURE_HARDWARE_KEYSTORE, 200))

    /**
     * Creates the keys and a device for `serverUrl`. The age identity needs
     * the screen lock or a biometric, so a phone without a secure lock
     * screen gets no keys at all.
     */
    fun create(serverUrl: String): Device {
        if (!getSystemService(KeyguardManager::class.java).isDeviceSecure) throw NoScreenLockException()
        keys.create(supportedTier())
        val created = Device.create(serverUrl, Build.MODEL, keys.signer(), keys.agreementPublicKey(), TrustAnchors.system())
        device = created
        restoreError = null
        save()
        return created
    }

    fun save() {
        device?.let { states.save(it.state()) }
    }

    /**
     * Whether store keys are open; drops them once the period or the
     * session ended, since an unlock period never outlasts the session.
     */
    fun unlocked(): Boolean {
        val current = device ?: return false
        if (!clock.open() || !current.signedIn()) current.lock()
        return current.unlocked()
    }

    /** The unlock period the person chose, before the policy caps it. */
    var chosenUnlockSeconds: Int
        get() = getSharedPreferences("unlock", MODE_PRIVATE).getInt("seconds", ManagedConfig.DEFAULT_UNLOCK_SECONDS)
        set(seconds) {
            getSharedPreferences("unlock", MODE_PRIVATE).edit().putInt("seconds", seconds).apply()
            shortenTo(managed.unlockSeconds(seconds))
        }

    /** The unlock period that applies: the person's choice, capped by the policy. */
    fun unlockSeconds(): Int = managed.unlockSeconds(chosenUnlockSeconds)

    /** Ends a running unlock period after `seconds` at the latest. */
    private fun shortenTo(seconds: Int) {
        if (clock.open()) {
            clock.shorten(seconds)
            main.removeCallbacks(expire)
            main.postDelayed(expire, clock.remainingMillis())
        }
    }

    /** Applies a new managed configuration to a running unlock period. */
    fun policyChanged() {
        if (clock.open()) {
            clock.shorten(unlockSeconds())
            main.removeCallbacks(expire)
            main.postDelayed(expire, clock.remainingMillis())
        }
        onPolicyChange?.invoke()
    }

    fun opened(seconds: Int) {
        clock.unlock(seconds)
        main.removeCallbacks(expire)
        main.postDelayed(expire, clock.remainingMillis())
    }

    fun lock() {
        main.removeCallbacks(expire)
        clock.lock()
        device?.lock()
    }

    /** Forgets the device: its keys, its state and its session. */
    fun reset() {
        lock()
        runCatching { device?.signOut() }
        device = null
        restoreError = null
        keys.destroy()
        states.delete()
    }

    /** Runs `work` off the main thread and hands its result to `done` on it. */
    fun <T> background(work: () -> T, done: (Result<T>) -> Unit) {
        worker.execute {
            val result = runCatching(work)
            main.post { done(result) }
        }
    }
}
