// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import wtf.metio.pasejo.testapp.PolicyReceiver

/**
 * The managed configuration as a device policy controller sets it: the test
 * APK becomes device owner, which `dpm` allows only on a device without
 * accounts, such as a fresh emulator; elsewhere the tests are skipped.
 */
@RunWith(AndroidJUnit4::class)
class ManagedConfigurationTest {
    companion object {
        private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
        private val testPackage get() = instrumentation.context.packageName
        private var owner = false
        private var refusal = ""

        private fun shell(command: String): String =
            instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().readText()
            }

        @BeforeClass
        @JvmStatic
        fun becomeDeviceOwner() {
            val admin = "$testPackage/wtf.metio.pasejo.testapp.TestAdmin"
            val output = shell("dpm set-device-owner $admin")
            owner = output.contains("Success") || shell("dumpsys device_policy").contains("admin=ComponentInfo{$admin}")
            refusal = output.trim()
        }

        @AfterClass
        @JvmStatic
        fun releaseDeviceOwner() {
            if (owner) assertEquals(PolicyReceiver.DONE, ask(Intent(PolicyReceiver.RELEASE)).first)
        }

        /** Sends `intent` to the test APK's policy receiver and waits for its result. */
        fun ask(intent: Intent): Pair<Int, String?> {
            val done = CountDownLatch(1)
            var outcome: Pair<Int, String?> = 0 to null
            val thread = HandlerThread("policy").apply { start() }
            try {
                instrumentation.targetContext.sendOrderedBroadcast(
                    intent.setClassName(testPackage, PolicyReceiver::class.java.name),
                    null,
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context, result: Intent) {
                            outcome = resultCode to resultData
                            done.countDown()
                        }
                    },
                    Handler(thread.looper),
                    0,
                    null,
                    null,
                )
                assertTrue("the policy receiver did not answer", done.await(10, TimeUnit.SECONDS))
            } finally {
                thread.quitSafely()
            }
            return outcome
        }
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = instrumentation.targetContext.applicationContext as PasejoApp

    private fun restrict(restrictions: Bundle) {
        val (code, error) = ask(Intent(PolicyReceiver.SET).putExtra(PolicyReceiver.RESTRICTIONS, restrictions))
        assertEquals(error, PolicyReceiver.DONE, code)
    }

    @Before
    fun requireDeviceOwner() = assumeTrue("the test APK cannot become device owner: $refusal", owner)

    @After
    fun clear() {
        if (owner) restrict(Bundle())
    }

    @Test
    fun withoutRestrictionsThePersonChooses() {
        assertEquals(ManagedConfig(), app.managed)
    }

    @Test
    fun setRestrictionsReachTheApp() {
        restrict(
            Bundle().apply {
                putString("server_url", " https://pasejo.example.com ")
                putString("organization", "0123456789abcdef0123456789abcdef")
                putInt("max_unlock_seconds", 60)
                putBoolean("allow_copy", false)
            },
        )
        val managed = app.managed
        assertEquals("https://pasejo.example.com", managed.serverUrl)
        assertEquals("0123456789abcdef0123456789abcdef", managed.organization)
        assertEquals(null, managed.genesisHash)
        assertFalse(managed.allowCopy)
        assertEquals(60, managed.unlockSeconds())
    }

    @Test
    fun valuesOfTheWrongTypeAreIgnored() {
        restrict(
            Bundle().apply {
                putString("max_unlock_seconds", "60")
                putString("allow_copy", "false")
            },
        )
        assertEquals(ManagedConfig(), app.managed)
    }

    @Test
    fun theSetupScreenLocksManagedFields() {
        restrict(Bundle().apply { putString("server_url", "https://pasejo.example.com") })
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("setup-server").assertTextContains("https://pasejo.example.com").assertIsNotEnabled()
            compose.onNodeWithTag("setup-org").assertIsEnabled()
        }
    }
}
