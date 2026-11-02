package io.github.thewisekd.phopencode.core.model

import kotlin.time.Instant

/**
 * A file or folder on the server. [sizeBytes] and [modifiedAt] are null when the source does not
 * give them: the API lists paths and types only, SFTP gives both.
 *
 * @property ignored Whether `.gitignore` covers it.
 */
data class FileEntry(
    val path: String,
    val type: FileEntryType,
    val sizeBytes: Long? = null,
    val modifiedAt: Instant? = null,
    val ignored: Boolean = false,
) {
    /** The last path segment. The root has none, so it is its own name. */
    val name: String
        get() {
            val trimmed = path.trimEnd('/')
            return if (trimmed.isEmpty()) path else trimmed.substringAfterLast('/')
        }
}

sealed interface FileEntryType {
    data object File : FileEntryType

    data object Directory : FileEntryType

    data object Symlink : FileEntryType

    data class Unknown(val raw: String) : FileEntryType
}
