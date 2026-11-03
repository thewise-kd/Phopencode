package io.github.thewisekd.phopencode.core.api_http.message

import io.github.thewisekd.phopencode.core.api.InboxContent
import io.github.thewisekd.phopencode.core.api.InboxItem
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.api_http.base.ApiJson
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.SessionId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `Session.Inbox.Info`: what `POST .../prompt` answers with. Unlike a listed user message, the
 * text sits in `payload`. This is the answer to the app's own request, so a known type with a
 * payload that does not fit is an error, not an unknown item.
 */
@Serializable
internal class InboxItemWire(
    val id: String,
    @SerialName("sessionID") val sessionId: String? = null,
    val time: TimeWire? = null,
    val type: String,
    val payload: JsonObject? = null,
    val delivery: String? = null,
) {
    @Serializable
    private class UserPayloadWire(
        val text: String,
        val files: List<FileAttachmentWire> = emptyList(),
        val agents: List<AgentAttachmentWire> = emptyList(),
        val skills: List<SkillAttachmentWire> = emptyList(),
    )

    @Serializable
    private class SyntheticPayloadWire(val text: String)

    /** @param requested The session asked for, should the server leave its own out. */
    fun toModel(requested: SessionId): InboxItem = InboxItem(
        id = MessageId(id),
        sessionId = sessionId?.let(::SessionId) ?: requested,
        createdAt = instantOf(time?.created),
        content = content(),
        delivery = deliveryOf(delivery),
    )

    private fun content(): InboxContent = when (type) {
        "user" -> ApiJson.decodeFromJsonElement(UserPayloadWire.serializer(), payload ?: JsonObject(emptyMap())).let {
            InboxContent.User(
                text = it.text,
                files = it.files.mapNotNull(FileAttachmentWire::path),
                agents = it.agents.map(AgentAttachmentWire::name),
                skills = it.skills.map(SkillAttachmentWire::id),
            )
        }
        "synthetic" -> InboxContent.Synthetic(
            ApiJson.decodeFromJsonElement(SyntheticPayloadWire.serializer(), payload ?: JsonObject(emptyMap())).text,
        )
        "compaction" -> InboxContent.Compaction
        else -> InboxContent.Unknown(type)
    }
}

internal fun deliveryOf(raw: String?): PromptDelivery? = when (raw) {
    "steer" -> PromptDelivery.Steer
    "queue" -> PromptDelivery.Queue
    else -> null
}

internal fun PromptDelivery.wireName(): String = when (this) {
    PromptDelivery.Steer -> "steer"
    PromptDelivery.Queue -> "queue"
}
