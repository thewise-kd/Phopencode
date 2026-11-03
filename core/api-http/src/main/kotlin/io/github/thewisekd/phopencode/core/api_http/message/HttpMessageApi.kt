package io.github.thewisekd.phopencode.core.api_http.message

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.InboxItem
import io.github.thewisekd.phopencode.core.api.MessageApi
import io.github.thewisekd.phopencode.core.api.MessageQuery
import io.github.thewisekd.phopencode.core.api.Page
import io.github.thewisekd.phopencode.core.api.Prompt
import io.github.thewisekd.phopencode.core.api.SortOrder
import io.github.thewisekd.phopencode.core.api_http.base.ApiJson
import io.github.thewisekd.phopencode.core.api_http.base.ApiRequest
import io.github.thewisekd.phopencode.core.api_http.base.DataEnvelope
import io.github.thewisekd.phopencode.core.api_http.base.HttpMethod
import io.github.thewisekd.phopencode.core.api_http.base.HttpTransport
import io.github.thewisekd.phopencode.core.api_http.base.PageEnvelope
import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.SessionRevert
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject

/**
 * [MessageApi] over `opencode serve`'s `/api/session/{id}/...` routes. These routes take no
 * location: the session knows its folder.
 *
 * Which calls may be sent twice. OkHttp resends a [ApiRequest.repeatable] request on its own after a
 * dropped connection, so only calls whose repeat does no harm are marked so:
 *
 * | Call | Route | Repeatable | Why |
 * |---|---|---|---|
 * | [prompt] | `POST prompt` | no | A repeat is a second prompt and a second turn |
 * | [command] | `POST command` | no | Starts a turn, as a prompt does |
 * | [stageRevert] | `POST revert/stage` | no | Takes a snapshot; a repeat could stage against a changed tree |
 * | [commitRevert] | `POST revert/commit` | no | Deletes history; a repeat acts on what is left |
 * | [cancelRevert] | `DELETE revert` | no | Runs a short execution on the server |
 * | [switchAgent], [switchModel] | `POST agent`, `POST model` | yes | Sets a value; see below |
 * | [list], [turnDiff] | `GET` | yes | Reads only |
 *
 * A repeated switch sets the same value again. At worst the history gets a second switch marker,
 * which is cheaper than failing a switch on every stale pooled connection.
 *
 * A call that is not repeatable fails with [ApiError.Network] when its connection drops, even if
 * the server got it: the caller decides whether to send it again.
 *
 * Nothing here logs: the transport's log line has the method and path only, never a prompt.
 */
internal class HttpMessageApi(private val transport: HttpTransport) : MessageApi {
    override suspend fun prompt(sessionId: SessionId, prompt: Prompt): InboxItem {
        val body = PromptBody(
            text = prompt.text,
            files = prompt.files.map(PromptBody::FileInputWire).ifEmpty { null },
            agents = prompt.agents.map(::AgentAttachmentWire).ifEmpty { null },
            // The server takes a skill's `id`, the name `CatalogApi` lists as `Skill.id`.
            skills = prompt.skills.map(::SkillAttachmentWire).ifEmpty { null },
            delivery = prompt.delivery?.wireName(),
            resume = prompt.resume,
        )
        val request = post(sessionId, listOf("prompt"), PromptBody.serializer(), body, repeatable = false)
        val envelope = transport.receive(request, DataEnvelope.serializer(InboxItemWire.serializer()))
        return mapped { envelope.data.toModel(sessionId) }
    }

    /**
     * The order goes out only for a first page: a cursor already carries it, and the server asks
     * not to get both. The type goes out on every page, as the server asks.
     */
    override suspend fun list(sessionId: SessionId, query: MessageQuery): Page<Message> {
        val parameters = buildList {
            query.limit?.let { add("limit" to it.toString()) }
            if (query.cursor == null) {
                query.order?.let { add("order" to if (it == SortOrder.Ascending) "asc" else "desc") }
            }
            query.type?.let { add("type" to it) }
            query.cursor?.let { add("cursor" to it.value) }
        }
        val request = ApiRequest(HttpMethod.GET, path(sessionId, "message"), query = parameters)
        val envelope = transport.receive(request, PageEnvelope.serializer(JsonObject.serializer()))
        return mapped { envelope.toPage { it.toMessage(sessionId) } }
    }

    override suspend fun command(sessionId: SessionId, name: String, text: String) {
        transport.execute(
            post(sessionId, listOf("command"), CommandBody.serializer(), CommandBody(name, text), repeatable = false),
        )
    }

    override suspend fun switchAgent(sessionId: SessionId, agent: String) {
        transport.execute(post(sessionId, listOf("agent"), AgentBody.serializer(), AgentBody(agent), repeatable = true))
    }

    override suspend fun switchModel(sessionId: SessionId, model: ModelRef) {
        transport.execute(
            post(sessionId, listOf("model"), ModelBody.serializer(), ModelBody(model.toWire()), repeatable = true),
        )
    }

    override suspend fun turnDiff(
        sessionId: SessionId,
        from: MessageId?,
        to: MessageId?,
        context: Int?,
    ): List<FileDiff> {
        val parameters = buildList {
            from?.let { add("from" to it.value) }
            to?.let { add("to" to it.value) }
            context?.let { add("context" to it.toString()) }
        }
        val request = ApiRequest(HttpMethod.GET, path(sessionId, "diff"), query = parameters)
        val envelope = transport.receive(request, DataEnvelope.serializer(ListSerializer(FileDiffWire.serializer())))
        return envelope.data.map(FileDiffWire::toModel)
    }

    override suspend fun stageRevert(sessionId: SessionId, messageId: MessageId, files: Boolean?): SessionRevert {
        val body = RevertStageBody(messageId.value, files)
        val request = post(sessionId, listOf("revert", "stage"), RevertStageBody.serializer(), body, repeatable = false)
        return transport.receive(request, DataEnvelope.serializer(RevertWire.serializer())).data.toModel()
    }

    override suspend fun commitRevert(sessionId: SessionId) {
        transport.execute(
            ApiRequest(HttpMethod.POST, path(sessionId, "revert", "commit"), repeatable = false),
        )
    }

    override suspend fun cancelRevert(sessionId: SessionId) {
        transport.execute(ApiRequest(HttpMethod.DELETE, path(sessionId, "revert"), repeatable = false))
    }

    private fun path(sessionId: SessionId, vararg route: String): List<String> =
        listOf("api", "session", sessionId.value) + route

    private fun <T> post(
        sessionId: SessionId,
        route: List<String>,
        serializer: SerializationStrategy<T>,
        body: T,
        repeatable: Boolean,
    ): ApiRequest = ApiRequest(
        method = HttpMethod.POST,
        path = path(sessionId, *route.toTypedArray()),
        body = ApiJson.encodeToJsonElement(serializer, body),
        repeatable = repeatable,
    )

    /** Mapping that reads past what the transport decoded; a misfit is the server's malformed answer. */
    private inline fun <T> mapped(block: () -> T): T = try {
        block()
    } catch (_: SerializationException) {
        throw ApiException(ApiError.MalformedResponse)
    } catch (_: IllegalArgumentException) {
        throw ApiException(ApiError.MalformedResponse)
    }

    override fun toString(): String = "HttpMessageApi"
}
