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
 * @property serverVersion What the server reports. [serverInfo] checks it like a real client.
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
        val found = serverVersion.numbers() ?: throw ApiException(ApiError.MalformedResponse)
        val required = OpencodeClient.MIN_SERVER_VERSION.numbers().orEmpty()
        if (found.isOlderThan(required)) throw ApiException(ApiError.VersionTooOld(serverVersion))
        ServerInfo(serverVersion, found.first() > required.first(), capabilities)
    }

    override fun close() = control.close()

    /** `2.2.5` as numbers, ignoring a suffix such as `-beta`. Null if it is not a version. */
    private fun String.numbers(): List<Int>? =
        substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }

    private fun List<Int>.isOlderThan(other: List<Int>): Boolean {
        zip(other).forEach { (mine, theirs) ->
            if (mine != theirs) return mine < theirs
        }
        return size < other.size
    }
}
