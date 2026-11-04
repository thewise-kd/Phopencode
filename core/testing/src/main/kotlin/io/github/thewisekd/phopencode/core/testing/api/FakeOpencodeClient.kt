package io.github.thewisekd.phopencode.core.testing.api

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.api.ServerInfo
import io.github.thewisekd.phopencode.core.model.DataValue

/**
 * A client whose APIs share one server state and one [control], so that a call through one shows up
 * through the others, and [close] ends them all.
 *
 * @property serverVersion What the server reports. [serverInfo] checks it like a real client: below
 *   the minimum fails, and one it cannot read passes with [ServerInfo.isVersionUnknown] set.
 * @property capabilities What the server reports next to the version.
 */
class FakeOpencodeClient(
    @Volatile var serverVersion: String = "2.2.5",
    @Volatile var capabilities: DataValue? = null,
    val control: FakeApiControl = FakeApiControl(),
) : OpencodeClient {
    override val events: FakeEventApi = FakeEventApi(control)
    override val sessions: FakeSessionApi = FakeSessionApi(control, events)
    override val messages: FakeMessageApi = FakeMessageApi(control, sessions)
    override val interactions: FakeInteractionApi = FakeInteractionApi(control, sessions)
    override val catalog: FakeCatalogApi = FakeCatalogApi(control)
    override val vcs: FakeVcsApi = FakeVcsApi(control)
    override val fs: FakeFsApi = FakeFsApi(control)

    override suspend fun serverInfo(): ServerInfo = control.call("serverInfo") {
        val raw = serverVersion
        val found = raw.numbers()
            ?: return@call ServerInfo(
                version = raw.trim().take(MAX_UNKNOWN_LENGTH),
                isNewerMajor = false,
                capabilities = capabilities,
                isVersionUnknown = true,
            )
        val required = checkNotNull(OpencodeClient.MIN_SERVER_VERSION.numbers())
        if (found.isOlderThan(required)) throw ApiException(ApiError.VersionTooOld(found.text()))
        ServerInfo(raw.trim(), found.first() > required.first(), capabilities)
    }

    override fun close() = control.close()

    private companion object {
        /** The real client's version pattern: `v`, `major.minor[.patch]`, `-pre.release`, `+build`. */
        val PATTERN = Regex(
            """v?([0-9]{1,9})\.([0-9]{1,9})(?:\.([0-9]{1,9}))?(?:-[0-9A-Za-z.-]{1,64})?(?:\+[0-9A-Za-z.-]{1,64})?""",
        )

        /** As in the real client: a longer text, whitespace included, is not read as a version. */
        const val MAX_LENGTH = 200

        /** As in the real client: the most of an unreadable version's text that is kept. */
        const val MAX_UNKNOWN_LENGTH = 200
    }

    /**
     * `v2.2.5-beta` as `[2, 2, 5]`, read exactly as the real client reads it: whitespace around it is
     * ignored and a suffix does not count. Null if the real client would not read it as a version.
     */
    private fun String.numbers(): List<Int>? {
        if (length > MAX_LENGTH) return null
        val match = PATTERN.matchEntire(trim()) ?: return null
        val (major, minor, patch) = match.destructured
        return listOfNotNull(major.toInt(), minor.toInt(), patch.takeIf { it.isNotEmpty() }?.toInt())
    }

    /** The numbers alone, as the real client names a version that is too old. */
    private fun List<Int>.text(): String = joinToString(".")

    /** Compared as the real client compares: a missing patch counts as 0. */
    private fun List<Int>.isOlderThan(other: List<Int>): Boolean {
        val mine = this + List(maxOf(0, 3 - size)) { 0 }
        val theirs = other + List(maxOf(0, 3 - other.size)) { 0 }
        mine.zip(theirs).forEach { (a, b) ->
            if (a != b) return a < b
        }
        return false
    }
}
