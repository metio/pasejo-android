// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wtf.metio.pasejo.core.DeviceInterface
import wtf.metio.pasejo.core.DomainVerifier
import wtf.metio.pasejo.core.Enrollment
import wtf.metio.pasejo.core.FillCredential
import wtf.metio.pasejo.core.FillRequest
import wtf.metio.pasejo.core.KeyAgreement
import wtf.metio.pasejo.core.OrgInfo
import wtf.metio.pasejo.core.QueryResult
import wtf.metio.pasejo.core.ServerInfo
import wtf.metio.pasejo.core.StoreView
import wtf.metio.pasejo.core.TotpCode

class MainModelTest {
    private val store = StoreView("s1", "Team", listOf("mail", "bank"), listOf("mail"))

    /**
     * A device whose calls are counted, and which records a call made outside
     * a request while a request holds the core's lock.
     */
    private class FakeDevice : DeviceInterface {
        var signedIn = true
        var organization: String? = "acme"
        var unlocked = true
        var held = false
        var insideRequest = false
        var connectError: Throwable? = null
        val outsideCalls = mutableListOf<String>()
        val secrets = mutableMapOf("mail" to "hunter2\nuser: me")

        private fun call(name: String) {
            if (held && !insideRequest) outsideCalls += name
        }

        override fun connect(checkpointKey: String?): ServerInfo {
            call("connect")
            connectError?.let { throw it }
            return ServerInfo("1", "key", false)
        }
        override fun deviceId() = "device-1".also { call("deviceId") }
        override fun enrollment() = Enrollment(pending = false, active = true).also { call("enrollment") }
        override fun fill(request: FillRequest, store: String, name: String): FillCredential = TODO()
        override fun fingerprint() = "ABCD 1234".also { call("fingerprint") }
        override fun finishSignIn(redirect: String) = call("finishSignIn")
        override fun join(org: String, genesis: String) = Enrollment(pending = true, active = false).also { call("join") }
        override fun lock() {
            call("lock")
            unlocked = false
        }
        override fun offline() = false.also { call("offline") }
        override fun organization() = organization.also { call("organization") }
        override fun query(request: FillRequest, verifier: DomainVerifier?): QueryResult = TODO()
        override fun refresh() = OrgInfo("acme", active = true, offlineReads = false).also { call("refresh") }
        override fun reveal(store: String, name: String) = secrets.getValue(name).also { call("reveal") }
        override fun serverUrl() = "https://pasejo.example".also { call("serverUrl") }
        override fun signOut() = call("signOut")
        override fun signedIn() = signedIn.also { call("signedIn") }
        override fun startSignIn(redirectUri: String) = "https://pasejo.example/auth".also { call("startSignIn") }
        override fun state() = "{}".also { call("state") }
        override fun stores() = listOf(StoreView("s1", "Team", listOf("mail", "bank"), listOf("mail"))).also { call("stores") }
        override fun totp(store: String, name: String) = TotpCode("123456", 20u).also { call("totp") }
        override fun unlock(identity: KeyAgreement) = stores().also {
            call("unlock")
            unlocked = true
        }
        override fun unlocked() = unlocked.also { call("unlocked") }
    }

    /** Requests wait in a queue until the test finishes them, as on the worker. */
    private class FakeEnvironment(override var device: DeviceInterface?) : MainEnvironment {
        val fake get() = device as FakeDevice
        val queue = ArrayDeque<() -> Unit>()
        val copied = mutableListOf<String>()
        var authentication: Result<() -> KeyAgreement>? = null
        var periodOpen = true
        var saves = 0
        var createError: Throwable? = null

        override var restoreError: String? = null
        override var managed = ManagedConfig()
        override val keyTier: Tier? = Tier.HARDWARE
        override val supportedTier = Tier.HARDWARE
        override var chosenUnlockSeconds = 300
        override var setup = SetupValues("https://pasejo.example", "acme", "genesis", "")

        override fun unlocked() = periodOpen && device?.unlocked() == true
        override fun unlockSeconds() = managed.unlockSeconds(chosenUnlockSeconds)
        override fun lock() {
            periodOpen = false
            device?.lock()
        }
        override fun reset() {
            device = null
        }
        override fun create(server: String): DeviceInterface = FakeDevice().also {
            it.connectError = createError
            device = it
        }
        override fun save() {
            saves++
        }
        override fun autofillMissing() = false
        override fun text(id: Int, vararg args: Any) = "text $id ${args.toList()}"
        override fun describe(error: Throwable) = "error ${error.message}"
        override fun copy(value: String) {
            copied += value
        }
        override fun authenticate(done: (Result<() -> KeyAgreement>) -> Unit) = done(requireNotNull(authentication))
        override fun opened() {
            periodOpen = true
        }

        override fun <T> background(work: () -> T, done: (Result<T>) -> Unit) {
            (device as? FakeDevice)?.held = true
            queue.addLast {
                val result = (device as? FakeDevice).inside { runCatching(work) }
                (device as? FakeDevice)?.held = queue.isNotEmpty()
                done(result)
            }
        }

        private fun <T> FakeDevice?.inside(block: () -> T): T {
            this?.insideRequest = true
            try {
                return block()
            } finally {
                this?.insideRequest = false
            }
        }

        /** Finishes the oldest running request. */
        fun finish() = queue.removeFirst()()

        fun finishAll() {
            while (queue.isNotEmpty()) finish()
        }
    }

    private val identity = object : KeyAgreement {
        override fun publicKey() = ByteArray(32)
        override fun agree(share: ByteArray) = ByteArray(32)
    }

    private fun model(device: FakeDevice? = FakeDevice()): Pair<MainModel, FakeEnvironment> {
        val env = FakeEnvironment(device)
        return MainModel(env) to env
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
        env.authentication = Result.success { identity }
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
    fun hidingDropsValuesAndCodes() {
        val (model, env) = model()
        model.openEntryOf("mail")
        model.reveal(store, "mail")
        env.finishAll()
        model.hide()
        assertEquals(MainUi(route = model.ui.route), model.ui)
    }
}
