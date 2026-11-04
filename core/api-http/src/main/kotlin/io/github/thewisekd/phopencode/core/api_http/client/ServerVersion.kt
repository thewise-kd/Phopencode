package io.github.thewisekd.phopencode.core.api_http.client

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.api.ServerInfo
import io.github.thewisekd.phopencode.core.model.DataValue

/**
 * An opencode version, read from `/api/info`: `major.minor` or `major.minor.patch`, optionally with
 * a leading `v`, a `-pre.release` and a `+build`. Each number has at most 9 digits; a suffix is at
 * most 64 characters of letters, digits, dots and hyphens.
 *
 * Only the numbers count when comparing, and a missing patch counts as 0: a pre-release of a version
 * compares equal to that version, so `2.2.0-beta.1` passes a 2.2 minimum.
 */
internal data class ServerVersion(val major: Int, val minor: Int, val patch: Int?) : Comparable<ServerVersion> {
    /** The numbers alone, without leading zeros, `v` or suffix; safe to show and to log. */
    val numbers: String
        get() = if (patch == null) "$major.$minor" else "$major.$minor.$patch"

    override fun compareTo(other: ServerVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch ?: 0 })

    companion object {
        private val PATTERN = Regex(
            """v?([0-9]{1,9})\.([0-9]{1,9})(?:\.([0-9]{1,9}))?(?:-[0-9A-Za-z.-]{1,64})?(?:\+[0-9A-Za-z.-]{1,64})?""",
        )

        /** Null for anything that is not a version as described on [ServerVersion]. Whitespace around it is ignored. */
        fun parse(text: String): ServerVersion? {
            // Bounded before the regex runs, so a huge reply costs nothing.
            if (text.length > MAX_LENGTH) return null
            val match = PATTERN.matchEntire(text.trim()) ?: return null
            val (major, minor, patch) = match.destructured
            return ServerVersion(major.toInt(), minor.toInt(), patch.takeIf { it.isNotEmpty() }?.toInt())
        }

        private const val MAX_LENGTH = 200
    }
}

/**
 * Decides whether this app works with a server, from the version it reports.
 *
 * - Below [OpencodeClient.MIN_SERVER_VERSION]: [ApiError.VersionTooOld] with the version's numbers.
 * - A major above the minimum's: accepted, with [ServerInfo.isNewerMajor] set, as a warning to show.
 * - A version that does not parse (see [ServerVersion]), such as `local` from a source build:
 *   accepted, with [ServerInfo.isVersionUnknown] set, as a warning to show. Compatibility is not
 *   checked. Its trimmed text, cut to [MAX_UNKNOWN_LENGTH] characters, goes only into
 *   [ServerInfo.version], never into an error or a log.
 */
internal object VersionGate {
    /** The most of an unparseable version's text that is kept in [ServerInfo.version]. */
    const val MAX_UNKNOWN_LENGTH: Int = 200

    private val MINIMUM: ServerVersion = checkNotNull(ServerVersion.parse(OpencodeClient.MIN_SERVER_VERSION))

    /** The [ServerInfo] for [raw], or throws [ApiException]; see [VersionGate]. */
    fun check(raw: String, capabilities: DataValue?): ServerInfo {
        val version = ServerVersion.parse(raw) ?: return ServerInfo(
            version = raw.trim().take(MAX_UNKNOWN_LENGTH),
            isNewerMajor = false,
            capabilities = capabilities,
            isVersionUnknown = true,
        )
        if (version < MINIMUM) throw ApiException(ApiError.VersionTooOld(version.numbers))
        return ServerInfo(
            version = raw.trim(),
            isNewerMajor = version.major > MINIMUM.major,
            capabilities = capabilities,
        )
    }
}
