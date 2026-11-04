package io.github.thewisekd.phopencode.core.api

import io.github.thewisekd.phopencode.core.model.DataValue

/**
 * One opencode server, and the entry point to all of its API.
 *
 * Errors: every `suspend` function here and in the APIs below returns its result, or throws
 * [ApiException] with a typed [ApiException.error]. They throw nothing else, apart from
 * `CancellationException`, which must pass through so that cancelling a coroutine still works.
 * Callers therefore catch [ApiException] and switch on [ApiError]. The event stream's rules are on
 * [EventApi.events].
 *
 * Implementations must not put a raw low-level exception or a server reply in a message: see
 * [ApiError] and [ApiException].
 *
 * Closing: [close] releases everything the client holds, so no resources remain afterwards.
 */
interface OpencodeClient : AutoCloseable {
    val sessions: SessionApi
    val messages: MessageApi
    val interactions: InteractionApi
    val catalog: CatalogApi
    val vcs: VcsApi
    val fs: FsApi
    val events: EventApi

    /**
     * Throws [ApiException] with [ApiError.VersionTooOld] below [MIN_SERVER_VERSION]. A version that
     * cannot be read is accepted with [ServerInfo.isVersionUnknown] set.
     */
    suspend fun serverInfo(): ServerInfo

    /**
     * Ends the event stream and releases the client's connections. Calling it again does nothing, and
     * it throws nothing, so it is safe in cleanup code.
     *
     * Afterwards every call on this client and its APIs fails with [ApiException] and
     * [ApiError.Closed], never a raw exception. That covers calls still running, and collectors of
     * [EventApi.events], whose flow fails the same way instead of reconnecting. To talk to the server
     * again, make a new client.
     */
    override fun close()

    companion object {
        /** The oldest opencode this app works with. */
        const val MIN_SERVER_VERSION: String = "2.2"
    }
}

/**
 * What a server says about itself. Its addresses are left out on purpose.
 *
 * @property isNewerMajor The major version is newer than this app knows. It may work, so this is a
 *   warning to show, not an error.
 * @property capabilities In the server's own shape.
 * @property isVersionUnknown The version could not be read, as with `local` from a source build.
 *   Compatibility is not checked then, so the UI shows a warning; [version] still holds the text.
 */
data class ServerInfo(
    val version: String,
    val isNewerMajor: Boolean = false,
    val capabilities: DataValue? = null,
    val isVersionUnknown: Boolean = false,
)
