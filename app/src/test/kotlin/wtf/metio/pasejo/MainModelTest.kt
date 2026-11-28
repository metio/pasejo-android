// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wtf.metio.pasejo.core.DeviceInterface
import wtf.metio.pasejo.core.MobileException

class MainModelTest {
    private val store = teamStore

    /** The main screen's environment over the shared fake app. */
    private class FakeEnvironment(device: DeviceInterface?) : FakePlatform(device), MainEnvironment {
        val copied = mutableListOf<String>()
        var createError: Throwable? = null
        var browseError: Throwable? = null
        val browsed = mutableListOf<String>()
        var resetInsideRequest: Boolean? = null

        override var restoreError: String? = null
        override var managed = ManagedConfig()
        override val keyTier: Tier? = Tier.HARDWARE
        override val supportedTier = Tier.HARDWARE
        override var chosenUnlockSeconds = 300
        override var setup = SetupValues("https://pasejo.example", "acme", "genesis", "")

        override fun unlockSeconds() = managed.unlockSeconds(chosenUnlockSeconds)
        override fun reset() {
            resetInsideRequest = inRequest
            device = null
        }
        override fun browse(url: String) {
            browseError?.let { throw it }
            browsed += url
        }
        override fun create(server: String): DeviceInterface = FakeDevice().also {
            it.connectError = createError
            device = it
        }
        override fun autofillMissing() = false
        override fun copy(value: String) {
            copied += value
        }
    }

    private fun model(device: FakeDevice? = FakeDevice()): Pair<MainModel, FakeEnvironment> {
        val env = FakeEnvironment(device)
        val model = MainModel(env)
        env.redraw = model::render
        return model to env
    }

    private fun MainModel.openEntryOf(name: String) {
        openStore(store)
        openEntry(name)
    }

    @Test
    fun aFreshInstallationShowsTheSetupWithTheSavedValues() {
        val (model, _) = model(device = null)
        model.render()
        val route = model.ui.route as Route.Setup
        assertEquals(SetupField("https://pasejo.example"), route.server)
        assertEquals(SetupField("acme"), route.org)
    }

    @Test
    fun managedValuesFixTheSetupFields() {
        val (model, env) = model(device = null)
        env.managed = ManagedConfig(serverUrl = "https://policy.example")
        model.render()
        assertEquals(SetupField("https://policy.example", managed = true), (model.ui.route as Route.Setup).server)
    }

    @Test
    fun anOpenDeviceShowsItsStores() {
        val (model, _) = model()
        model.render()
        assertTrue(model.ui.route is Route.Stores)
    }

    @Test
    fun aDeviceWithoutASessionAsksForSignIn() {
        val (model, env) = model()
        env.fake.signedIn = false
        model.render()
        assertEquals(Route.SignIn("https://pasejo.example"), model.ui.route)
    }

    @Test
    fun aRevealedValueIsShownOnItsEntry() {
        val (model, env) = model()
        model.openEntryOf("mail")
        model.reveal(store, "mail")
        env.finish()
        assertEquals(Shown.Value("hunter2\nuser: me"), model.ui.value)
        assertEquals(1, env.saves)
    }

    @Test
    fun anEndedUnlockPeriodHidesARevealedValue() {
        val (model, env) = model()
        model.openEntryOf("mail")
        model.reveal(store, "mail")
        env.finish()
        env.lock()
        // PasejoApp calls render through onChange when the period ends.
        model.render()
        assertEquals(Shown.Hidden, model.ui.value)
        assertTrue(model.ui.route is Route.Locked)
    }

    @Test
    fun goingBackDuringARevealLeavesTheEntryAndDropsTheValue() {
        val (model, env) = model()
        model.openEntryOf("mail")
        model.reveal(store, "mail")
        model.back()
        env.finishAll()
        assertEquals(Route.Entries(store), model.ui.route)
        assertEquals(Shown.Hidden, model.ui.value)
    }

    @Test
    fun aPolicyChangeDuringARequestAppliesOnceItFinished() {
        val (model, env) = model()
        model.openEntryOf("mail")
        model.reveal(store, "mail")
        env.managed = ManagedConfig(allowCopy = false)
        model.render()
        env.finishAll()
        assertEquals(false, (model.ui.route as Route.Entry).allowCopy)
    }

    @Test
    fun theDeviceIsNotReadOutsideARunningRequest() {
        val (model, env) = model()
        model.openEntryOf("mail")
        model.reveal(store, "mail")
        model.render()
        model.back()
        model.copyPassword(store, "mail")
        env.finishAll()
        assertEquals(emptyList<String>(), env.fake.outsideCalls)
    }

    @Test
    fun unlockingRunsAsARequestSoTheScreenWaitsForIt() {
        val (model, env) = model()
        env.periodOpen = false
        env.authentication = Result.success { unusedIdentity }
        model.render()
        env.finishAll()
        model.unlock()
        // The activity resumes when the system prompt closes.
        model.render()
        assertEquals(emptyList<String>(), env.fake.outsideCalls)
        env.finishAll()
        assertTrue(model.ui.route is Route.Stores)
    }

