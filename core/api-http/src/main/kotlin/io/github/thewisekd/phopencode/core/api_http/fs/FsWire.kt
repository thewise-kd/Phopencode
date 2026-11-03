package io.github.thewisekd.phopencode.core.api_http.fs

import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import kotlinx.serialization.Serializable

/**
 * `FileSystem.Entry`: `{path, type}`, the path relative to the location. The server ends a directory's
 * path with `/`; the model holds it without, as SFTP gives paths, so both sources agree. The API gives
 * neither size, time nor the ignored flag.
 */
@Serializable
internal class FsEntryWire(val path: String, val type: String) {
    fun toModel(): FileEntry = FileEntry(
        path = if (path.length > 1 && path.endsWith('/')) path.dropLast(1) else path,
        type = when (type) {
            "file" -> FileEntryType.File
            "directory" -> FileEntryType.Directory
            else -> FileEntryType.Unknown(type)
        },
    )
}
