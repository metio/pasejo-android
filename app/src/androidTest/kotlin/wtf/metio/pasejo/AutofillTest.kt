// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import wtf.metio.pasejo.testapp.LoginActivity
import wtf.metio.pasejo.testapp.SearchActivity

/**
 * pasejo as the system's autofill service, against forms of another app
 * (the test APK). The device under test has no pasejo device set up, so a
 * chosen entry must fill nothing.
 */
@RunWith(AndroidJUnit4::class)
class AutofillTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui = UiDevice.getInstance(instrumentation)
    private val testPackage = instrumentation.context.packageName
    private val entry get() = instrumentation.targetContext.getString(R.string.autofill_entry)
    private var previous: String = ""

    @Before
    fun usePasejo() {
        previous = ui.executeShellCommand("settings get secure autofill_service").trim()
        ui.executeShellCommand("settings put secure autofill_service ${instrumentation.targetContext.packageName}/${PasejoAutofillService::class.java.name}")
        assertNull((instrumentation.targetContext.applicationContext as PasejoApp).device)
    }

    @After
    fun restore() {
        ui.pressBack()
        if (previous.isEmpty() || previous == "null") {
            ui.executeShellCommand("settings delete secure autofill_service")
        } else {
            ui.executeShellCommand("settings put secure autofill_service $previous")
        }
    }

    private fun open(activity: Class<*>) {
        instrumentation.context.startActivity(
            Intent().setClassName(testPackage, activity.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        assertNotNull(ui.wait(Until.hasObject(By.pkg(testPackage).depth(0)), TIMEOUT))
    }

    private fun focus(description: String) {
        val field = ui.wait(Until.findObject(By.desc(description)), TIMEOUT)
        assertNotNull("no field $description", field)
        field.click()
    }

    @Test
    fun aLoginFormOffersPasejo() {
        open(LoginActivity::class.java)
        focus("username")
        assertNotNull("no autofill entry", ui.wait(Until.findObject(By.text(entry)), TIMEOUT))
    }

    @Test
    fun choosingTheEntryWithoutDeviceFillsNothing() {
        open(LoginActivity::class.java)
        focus("username")
        ui.wait(Until.findObject(By.text(entry)), TIMEOUT).click()
        // The fill activity opens and, without a device, finishes at once.
        assertNotNull(ui.wait(Until.hasObject(By.desc("username")), TIMEOUT))
        ui.waitForIdle()
        // Accessibility reports an empty field as no text or as its hint.
        assertTrue(ui.findObject(By.desc("username")).text in setOf(null, "", "Username"))
        assertTrue(ui.findObject(By.desc("password")).text in setOf(null, "", "Password"))
    }

    @Test
    fun aFormWithoutLoginFieldsOffersNothing() {
        open(LoginActivity::class.java)
        focus("username")
        assertNotNull("no autofill entry on the control form", ui.wait(Until.findObject(By.text(entry)), TIMEOUT))
        open(SearchActivity::class.java)
        focus("search")
        assertNull(ui.wait(Until.findObject(By.text(entry)), ABSENCE))
    }

    @Test
    fun pasejoOffersNothingOnItsOwnScreens() {
        open(LoginActivity::class.java)
        focus("username")
        assertNotNull("no autofill entry on the control form", ui.wait(Until.findObject(By.text(entry)), TIMEOUT))
        instrumentation.targetContext.startActivity(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val server = ui.wait(Until.findObject(By.clazz("android.widget.EditText")), TIMEOUT)
        assertNotNull(server)
        server.click()
        assertNull(ui.wait(Until.findObject(By.text(entry)), ABSENCE))
    }

    companion object {
        private const val TIMEOUT = 10_000L
        private const val ABSENCE = 3_000L
    }
}