    @Test
    fun aCancelledPromptShowsTheError() {
        val (model, env) = model()
        env.periodOpen = false
        env.authentication = Result.failure(SecurityException("cancelled"))
        model.render()
        env.finishAll()
        model.unlock()
        assertEquals(Route.Failed("error cancelled"), model.ui.route)
    }

    @Test
    fun theLockedScreenShowsTheApprovalStatus() {
        val (model, env) = model()
        env.periodOpen = false
        model.render()
        val route = model.ui.route as Route.Locked
        assertEquals("pasejo org approve device-1 --fingerprint 'ABCD 1234' --org acme", route.command)
        env.finishAll()
        assertEquals(true, model.ui.lockStatus?.canUnlock)
    }

    @Test
    fun copyingThePasswordCopiesItsFirstLine() {
        val (model, env) = model()
        model.openEntryOf("mail")
        model.copyPassword(store, "mail")
        env.finishAll()
        assertEquals(listOf("hunter2"), env.copied)
    }

    @Test
    fun aCodeIsShownWithANewGeneration() {
        val (model, env) = model()
        model.openEntryOf("mail")
        model.showCode(store, "mail")
        env.finishAll()
        model.nextCode()
        env.finishAll()
        assertEquals("123456", model.ui.liveCode?.code)
        assertEquals(2, model.ui.codeGeneration)
    }

    @Test
    fun aFailedSetupForgetsTheDevice() {
        val (model, env) = model(device = null)
        env.createError = IllegalStateException("offline")
        model.create(SetupValues("https://pasejo.example", "acme", "genesis", ""))
        env.finishAll()
        assertEquals(null, env.device)
        assertEquals(Route.Failed("error offline"), model.ui.route)
    }

    @Test
    fun aRecreatedScreenWaitsForTheRequestOfTheOldOne() {
        val (old, env) = model()
        old.openEntryOf("mail")
        old.reveal(store, "mail")
        // Rotation: a new activity with its own model, on the same app.
        val recreated = MainModel(env)
        env.redraw = recreated::render
        recreated.render()
        assertEquals(emptyList<String>(), env.fake.outsideCalls)
        env.finishAll()
        assertTrue(recreated.ui.route is Route.Stores)
    }

    @Test
    fun withoutTheServerTheLockedScreenStillOffersUnlock() {
        val (model, env) = model()
        env.periodOpen = false
        env.fake.refreshError = MobileException.Network("unreachable")
        model.render()
        env.finishAll()
        assertEquals(true, model.ui.lockStatus?.canUnlock)
    }

    @Test
    fun otherFailuresOfTheCheckKeepUnlockClosed() {
        val (model, env) = model()
        env.periodOpen = false
        env.fake.refreshError = MobileException.Refused("removed")
        model.render()
        env.finishAll()
        assertEquals(false, model.ui.lockStatus?.canUnlock)
    }

    @Test
    fun aFailedSetupIsUndoneInsideItsRequest() {
        val (model, env) = model(device = null)
        env.createError = IllegalStateException("checkpoint mismatch")
        model.create(SetupValues("https://pasejo.example", "acme", "genesis", "key"))
        env.finishAll()
        assertEquals(true, env.resetInsideRequest)
        assertEquals(0, env.saves)
    }

    @Test
    fun aLockWhileUnlockingClosesTheStoresAgain() {
        val (model, env) = model()
        env.periodOpen = false
        model.render()
        env.finishAll()
        model.unlock()
        // The screen turns off before the queued unlock ran.
        env.lock()
        env.finishAll()
        assertTrue(model.ui.route is Route.Failed)
        assertEquals(false, env.unlocked())
        assertEquals(false, env.fake.unlocked)
    }

    @Test
    fun anExpiredCodeIsHiddenWhileAnotherRequestRuns() {
        val (model, env) = model()
        model.openEntryOf("mail")
        model.showCode(store, "mail")
        env.finishAll()
        model.reveal(store, "mail")
        model.nextCode()
        assertEquals(null, model.ui.liveCode)
        assertEquals(Shown.Hidden, model.ui.code)
    }

    @Test
    fun signInOpensTheServersPage() {
        val (model, env) = model()
        env.fake.signedIn = false
        model.render()
        model.signIn()
        assertEquals(listOf("https://pasejo.example/auth"), env.browsed)
    }

    @Test
    fun aPhoneWithoutABrowserShowsAnError() {
        val (model, env) = model()
        env.fake.signedIn = false
        env.browseError = IllegalStateException("no browser")
        model.render()
        model.signIn()
        assertEquals(Route.Failed("error no browser"), model.ui.route)
    }

    @Test
    fun hidingDropsValuesAndCodes() {
        val (model, env) = model()
        model.openEntryOf("mail")
        model.reveal(store, "mail")
        env.finishAll()
        model.hide()
        assertEquals(MainUi(route = model.ui.route), model.ui)
    }
}
