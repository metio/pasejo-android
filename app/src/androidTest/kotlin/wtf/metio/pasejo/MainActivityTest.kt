// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import android.app.KeyguardManager
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun aFreshInstallationShowsTheSetupAndKeepsScreenshotsOut() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val app = activity.application as PasejoApp
                assertNull(app.device)
                val flags = activity.window.attributes.flags
                assertTrue(flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, activity.window.decorView.importantForAutofill)
            }
            val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            compose.onNodeWithText(context.getString(R.string.setup_title)).assertIsDisplayed()
        }
    }

    @Test
    fun aPhoneWithoutAScreenLockGetsNoKeys() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        assumeFalse("the phone has a screen lock", context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        val app = context.applicationContext as PasejoApp
        assertThrows(NoScreenLockException::class.java) { app.create("https://pasejo.example") }
        assertNull(app.keys.tier)
        assertNull(app.device)
    }

    @Test
    fun anEndedUnlockPeriodTellsTheOpenScreen() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as PasejoApp
        val told = java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync {
            app.onChange = { told.countDown() }
            app.opened(1)
        }
        try {
            assertTrue("the screen was not told", told.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(!app.clock.open())
        } finally {
            instrumentation.runOnMainSync { app.onChange = null }
        }
    }

    @Test
    fun theFillActivityCannotBeStartedByOtherApps() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, FillActivity::class.java),
            android.content.pm.PackageManager.ComponentInfoFlags.of(0),
        )
        assertTrue(!info.exported)
    }

    @Test
    fun nothingIsBackedUp() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val flags = context.applicationInfo.flags
        assertTrue(flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP == 0)
    }
}
