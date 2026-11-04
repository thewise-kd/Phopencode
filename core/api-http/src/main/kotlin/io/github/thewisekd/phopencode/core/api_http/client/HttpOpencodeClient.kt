package io.github.thewisekd.phopencode.core.api_http.client

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.CatalogApi
import io.github.thewisekd.phopencode.core.api.EventApi
import io.github.thewisekd.phopencode.core.api.FsApi
import io.github.thewisekd.phopencode.core.api.InteractionApi
import io.github.thewisekd.phopencode.core.api.MessageApi
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.api.ServerInfo
import io.github.thewisekd.phopencode.core.api.SessionApi
import io.github.thewisekd.phopencode.core.api.VcsApi
import io.github.thewisekd.phopencode.core.api_http.base.ApiRequest
import io.github.thewisekd.phopencode.core.api_http.base.BasicCredentials
import io.github.thewisekd.phopencode.core.api_http.base.HttpLogger
import io.github.thewisekd.phopencode.core.api_http.base.HttpMethod
import io.github.thewisekd.phopencode.core.api_http.base.HttpTransport
import io.github.thewisekd.phopencode.core.api_http.catalog.HttpCatalogApi
import io.github.thewisekd.phopencode.core.api_http.event.decode.toDataValue
import io.github.thewisekd.phopencode.core.api_http.event.stream.ReconnectPolicy
import io.github.thewisekd.phopencode.core.api_http.fs.HttpFsApi
import io.github.thewisekd.phopencode.core.api_http.interaction.HttpInteractionApi
import io.github.thewisekd.phopencode.core.api_http.message.HttpMessageApi
import io.github.thewisekd.phopencode.core.api_http.session.HttpSessionApi
import io.github.thewisekd.phopencode.core.api_http.vcs.HttpVcsApi
import javax.net.SocketFactory
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * [OpencodeClient] for one `opencode serve`, over its V2 HTTP API. Make one with [create].
 *
 * All APIs share one [HttpTransport], so they share its auth, logging and connections, and [close]
 * ends them all: running calls and event flows fail with [ApiError.Closed], and so does every call
 * after it.
 *
 * The version gate is [serverInfo]: it reads `GET /api/info` and fails with
 * [ApiError.VersionTooOld] below [OpencodeClient.MIN_SERVER_VERSION]; a newer major passes with
 * [ServerInfo.isNewerMajor] set, and a version that does not parse passes with
 * [ServerInfo.isVersionUnknown] set. The other calls do not check the
 * version, so a caller asks [serverInfo] first, as the connection manager does. The rules are on
 * [VersionGate].
 *
 * Neither the server address nor the password is ever printed, logged or put in an error.
 */
class HttpOpencodeClient internal constructor(
    private val transport: HttpTransport,
    eventPolicy: ReconnectPolicy = ReconnectPolicy(),
    eventContext: CoroutineContext = Dispatchers.IO,
) : OpencodeClient {
    override val sessions: SessionApi = HttpSessionApi(transport)
    override val messages: MessageApi = HttpMessageApi(transport)
    override val interactions: InteractionApi = HttpInteractionApi(transport)
    override val catalog: CatalogApi = HttpCatalogApi(transport)
    override val vcs: VcsApi = HttpVcsApi(transport)
    override val fs: FsApi = HttpFsApi(transport)
    override val events: EventApi = HttpEventApi(transport, eventPolicy, eventContext)

    /**
     * Reads `GET /api/info`. Only `version` and `capabilities` are read: the reply also names the
     * server's host, URLs, paths and process ID, which are left alone. A reply without a `version`
     * string is [ApiError.MalformedResponse].
     */
    override suspend fun serverInfo(): ServerInfo {
        val reply = transport.receive(ApiRequest(HttpMethod.GET, INFO_PATH), JsonElement.serializer())
        val info = reply as? JsonObject ?: throw ApiException(ApiError.MalformedResponse)
        val version = (info["version"] as? JsonPrimitive)?.takeIf { it.isString }
            ?: throw ApiException(ApiError.MalformedResponse)
        val capabilities = info["capabilities"]?.takeIf { it !is JsonNull }?.let(::toDataValue)
        return VersionGate.check(version.content, capabilities)
    }

    /** Closes the transport, which ends every call and event flow; see [OpencodeClient.close]. */
    override fun close() = transport.close()

    /** No address: the location of a user's server is private. */
    override fun toString(): String = "HttpOpencodeClient(closed=${transport.isClosed})"

    companion object {
        private val INFO_PATH = listOf("api", "info")

        /**
         * A client for the server at [baseUrl], usually `http://127.0.0.1:<port>/` reached through
         * a tunnel's [socketFactory]. A path prefix is kept. Nothing is sent until the first call.
         *
         * Throws [IllegalArgumentException] for an address that is not an `http` or `https` URL, or
         * that carries a user name or password: those go in [credentials]. The message never quotes
         * the address.
         *
         * @param logger Gets one line per exchange, never a header, query, body or host; see [HttpLogger].
         * @param socketFactory Opens every connection of the client: each request and the event
         *   stream. Pass an SSH tunnel's factory, whose sockets are channels to the server: then
         *   `127.0.0.1` in [baseUrl] means the server's loopback, not the phone's, and the password
         *   only travels inside the tunnel. Without one, the client connects to [baseUrl] directly,
         *   so a loopback address would send the password to the phone itself. The client does not
         *   close the factory, nor the tunnel behind it: whoever made it does, after [close].
         */
        fun create(
            baseUrl: String,
            credentials: BasicCredentials,
            logger: HttpLogger = HttpLogger.None,
            socketFactory: SocketFactory? = null,
        ): HttpOpencodeClient {
            val url = requireNotNull(baseUrl.toHttpUrlOrNull()) { "The server address is not an http or https URL." }
            val transport = HttpTransport(url, credentials, logger) {
                if (socketFactory != null) socketFactory(socketFactory)
            }
            return HttpOpencodeClient(transport)
        }
    }
}
