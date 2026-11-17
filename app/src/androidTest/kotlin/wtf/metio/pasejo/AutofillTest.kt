// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import android.content.Intent
import android.os.SystemClock
import android.view.autofill.AutofillManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.BeforeClass
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

    @Before
    fun noDevice() = assertNull((instrumentation.targetContext.applicationContext as PasejoApp).device)

    @After
    fun dismiss() {
        ui.pressBack()
    }

    /**
     * Starts the form and waits for its window, so a field found afterwards
     * belongs to it rather than to the form an earlier test left on screen.
     */
    private fun open(activity: Class<*>) {
        val start = Intent().setClassName(testPackage, activity.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        assertTrue("no window for ${activity.simpleName}", ui.performActionAndWait({ instrumentation.context.startActivity(start) }, Until.newWindow(), TIMEOUT))
        assertNotNull(ui.wait(Until.hasObject(By.pkg(testPackage).depth(0)), TIMEOUT))
        ui.waitForIdle()
    }

    private fun focus(description: String) {
        val field = ui.wait(Until.findObject(By.desc(description)), TIMEOUT)
        assertNotNull("no field $description", field)
        field.click()
    }

    /**
     * pasejo's entry on the login form. On a slow emulator a single focus
     * does not always bring up the suggestion, so the form opens again
     * before the entry counts as missing.
     */
    private fun loginEntry(): UiObject2? {
        repeat(ATTEMPTS) {
            open(LoginActivity::class.java)
            focus("username")
            ui.wait(Until.findObject(By.text(entry)), TIMEOUT)?.let { return it }
        }
        return null
    }

    @Test
    fun aLoginFormOffersPasejo() {
        assertNotNull("no autofill entry", loginEntry())
    }

    @Test
    fun choosingTheEntryWithoutDeviceFillsNothing() {
        val offered = loginEntry()
        assertNotNull("no autofill entry", offered)
        offered!!.click()
        // The fill activity opens and, without a device, finishes at once.
        assertNotNull(ui.wait(Until.hasObject(By.desc("username")), TIMEOUT))
        ui.waitForIdle()
        // Accessibility reports an empty field as no text or as its hint.
        assertTrue(ui.findObject(By.desc("username")).text in setOf(null, "", "Username"))
        assertTrue(ui.findObject(By.desc("password")).text in setOf(null, "", "Password"))
    }

    @Test
    fun aFormWithoutLoginFieldsOffersNothing() {
        assertNotNull("no autofill entry on the control form", loginEntry())
        open(SearchActivity::class.java)
        focus("search")
        assertNull(ui.wait(Until.findObject(By.text(entry)), ABSENCE))
    }

    @Test
    fun pasejoOffersNothingOnItsOwnScreens() {
        assertNotNull("no autofill entry on the control form", loginEntry())
        instrumentation.targetContext.startActivity(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val pasejo = instrumentation.targetContext.packageName
        val server = ui.wait(Until.findObject(By.pkg(pasejo).clazz("android.widget.EditText")), TIMEOUT)
        assertNotNull(server)
        server.click()
        assertNull(ui.wait(Until.findObject(By.text(entry)), ABSENCE))
    }

    companion object {
        private const val TIMEOUT = 10_000L
        private const val ABSENCE = 3_000L
        private const val ATTEMPTS = 3
        private var previous = ""

        private fun shell(command: String): String =
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand(command).trim()

        /**
         * pasejo stays the autofill service for the whole class, and the
         * tests start only once the system's autofill manager reports
         * pasejo's service enabled.
         */
        @BeforeClass
        @JvmStatic
        fun usePasejo() {
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            previous = shell("settings get secure autofill_service")
            shell("settings put secure autofill_service ${target.packageName}/${PasejoAutofillService::class.java.name}")
            val autofill = target.getSystemService(AutofillManager::class.java)
            val deadline = SystemClock.uptimeMillis() + TIMEOUT
            while (!autofill.hasEnabledAutofillServices() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            assertTrue("pasejo is not the autofill service", autofill.hasEnabledAutofillServices())
        }

        @AfterClass
        @JvmStatic
        fun restore() {
            if (previous.isEmpty() || previous == "null") {
                shell("settings delete secure autofill_service")
            } else {
                shell("settings put secure autofill_service $previous")
            }
        }
    }
}
