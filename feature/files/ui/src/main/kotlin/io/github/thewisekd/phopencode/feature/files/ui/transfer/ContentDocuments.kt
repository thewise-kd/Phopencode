package io.github.thewisekd.phopencode.feature.files.ui.transfer

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.result.contract.ActivityResultContract
import io.github.thewisekd.phopencode.feature.files.logic.contract.LocalDocument
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/**
 * Turns a document picked in the system picker into a [LocalDocument]. Reads nothing: it is called
 * in the picker's result callback, on the main thread, and the document goes to the queue at once.
 */
internal fun interface DocumentAccess {
    /**
     * [fallbackName] names the document when its provider does not tell; [forWriting] marks a
     * download's target, which the queue writes and may delete.
     */
    fun open(uri: Uri, fallbackName: String, forWriting: Boolean): LocalDocument
}

/**
 * Documents through the [resolver], by the URI the system picker granted. Nothing but those URIs
 * is reached, so the app needs no storage permission.
 *
 * The grant: the Storage Access Framework guide says access to a picked document lasts until the
 * device restarts, but the platform grants a picker's result to the activity that asked and drops
 * it when that activity is destroyed (`ActivityRecord.removeUriPermissionsLocked`). A rotation
 * keeps the activity; finishing it would revoke the grant while queued transfers still need the
 * document. So opening takes the persistable grant the picker offers
 * (`FLAG_GRANT_PERSISTABLE_URI_PERMISSION`, set for `ACTION_OPEN_DOCUMENT` and
 * `ACTION_CREATE_DOCUMENT`), and [releaseTransferGrants] gives every one back once the queue is
 * idle, so none is kept for good.
 *
 * The name and size are read once, when first asked for, wherever that is: the provider is not
 * queried in the result callback.
 */
internal class ContentDocuments(private val resolver: ContentResolver) : DocumentAccess {
    override fun open(uri: Uri, fallbackName: String, forWriting: Boolean): LocalDocument {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            (if (forWriting) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        try {
            resolver.takePersistableUriPermission(uri, flags)
        } catch (notPersistable: SecurityException) {
            // A provider that offers no persistable grant: the activity's grant is all there is.
        }
        return UriDocument(resolver, uri, fallbackName)
    }
}

/**
 * Gives back every persisted URI grant of the app. Transfers are the only part of the app that
 * persists grants, and they hold them only while the queue has work: the transfer service calls
 * this when the queue is idle. A grant left by a process that died is given back the next time.
 */
internal fun releaseTransferGrants(resolver: ContentResolver) {
    resolver.persistedUriPermissions.toList().forEach { grant ->
        val flags = (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        try {
            resolver.releasePersistableUriPermission(grant.uri, flags)
        } catch (gone: SecurityException) {
            // Already given back.
        }
    }
}

private val TRUNCATING_MODES = listOf("wt", "rwt")

private class Metadata(val name: String?, val size: Long?)

private fun ContentResolver.metadata(uri: Uri): Metadata {
    var name: String? = null
    var size: Long? = null
    try {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { row ->
            if (row.moveToFirst()) {
                val nameColumn = row.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = row.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0 && !row.isNull(nameColumn)) name = row.getString(nameColumn)
                if (sizeColumn >= 0 && !row.isNull(sizeColumn)) size = row.getLong(sizeColumn).takeIf { it >= 0 }
            }
        }
    } catch (unreadable: RuntimeException) {
        // A provider that cannot tell: the fallback name and an unknown size.
    }
    return Metadata(name, size)
}

/** A document's file name: the last part of what its provider gave, or [fallback] when that is no name. */
internal fun documentName(raw: String?, fallback: String): String {
    val name = raw?.substringAfterLast('/')?.trim().orEmpty()
    return if (name.isEmpty() || name == "." || name == "..") fallback else name
}

/** Holds no stream: each call opens a new one, which the caller closes. */
private class UriDocument(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val fallbackName: String,
) : LocalDocument {
    private val metadata by lazy { resolver.metadata(uri) }

    override val name: String
        get() = documentName(metadata.name, fallbackName)

    override val sizeBytes: Long?
        get() = metadata.size

    override fun openInput(): InputStream =
        resolver.openInputStream(uri) ?: throw FileNotFoundException("The provider gave no stream")

    /**
     * Always truncates: `wt`, or `rwt` from a provider without it. Plain `w` is never used, since
     * some providers keep the old bytes past the new end. A provider with neither mode fails the
     * download, and the queue deletes the document as after any failure.
     */
    override fun openOutput(): OutputStream {
        for (mode in TRUNCATING_MODES) {
            try {
                resolver.openOutputStream(uri, mode)?.let { return it }
            } catch (unsupported: IllegalArgumentException) {
                // Try the next mode.
            } catch (unsupported: FileNotFoundException) {
                // Try the next mode.
            }
        }
        throw FileNotFoundException("The provider cannot replace the document's content")
    }

    /**
     * A plain provider deletes through the resolver. A documents provider, as the create-document
     * picker's, refuses that and deletes through [DocumentsContract]; asking it first would not do,
     * since a plain provider ignores that call and seems to succeed.
     */
    override fun delete(): Boolean {
        try {
            return resolver.delete(uri, null, null) > 0
        } catch (notPlain: RuntimeException) {
            // A documents provider: below.
        }
        return try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (refused: RuntimeException) {
            false
        } catch (gone: FileNotFoundException) {
            false
        }
    }

    override fun toString(): String = "UriDocument(***)"
}

/**
 * Where a download goes: the system's create-document picker opens with [name] and [mimeType]
 * filled in.
 */
internal data class DownloadTarget(val name: String, val mimeType: String)

/** The download's target for the remote file [name]; a type the phone does not know is plain bytes. */
internal fun downloadTarget(name: String): DownloadTarget {
    val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    val type = extension.takeIf { it.isNotEmpty() }?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
    return DownloadTarget(name, type ?: "application/octet-stream")
}

/** `ACTION_CREATE_DOCUMENT` with the type picked per file, which the platform's contract fixes once. */
internal class CreateDocumentContract : ActivityResultContract<DownloadTarget, Uri?>() {
    override fun createIntent(context: Context, input: DownloadTarget): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType)
            .putExtra(Intent.EXTRA_TITLE, input.name)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent.takeIf { resultCode == Activity.RESULT_OK }?.data
}
