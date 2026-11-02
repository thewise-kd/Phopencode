package io.github.thewisekd.phopencode.core.storage_api

/**
 * Where a host key was seen. Host names are case-insensitive, so the host is lower-cased: otherwise
 * `Server` and `server` would be two entries, and a changed key could pass as a first connection
 * under the other spelling. Printing leaves out the address, as `ServerProfile` does.
 */
class HostEndpoint(host: String, val port: Int) {
    val host: String = host.lowercase()

    override fun equals(other: Any?): Boolean = other is HostEndpoint && host == other.host && port == other.port

    override fun hashCode(): Int = 31 * host.hashCode() + port

    override fun toString(): String = "HostEndpoint(***)"
}

/**
 * The SHA-256 fingerprint of a host key in OpenSSH form, `SHA256:<base64>`. Compared exactly:
 * base64 is case-sensitive, and a mismatch stops the connection rather than letting one through.
 * A fingerprint is not secret, but public scanners index them, so one in a log can identify the
 * server. Printing is masked; screens read [sha256].
 */
@JvmInline
value class HostKeyFingerprint(val sha256: String) {
    override fun toString(): String = "HostKeyFingerprint(***)"
}

sealed interface HostKeyCheck {
    /** No key is approved for this endpoint yet: ask the user. */
    data object Unknown : HostKeyCheck

    /** The presented key is the approved one. */
    data object Trusted : HostKeyCheck

    /** The presented key differs from [approved]: stop the connection. */
    data class Changed(val approved: HostKeyFingerprint) : HostKeyCheck
}

sealed interface HostKeyUpdate {
    data object Stored : HostKeyUpdate

    /** Nothing changed. [current] is the approved key found, `null` if there is none. */
    data class Rejected(val current: HostKeyFingerprint?) : HostKeyUpdate
}

/**
 * The host keys the user approved, one per endpoint. No function here overwrites an approved key
 * unless the caller names that key, so a changed key can never replace it by accident. Each
 * read-and-write below is atomic.
 */
interface KnownHostsStore {
    /** The approved key for [endpoint], or `null` if there is none. */
    suspend fun approved(endpoint: HostEndpoint): HostKeyFingerprint?

    /** Compares a key the server presented with the approved one. */
    suspend fun check(endpoint: HostEndpoint, presented: HostKeyFingerprint): HostKeyCheck =
        when (val current = approved(endpoint)) {
            null -> HostKeyCheck.Unknown
            presented -> HostKeyCheck.Trusted
            else -> HostKeyCheck.Changed(current)
        }

    /**
     * First approval of [endpoint]. Gives [HostKeyUpdate.Stored] if nothing was approved or
     * [fingerprint] already is, and [HostKeyUpdate.Rejected] if a different key is approved.
     */
    suspend fun approve(endpoint: HostEndpoint, fingerprint: HostKeyFingerprint): HostKeyUpdate

    /**
     * The user's explicit decision to accept a changed key. Stores [replacement] only if [expected]
     * is still the approved key; otherwise gives [HostKeyUpdate.Rejected] and changes nothing, so a
     * decision about one key cannot apply to another.
     */
    suspend fun replace(
        endpoint: HostEndpoint,
        expected: HostKeyFingerprint,
        replacement: HostKeyFingerprint,
    ): HostKeyUpdate

    /** Removes the approved key for [endpoint], if any. */
    suspend fun delete(endpoint: HostEndpoint)
}
