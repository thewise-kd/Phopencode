package io.github.thewisekd.phopencode.core.remote.detect

import io.github.thewisekd.phopencode.core.api.OpencodeClient

/**
 * An opencode version as `opencode --version` prints it: `major.minor` or `major.minor.patch`,
 * optionally with a leading `v`, a `-pre.release` and a `+build`, which is dropped. Each number has
 * at most 9 digits; a suffix is at most 64 letters, digits, dots and hyphens.
 *
 * Only the numbers count when comparing, and a missing patch counts as 0, the same rule the API
 * client applies to `/api/info`.
 */
data class OpencodeVersion(val major: Int, val minor: Int, val patch: Int?, val preRelease: String?) :
    Comparable<OpencodeVersion> {
    init {
        require(major >= 0 && minor >= 0 && (patch == null || patch >= 0)) { "Version numbers must not be negative" }
    }

    /** The numbers alone, without `v` or suffix. */
    val numbers: String
        get() = if (patch == null) "$major.$minor" else "$major.$minor.$patch"

    /** Whether this app supports a server of this version, see [OpencodeClient.MIN_SERVER_VERSION]. */
    val meetsMinimum: Boolean
        get() = this >= MINIMUM

    override fun compareTo(other: OpencodeVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch ?: 0 })

    override fun toString(): String = if (preRelease == null) numbers else "$numbers-$preRelease"

    companion object {
        private val PATTERN = Regex(
            """v?([0-9]{1,9})\.([0-9]{1,9})(?:\.([0-9]{1,9}))?(?:-([0-9A-Za-z.-]{1,64}))?(?:\+[0-9A-Za-z.-]{1,64})?""",
        )
        private val ANSI_ESCAPE = Regex("""\u001B\[[0-9;?]*[A-Za-z]""")
        private val SEPARATORS = Regex("""[\s(),;:"'\[\]]+""")

        /** Only the start of the output is read, so a flood of text costs nothing. */
        private const val MAX_SCAN_CHARS = 4096

        private val MINIMUM: OpencodeVersion = checkNotNull(parse(OpencodeClient.MIN_SERVER_VERSION))

        /** Null unless all of [text] is one version as described on [OpencodeVersion]. */
        fun parse(text: String): OpencodeVersion? {
            if (text.length > MAX_TOKEN_CHARS) return null
            val match = PATTERN.matchEntire(text) ?: return null
            val (major, minor, patch, preRelease) = match.destructured
            return OpencodeVersion(
                major.toInt(),
                minor.toInt(),
                patch.takeIf { it.isNotEmpty() }?.toInt(),
                preRelease.takeIf { it.isNotEmpty() },
            )
        }

        /**
         * The first word of a command's output that is a version, or null. Colour codes and
         * surrounding punctuation are ignored, so `opencode v2.2.5.` reads as 2.2.5; anything with
         * more parts, such as an address, is not a version.
         */
        fun find(output: String): OpencodeVersion? =
            ANSI_ESCAPE.replace(output.take(MAX_SCAN_CHARS), " ")
                .split(SEPARATORS)
                .asSequence()
                .map { it.trimEnd('.', ',') }
                .filter { it.isNotEmpty() }
                .firstNotNullOfOrNull(::parse)

        private const val MAX_TOKEN_CHARS = 200
    }
}
