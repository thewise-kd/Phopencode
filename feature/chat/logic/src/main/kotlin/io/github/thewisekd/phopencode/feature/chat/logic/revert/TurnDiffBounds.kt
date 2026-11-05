package io.github.thewisekd.phopencode.feature.chat.logic.revert

import io.github.thewisekd.phopencode.core.model.FileDiff

/**
 * How much of a turn's diff the revert page holds, so one huge turn cannot fill the phone's memory.
 *
 * - At most [MAX_FILES] files, the first ones in the server's order; the rest are left out.
 * - A patch longer than [MAX_PATCH_CHARS] is dropped, and so is every patch that would take the
 *   patches held past [MAX_TOTAL_PATCH_CHARS]. A dropped patch leaves the file with its status and
 *   line counts and a null patch, as the server gives for a binary file.
 *
 * Nothing is cut in the middle: a patch is held whole or not at all, so what shows is never a
 * partial change that looks complete.
 */
internal object TurnDiffBounds {
    const val MAX_FILES = 1_000

    const val MAX_PATCH_CHARS = 64 * 1024

    const val MAX_TOTAL_PATCH_CHARS = 1024 * 1024

    fun bound(diffs: List<FileDiff>): List<FileDiff> {
        var used = 0
        return diffs.take(MAX_FILES).map { diff ->
            val length = diff.patch?.length ?: return@map diff
            if (length <= MAX_PATCH_CHARS && used + length <= MAX_TOTAL_PATCH_CHARS) {
                used += length
                diff
            } else {
                diff.copy(patch = null)
            }
        }
    }
}
