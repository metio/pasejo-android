// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import wtf.metio.pasejo.core.DeviceInterface
import wtf.metio.pasejo.core.DomainVerifier
import wtf.metio.pasejo.core.Enrollment
import wtf.metio.pasejo.core.FillCredential
import wtf.metio.pasejo.core.FillMatch
import wtf.metio.pasejo.core.FillRequest
import wtf.metio.pasejo.core.KeyAgreement
import wtf.metio.pasejo.core.OrgInfo
import wtf.metio.pasejo.core.QueryResult
import wtf.metio.pasejo.core.ServerInfo
import wtf.metio.pasejo.core.StoreView
import wtf.metio.pasejo.core.TotpCode

val teamStore = StoreView("s1", "Team", listOf("mail", "bank"), listOf("mail"))

fun match(name: String, confirm: Boolean = false) =
    FillMatch(store = "s1", storeLabel = "Team", name = name, username = "me", url = null, exact = true, confirm = confirm, score = 1.0)

/**
 * A device whose answers the tests set, and which records a call made
 * outside a request while a request holds the core's lock.
 */
class FakeDevice : DeviceInterface {
    var signedIn = true
    var organization: String? = "acme"
    var unlocked = true
    var held = false
    var insideRequest = false
    var connectError: Throwable? = null
    var refreshError: Throwable? = null
    val outsideCalls = mutableListOf<String>()
    val secrets = mutableMapOf("mail" to "hunter2\nuser: me")
    var matches = listOf(match("mail"))
    var credential = FillCredential("me", "hunter2", "123456")
    val fills = mutableListOf<String>()
    val queries = mutableListOf<FillRequest>()

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
    override fun fill(request: FillRequest, store: String, name: String): FillCredential {
        call("fill")
        check(unlocked) { "locked" }
        fills += name
        return credential
    }
    override fun fingerprint() = "ABCD 1234".also { call("fingerprint") }
    override fun finishSignIn(redirect: String) = call("finishSignIn")
    override fun join(org: String, genesis: String) = Enrollment(pending = true, active = false).also { call("join") }
    override fun lock() {
        call("lock")
        unlocked = false
    }
    override fun offline() = false.also { call("offline") }
    override fun organization() = organization.also { call("organization") }
    override fun query(request: FillRequest, verifier: DomainVerifier?): QueryResult {
        call("query")
        queries += request
        return QueryResult(matches, emptyList(), request)
    }
    override fun refresh(): OrgInfo {
        call("refresh")
        refreshError?.let { throw it }
        return OrgInfo("acme", active = true, offlineReads = false)
    }
    override fun reveal(store: String, name: String) = secrets.getValue(name).also { call("reveal") }
    override fun serverUrl() = "https://pasejo.example".also { call("serverUrl") }
    override fun signOut() = call("signOut")
    override fun signedIn() = signedIn.also { call("signedIn") }
    override fun startSignIn(redirectUri: String) = "https://pasejo.example/auth".also { call("startSignIn") }
    override fun state() = "{}".also { call("state") }
    override fun stores() = listOf(teamStore).also { call("stores") }
    override fun totp(store: String, name: String) = TotpCode("123456", 20u).also { call("totp") }
    override fun unlock(identity: KeyAgreement) = stores().also {
        call("unlock")
        unlocked = true
    }
    override fun unlocked() = unlocked.also { call("unlocked") }
}

/** An age identity the fake device never uses. */
val unusedIdentity = object : KeyAgreement {
    override fun publicKey() = ByteArray(32)
    override fun agree(share: ByteArray) = ByteArray(32)
}

/**
 * The app as the screens see it. Requests wait in a queue until the test
 * finishes them, as on the worker; the requests count is the real one.
 */
open class FakePlatform(override var device: DeviceInterface?) : Platform {
    val fake get() = device as FakeDevice
    override val requests = InFlight()
    val queue = ArrayDeque<() -> Unit>()
    var authentication: Result<() -> KeyAgreement>? = Result.success { unusedIdentity }
    var authentications = 0
    var periodOpen = true
    var saves = 0
    override var locks = 0

    /** Whether a request's work runs right now. */
    var inRequest = false

    /** What PasejoApp calls as onChange: the redraw of the screen in front. */
    var redraw: () -> Unit = {}

    override fun unlocked() = periodOpen && device?.unlocked() == true
    override fun lock() {
        periodOpen = false
        locks++
        device?.lock()
    }
    override fun save() {
        saves++
    }
    override fun text(id: Int, vararg args: Any) = "text $id ${args.toList()}"
    override fun describe(error: Throwable) = "error ${error.message?.takeIf(String::isNotEmpty) ?: error.javaClass.simpleName}"
    override fun authenticate(done: (Result<() -> KeyAgreement>) -> Unit) {
        authentications++
        done(requireNotNull(authentication))
    }
    override fun opened() {
        periodOpen = true
    }

    override fun <T> background(work: () -> T, done: (Result<T>) -> Unit) {
        requests.started()
        (device as? FakeDevice)?.held = true
        queue.addLast {
            val device = device as? FakeDevice
            device?.insideRequest = true
            inRequest = true
            val result = try {
                runCatching(work)
            } finally {
                device?.insideRequest = false
                inRequest = false
            }
            device?.held = queue.isNotEmpty()
            requests.finished({ done(result) }, redraw)
        }
    }

    /** Finishes the oldest running request. */
    fun finish() = queue.removeFirst()()

    fun finishAll() {
        while (queue.isNotEmpty()) finish()
    }
}
