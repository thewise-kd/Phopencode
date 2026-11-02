package io.github.thewisekd.phopencode.core.api

import io.github.thewisekd.phopencode.core.model.FileEntry

/**
 * File name search. Errors are described on [OpencodeClient].
 *
 * Listing, reading and writing go over SFTP, and content search over SSH: that gives one path, real
 * sizes and byte accuracy.
 */
interface FsApi {
    /**
     * @param type Null finds both files and folders.
     * @param limit Null is the server's default.
     */
    suspend fun find(
        location: Location,
        query: String,
        type: FileSearchType? = null,
        limit: Int? = null,
    ): List<FileEntry>
}

enum class FileSearchType {
    File,
    Directory,
}
