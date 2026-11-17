// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import android.app.KeyguardManager
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @Test
    fun aFreshInstallationShowsTheSetupAndKeepsScreenshotsOut() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val app = activity.application as PasejoApp
                assertNull(app.device)
                val flags = activity.window.attributes.flags
                assertTrue(flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                val texts = mutableListOf<String>()
                fun collect(view: android.view.View) {
                    if (view is android.widget.TextView) texts += view.text.toString()
                    if (view is android.view.ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
                }
                collect(activity.window.decorView)
                assertTrue(texts.toString(), activity.getString(R.string.setup_title) in texts)
            }
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
