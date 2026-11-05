package io.github.thewisekd.phopencode.core.files

import io.github.thewisekd.phopencode.core.api.ApiError
import java.io.IOException

/**
 * Why a [FileService] call failed, as a type the UI can turn into a localized text.
 *
 * [message] is fixed English text for logs. No error holds a path, a name or file content, so
 * every printed form is safe to log.
 */
sealed class FileError(val message: String) {
    final override fun toString(): String = "FileError.${javaClass.simpleName}"

    data object NotFound : FileError("The file or folder does not exist.")

    data object PermissionDenied : FileError("The server does not allow this.")

    /** The target exists and the call was not allowed to replace it. */
    data object AlreadyExists : FileError("The target already exists.")

    /** A folder with content, deleted without `recursive`. */
    data object NotEmpty : FileError("The folder is not empty.")

    /**
     * The path is malformed (a `.` or `..` segment, a NUL, half a surrogate pair) or leads outside
     * the service's root, before or after symlinks. Nothing was changed.
     */
    data object PathRejected : FileError("The path is not allowed.")

    /** A file was expected, and the path names a folder or something else. */
    data object NotAFile : FileError("The path is not a file.")

    /** A folder was expected. */
    data object NotADirectory : FileError("The path is not a folder.")

    /** The service's root itself cannot be deleted or moved. */
    data object RootProtected : FileError("The root folder cannot be changed.")

    /** A folder cannot move into itself or below itself. */
    data object MoveIntoItself : FileError("A folder cannot move into itself.")

    /**
     * The file is larger than the caller's limit, and was not loaded.
     *
     * @property sizeBytes The size the server reported, or null when the file grew past the limit
     *   while it was being read.
     */
    data class TooLarge(val sizeBytes: Long?, val limitBytes: Long) :
        FileError("The file is larger than the limit.")

    /** The file changed, or went away, since the time the caller expected. Nothing was written. */
    data object Conflict : FileError("The file changed on the server.")

    /** The server has neither `rg` nor `grep`, or has no `head`. */
    data object SearchUnavailable : FileError("The server lacks the search tools.")

    /** The search tool failed without a result, for example on an invalid pattern. */
    data object SearchFailed : FileError("The search failed.")

    /** The name search of the opencode API failed. */
    data class Api(val error: ApiError) : FileError("The name search failed.")

    data object Disconnected : FileError("The connection to the server was lost.")

    data object Timeout : FileError("The server did not answer in time.")

    /** Anything the server does not report more precisely. */
    data object Failed : FileError("The file operation failed.")
}

/**
 * Carries a [FileError] out of a [FileService] call. The message is [FileError.message]; the cause,
 * when there is one, is an SSH failure whose text is fixed as well.
 */
class FileException(val error: FileError, cause: Throwable? = null) : IOException(error.message, cause)
