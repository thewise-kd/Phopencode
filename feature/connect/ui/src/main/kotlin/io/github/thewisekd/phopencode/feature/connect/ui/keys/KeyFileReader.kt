package io.github.thewisekd.phopencode.feature.connect.ui.keys

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysIntent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

/** The largest file read as a private key. Real ones are a few KiB; anything larger is refused unread. */
internal const val MAX_KEY_FILE_BYTES = 64 * 1024

/** The longest name an imported key gets from its file name. */
internal const val MAX_KEY_NAME_LENGTH = 64

/** Why a picked file was not sent. The screen shows these itself: the presenter never saw the file. */
internal enum class KeyFileError { TooLarge, Empty, Unreadable }

/** Where a picked file is read from: the content resolver in the app, a fake in tests. */
internal interface KeyFileSource {
    /** Opens [uri] for reading, or returns `null` when it cannot. May throw. */
    fun open(uri: Uri): InputStream?

    /** The name the provider shows for [uri], or `null`. */
    fun displayName(uri: Uri): String?
}

/** Reads documents the system picker granted through [resolver]. */
internal class ContentResolverKeyFileSource(private val resolver: ContentResolver) : KeyFileSource {
    override fun open(uri: Uri): InputStream? = resolver.openInputStream(uri)

    override fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
}

/**
 * Turns a picked file into one [KeysIntent.Import]. Reads at most [maxBytes] on [dispatcher], never
 * the main thread, and hands the bytes straight to the intent: no copy stays here, and nothing about
 * the content is logged or saved. Every buffer comes from [newBuffer], which tests replace to check
 * that each one is wiped.
 */
internal class KeyFileReader(
    private val source: KeyFileSource,
    private val maxBytes: Int = MAX_KEY_FILE_BYTES,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val newBuffer: (Int) -> ByteArray = ::ByteArray,
) {
    /**
     * Reads [uri] and sends it through [onIntent], named after the file or [fallbackName].
     *
     * Cancellable: a cancelled import sends nothing and wipes whatever it read, also when the cancel
     * comes after the read but before its result is back on the caller's thread.
     *
     * @return `null` once the intent is sent, or why the file was refused; then nothing is sent.
     */
    suspend fun import(uri: Uri, fallbackName: String, onIntent: (KeysIntent) -> Unit): KeyFileError? {
        // Owns the bytes from the moment they exist until the intent takes them. Created out here,
        // so a result dropped by cancellation on its way back is still wiped by the finally block.
        val holder = ContentHolder()
        try {
            val error = withContext(dispatcher) { read(uri, holder) }
            if (error != null) return error
            // No suspension from here to the hand-off, so it cannot be cut short half way.
            currentCoroutineContext().ensureActive()
            onIntent(KeysIntent.Import(keyName(holder.name, fallbackName), holder.take()))
            return null
        } finally {
            holder.wipe()
        }
    }

    /** The bytes read, until [take] hands them on. Written on the reading thread, read on the caller's. */
    private class ContentHolder {
        @Volatile
        var name: String? = null

        @Volatile
        private var bytes: ByteArray? = null

        fun own(content: ByteArray) {
            bytes = content
        }

        fun take(): ByteArray = checkNotNull(bytes).also { bytes = null }

        fun wipe() {
            bytes?.fill(0)
            bytes = null
        }
    }

    /**
     * Fills [holder] with the file and its name, or returns why the file is refused.
     *
     * A blocking read ignores cancellation, and a stalled provider can block it for good. So while the
     * stream is read, a watcher closes it as soon as this coroutine is cancelled, on the cancelling
     * thread: the blocked read then fails, the worker thread returns, and the buffers are wiped.
     */
    private suspend fun read(uri: Uri, holder: ContentHolder): KeyFileError? = coroutineScope {
        try {
            val input = source.open(uri) ?: return@coroutineScope KeyFileError.Unreadable
            val closeOnCancel = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    // Never fails this scope: a child failure would escape the catches below.
                    closeQuietly(input)
                }
            }
            val error = try {
                readBounded(input, holder)
            } finally {
                closeOnCancel.cancel()
                closeQuietly(input)
            }
            if (error == null) holder.name = displayNameOrNull(uri)
            error
        } catch (_: IOException) {
            // Also a read that failed because cancelling closed the stream: the caller drops it anyway.
            KeyFileError.Unreadable
        } catch (_: SecurityException) {
            KeyFileError.Unreadable
        } catch (_: IllegalArgumentException) {
            // A provider that does not know the URI.
            KeyFileError.Unreadable
        }
    }

    /**
     * Reads the whole stream into [holder] if it holds 1 to [maxBytes] bytes; otherwise says why not.
     * The read buffer is wiped before it is dropped, and the content belongs to [holder] before any
     * byte is copied into it.
     */
    private fun readBounded(input: InputStream, holder: ContentHolder): KeyFileError? {
        val buffer = newBuffer(maxBytes + 1)
        try {
            var total = 0
            while (total < buffer.size) {
                val count = input.read(buffer, total, buffer.size - total)
                if (count < 0) break
                total += count
            }
            if (total > maxBytes) return KeyFileError.TooLarge
            if (total == 0) return KeyFileError.Empty
            val content = newBuffer(total)
            holder.own(content)
            buffer.copyInto(content, 0, 0, total)
            return null
        } finally {
            buffer.fill(0)
        }
    }

    /**
     * Closes [input], ignoring a failure: everything needed was read, or the read is abandoned. A
     * broken provider's close must neither fail a good import nor crash a cancelled one.
     */
    private fun closeQuietly(input: InputStream) {
        try {
            input.close()
        } catch (_: IOException) {
            // Nothing to do: the stream is dropped either way.
        } catch (_: RuntimeException) {
            // Same for a provider that throws something else on close.
        }
    }

    private fun displayNameOrNull(uri: Uri): String? = try {
        source.displayName(uri)
    } catch (_: RuntimeException) {
        // The name is a nicety: a provider that fails to give one does not stop the import.
        null
    }

    private fun keyName(name: String?, fallbackName: String): String {
        val clean = name.orEmpty().filterNot { it.isISOControl() }.trim().take(MAX_KEY_NAME_LENGTH).trim()
        return clean.ifEmpty { fallbackName }
    }
}
