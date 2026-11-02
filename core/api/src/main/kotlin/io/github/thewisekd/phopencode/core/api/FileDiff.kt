package io.github.thewisekd.phopencode.core.api

import io.github.thewisekd.phopencode.core.model.VcsFile

/**
 * The change to one file, for a turn or for the working tree.
 *
 * @property patch A unified diff. Null when the server gave none, as for a binary file.
 */
data class FileDiff(
    val file: VcsFile,
    val patch: String? = null,
)
