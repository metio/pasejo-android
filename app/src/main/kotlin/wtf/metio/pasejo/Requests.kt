// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import wtf.metio.pasejo.core.AppRequest
import wtf.metio.pasejo.core.FillRequest

/** The fields of one form that the app can fill, by kind. */
data class Targets<Id>(val username: Id?, val password: Id?, val otp: Id?) {
    val ids: List<Id> get() = listOfNotNull(username, password, otp)

    companion object {
        /** The first field of every kind, or `null` when the form has none. */
        fun <Id> pick(fields: List<Pair<Id, FieldInfo>>): Targets<Id>? {
            val kinds = fields.mapNotNull { (id, info) -> Fields.classify(info)?.let { it to id } }
            val first = { kind: FieldKind -> kinds.firstOrNull { it.first == kind }?.second }
            return Targets(first(FieldKind.USERNAME), first(FieldKind.PASSWORD), first(FieldKind.OTP))
                .takeIf { it.ids.isNotEmpty() }
        }
    }
}

/** Who asks for credentials, as the platform reported it. */
data class Caller(
    val packageName: String,
    val certificates: List<String>,
    val webDomain: String?,
)

/** Turns a caller into the request the core matches. */
object Requests {
    /**
     * The request for `caller`, or `null` when there is nothing to fill. A
     * web domain counts only when a listed browser reports it; an app's own
     * web views could claim any domain. An app gets the domains of its
     * package name whose statement file names it (`verified`), and its
     * `androidapp://` entries.
     */
    fun build(caller: Caller, browser: Boolean, verified: (String) -> Boolean): FillRequest? {
        if (browser) {
            val domain = caller.webDomain?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return FillRequest(domains = listOf(domain), app = null)
        }
        if (caller.certificates.isEmpty()) return null
        val domains = wtf.metio.pasejo.core.packageDomains(caller.packageName).filter(verified)
        return FillRequest(domains = domains, app = AppRequest(caller.packageName, caller.certificates))
    }
}

/**
 * Remembers which domains let an app receive their credentials for a day,
 * so the statement file is fetched once per app and domain. Failures are
 * not remembered.
 */
class LinkCache(
    private val verify: (domain: String, packageName: String, certificates: List<String>) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = 24 * 60 * 60 * 1000L,
) {
    private data class Key(val domain: String, val packageName: String, val certificates: List<String>)

    private val entries = HashMap<Key, Pair<Boolean, Long>>()

    @Synchronized
    fun verified(domain: String, packageName: String, certificates: List<String>): Boolean {
        val key = Key(domain, packageName, certificates.map(Fingerprints::normalize).sorted())
        entries[key]?.let { (result, at) -> if (now() - at < ttlMillis) return result }
        val result = try {
            verify(domain, packageName, certificates)
        } catch (_: Exception) {
            entries.remove(key)
            return false
        }
        entries[key] = result to now()
        return result
    }
}
