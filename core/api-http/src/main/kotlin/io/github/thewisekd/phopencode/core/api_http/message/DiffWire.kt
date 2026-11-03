package io.github.thewisekd.phopencode.core.api_http.message

import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.SessionRevert
import io.github.thewisekd.phopencode.core.model.VcsFile
import io.github.thewisekd.phopencode.core.model.VcsFileStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `FileDiff.Info`: an item of a turn diff and of a revert's `files`. */
@Serializable
internal class FileDiffWire(
    val file: String,
    val patch: String? = null,
    val additions: Long? = null,
    val deletions: Long? = null,
    val status: String,
) {
    fun toModel(): FileDiff = FileDiff(
        file = VcsFile(path = file, status = statusOf(status), additions = additions, deletions = deletions),
        patch = patch,
    )
}

/** The spec has `added`, `deleted` and `modified`; the rest are kept open. */
private fun statusOf(raw: String): VcsFileStatus = when (raw) {
    "added" -> VcsFileStatus.Added
    "modified" -> VcsFileStatus.Modified
    "deleted" -> VcsFileStatus.Deleted
    "renamed" -> VcsFileStatus.Renamed
    "untracked" -> VcsFileStatus.Untracked
    else -> VcsFileStatus.Unknown(raw)
}

/** `Session.Revert`: what `revert/stage` answers with, and what a session carries as `revert`. */
@Serializable
internal class RevertWire(
    @SerialName("messageID") val messageId: String,
    val snapshot: String? = null,
    val files: List<FileDiffWire> = emptyList(),
) {
    fun toModel(): SessionRevert = SessionRevert(
        messageId = MessageId(messageId),
        snapshot = snapshot,
        files = files.map(FileDiffWire::toModel),
    )
}
