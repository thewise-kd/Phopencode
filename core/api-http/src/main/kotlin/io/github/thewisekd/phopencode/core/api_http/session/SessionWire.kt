package io.github.thewisekd.phopencode.core.api_http.session

import io.github.thewisekd.phopencode.core.api_http.base.LocationWire
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The server's session shapes, as recorded (`docs/api/README.md`) and specified in `openapi.json`.
 * Only what the model needs is declared; everything else, `permissions` included, is dropped by
 * `ApiJson`. Numbers that must stay exact, such as costs and token counts, are kept as their JSON
 * text and converted in SessionMapping.kt. No type here may declare `settings`, `headers` or an
 * API key.
 */

/** `Session.Info`. Only `id` is required here, so that a missing optional part costs no session. */
@Serializable
internal class SessionWire(
    val id: String,
    @SerialName("parentID") val parentId: String? = null,
    @SerialName("projectID") val projectId: String? = null,
    val title: String? = null,
    val subpath: String? = null,
    val agent: String? = null,
    val model: ModelRefWire? = null,
    val location: LocationWire? = null,
    val time: SessionTimeWire? = null,
    val outcome: String? = null,
    val fork: ForkWire? = null,
    val revert: RevertWire? = null,
    val tokens: TokensWire? = null,
    val cost: JsonPrimitive? = null,
    val metadata: JsonObject? = null,
)

/** Milliseconds since the epoch; `archived` is there only while the session is archived. */
@Serializable
internal class SessionTimeWire(
    val created: JsonPrimitive? = null,
    val updated: JsonPrimitive? = null,
    val archived: JsonPrimitive? = null,
)

/** `Model.Ref`, both ways: `id` is the model's ID within its provider. */
@Serializable
internal class ModelRefWire(
    val id: String,
    @SerialName("providerID") val providerId: String,
    val variant: String? = null,
)

@Serializable
internal class ForkWire(
    @SerialName("sessionID") val sessionId: String,
    val boundary: ForkBoundaryWire,
)

@Serializable
internal class ForkBoundaryWire(
    val type: String,
    @SerialName("messageID") val messageId: String,
)

@Serializable
internal class RevertWire(
    @SerialName("messageID") val messageId: String,
    val snapshot: String? = null,
    val files: List<FileDiffWire> = emptyList(),
)

/** `FileDiff.Info`. */
@Serializable
internal class FileDiffWire(
    val file: String,
    val status: String,
    val patch: String? = null,
    val additions: Long? = null,
    val deletions: Long? = null,
)

/** `TokenUsage.Info`: counts that may exceed a `Long`, so kept as text. */
@Serializable
internal class TokensWire(
    val input: JsonPrimitive? = null,
    val output: JsonPrimitive? = null,
    val reasoning: JsonPrimitive? = null,
    val cache: CacheWire? = null,
)

@Serializable
internal class CacheWire(
    val read: JsonPrimitive? = null,
    val write: JsonPrimitive? = null,
)

/** `Session.Inbox.*` as `compact` returns it. The payload is not needed for a compaction. */
@Serializable
internal class InboxItemWire(
    val id: String,
    @SerialName("sessionID") val sessionId: String,
    val type: String,
    val time: InboxTimeWire? = null,
    val delivery: String? = null,
)

@Serializable
internal class InboxTimeWire(val created: JsonPrimitive? = null)

/** An entry of `/api/session/active`. */
@Serializable
internal class ActiveWire(val type: String)

/** The interrupt reply, which has no `data` wrapper. */
@Serializable
internal class InterruptWire(val interrupted: Boolean)

/** Every wire type of this package, for the test that no secret-bearing field is declared. */
internal object SessionWireTypes {
    val all: List<SerialDescriptor> = listOf(
        SessionWire.serializer().descriptor,
        InboxItemWire.serializer().descriptor,
        ActiveWire.serializer().descriptor,
        InterruptWire.serializer().descriptor,
    )
}
