// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

/**
 * The managed configuration a device policy controller set for the app
 * (`res/xml/app_restrictions.xml`). Absent keys leave the choice to the
 * person.
 */
data class ManagedConfig(
    val serverUrl: String? = null,
    val organization: String? = null,
    val genesisHash: String? = null,
    val checkpointKey: String? = null,
    val maxUnlockSeconds: Int? = null,
    val allowCopy: Boolean = true,
) {
    /** The unlock period for a requested one, capped by the policy. */
    fun unlockSeconds(requested: Int = DEFAULT_UNLOCK_SECONDS): Int {
        val wanted = requested.coerceIn(MIN_UNLOCK_SECONDS, MAX_UNLOCK_SECONDS)
        return maxUnlockSeconds?.let { minOf(wanted, it.coerceAtLeast(MIN_UNLOCK_SECONDS)) } ?: wanted
    }

    companion object {
        const val DEFAULT_UNLOCK_SECONDS = 300
        const val MIN_UNLOCK_SECONDS = 10
        const val MAX_UNLOCK_SECONDS = 3600

        /** Reads the restrictions through `get`, ignoring values of the wrong type. */
        fun parse(get: (String) -> Any?): ManagedConfig {
            fun text(key: String) = (get(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }
            return ManagedConfig(
                serverUrl = text("server_url"),
                organization = text("organization"),
                genesisHash = text("genesis_hash"),
                checkpointKey = text("checkpoint_key"),
                maxUnlockSeconds = (get("max_unlock_seconds") as? Int)?.takeIf { it > 0 },
                allowCopy = get("allow_copy") as? Boolean ?: true,
            )
        }
    }
}
