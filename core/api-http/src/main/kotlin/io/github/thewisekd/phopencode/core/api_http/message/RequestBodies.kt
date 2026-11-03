package io.github.thewisekd.phopencode.core.api_http.message

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Request bodies, with fields in the spec's order. Null fields are left out (see ApiJson), so a
 * prompt with nothing attached sends `{"text": ...}` alone, as the recordings do.
 */

/** `POST .../prompt`. Empty lists are sent as null, so left out. */
@Serializable
internal class PromptBody(
    val text: String,
    val files: List<FileInputWire>? = null,
    val agents: List<AgentAttachmentWire>? = null,
    val skills: List<SkillAttachmentWire>? = null,
    val delivery: String? = null,
    val resume: Boolean? = null,
) {
    /** `PromptInput.FileAttachment`: the server resolves [uri] and answers with the file's data. */
    @Serializable
    class FileInputWire(val uri: String)
}

/** `POST .../command`. The server requires `text`, empty or not. */
@Serializable
internal class CommandBody(val name: String, val text: String)

/** `POST .../agent`. */
@Serializable
internal class AgentBody(val agent: String)

/** `POST .../model`. */
@Serializable
internal class ModelBody(val model: ModelRefWire)

/** `POST .../revert/stage`. */
@Serializable
internal class RevertStageBody(@SerialName("messageID") val messageId: String, val files: Boolean? = null)
