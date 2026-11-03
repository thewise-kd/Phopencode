package io.github.thewisekd.phopencode.core.api_http.vcs

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.VcsFile
import io.github.thewisekd.phopencode.core.model.VcsFileStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/*
 * The VCS replies, as recorded from opencode 2.2.5. A value that cannot be what the server promised
 * fails the call with ApiError.MalformedResponse; a value that is merely new, such as an unknown file
 * status, is kept raw in the model's Unknown case.
 */

/** `Vcs.Info`: `{provider, branch: {current, default}}`; with no repository `{branch: {}}`. */
@Serializable
internal class VcsInfoWire(val branch: VcsBranchWire = VcsBranchWire())

/** `Vcs.Branch`. `default` is read nowhere: the default branch comes from `/api/vcs/base`. */
@Serializable
internal class VcsBranchWire(val current: String? = null)

/** `Vcs.Base`: `{name, ref, source}`. Only the name is kept. */
@Serializable
internal class VcsBaseWire(val name: String)

/** `Vcs.FileStatus`. */
@Serializable
internal class VcsFileStatusWire(
    val file: String,
    val additions: JsonPrimitive? = null,
    val deletions: JsonPrimitive? = null,
    val status: String,
) {
    fun toModel(): VcsFile = vcsFile(file, status, additions, deletions)
}

/**
 * `FileDiff.Info`. The spec makes `patch` required; it is optional here so that a file the server
 * gives no patch for still decodes. `more`, which marks an entry that stands for files the server
 * did not list in full, has no place in the model and is dropped: it only comes with
 * `untracked=folders`, past the server's 500-entry cap.
 */
@Serializable
internal class VcsFileDiffWire(
    val file: String,
    val patch: String? = null,
    val additions: JsonPrimitive? = null,
    val deletions: JsonPrimitive? = null,
    val status: String,
) {
    fun toModel(): FileDiff = FileDiff(vcsFile(file, status, additions, deletions), patch)
}

private fun vcsFile(file: String, status: String, additions: JsonPrimitive?, deletions: JsonPrimitive?) =
    VcsFile(path = file, status = statusOf(status), additions = additions.toCount(), deletions = deletions.toCount())

/** The spec has `added`, `deleted` and `modified`; the rest are kept open. */
private fun statusOf(raw: String): VcsFileStatus = when (raw) {
    "added" -> VcsFileStatus.Added
    "modified" -> VcsFileStatus.Modified
    "deleted" -> VcsFileStatus.Deleted
    "renamed" -> VcsFileStatus.Renamed
    "untracked" -> VcsFileStatus.Untracked
    else -> VcsFileStatus.Unknown(raw)
}

/** The spec's largest count: JavaScript's largest safe integer. */
private const val MAX_COUNT = 9_007_199_254_740_991L
private const val MAX_COUNT_DIGITS = 16

/**
 * A line count: plain digits, at most [MAX_COUNT]. Absent or null is null. The text is checked before
 * it is parsed, so no input, however long or however large its exponent, costs more than a glance; no
 * BigDecimal is involved.
 */
private fun JsonPrimitive?.toCount(): Long? {
    if (this == null || this is JsonNull) return null
    val text = content
    if (isString || text.isEmpty() || text.length > MAX_COUNT_DIGITS || !text.all { it in '0'..'9' }) malformed()
    return text.toLong().takeIf { it <= MAX_COUNT } ?: malformed()
}

private fun malformed(): Nothing = throw ApiException(ApiError.MalformedResponse)
