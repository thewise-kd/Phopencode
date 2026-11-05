package io.github.thewisekd.phopencode.feature.files.logic.transfer

import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileTransfers
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesException
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.LocalDocument
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred

internal const val ROOT = "/srv/çalışma alanı"

internal fun utf8(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

/**
 * A pretend server's files, keyed by absolute path, with the port's atomic behaviour: an upload
 * streams into a temporary file and renames it over the target only at the end; on any failure or
 * cancellation the temporary file goes and the target stays as it was.
 */
internal class FakeTransfers(private val chunk: Int = 4) : FileTransfers {
    val files = mutableMapOf<String, ByteArray>()

    val calls = mutableListOf<String>()

    /** The target of each upload call, with its overwrite flag. */
    val uploads = mutableListOf<Pair<String, Boolean>>()

    /** Awaited after the first chunk of each transfer, when set: lets a test act mid-transfer. */
    var gate: CompletableDeferred<Unit>? = null

    /** Thrown after the first chunk, when set. */
    var failMidway: Exception? = null

    var statFailure: FilesFailure? = null

    /** What stat reports as the size, when set; otherwise the real one. */
    var reportedSize: Long? = null

    var statType: FileEntryType = FileEntryType.File

    private fun absolute(path: String): String = if (path.startsWith("/")) path else "$ROOT/$path"

    val temporaries: List<String>
        get() = files.keys.filter { it.endsWith(".part") }

    override suspend fun stat(directory: String, path: String): FileEntry {
        calls += "stat"
        check(directory == ROOT)
        statFailure?.let { throw FilesException(it) }
        val content = files[absolute(path)] ?: throw FilesException(FilesFailure.NotFound)
        return FileEntry(absolute(path), statType, sizeBytes = reportedSize ?: content.size.toLong())
    }

    override suspend fun upload(
        directory: String,
        path: String,
        source: InputStream,
        overwrite: Boolean,
        onProgress: (Long) -> Unit,
    ) {
        calls += "upload"
        check(directory == ROOT)
        val target = absolute(path)
        uploads += target to overwrite
        if (!overwrite && target in files) throw FilesException(FilesFailure.AlreadyExists)
        val temporary = "$target.part"
        files[temporary] = ByteArray(0)
        try {
            val buffer = ByteArray(chunk)
            var sent = 0L
            var first = true
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                files[temporary] = files.getValue(temporary) + buffer.copyOf(read)
                sent += read
                onProgress(sent)
                if (first) {
                    first = false
                    gate?.await()
                    failMidway?.let { throw it }
                }
            }
            files[target] = files.getValue(temporary)
        } finally {
            files.remove(temporary)
        }
    }

    override suspend fun download(directory: String, path: String, sink: OutputStream, onProgress: (Long) -> Unit): Long {
        calls += "download"
        check(directory == ROOT)
        val content = files[absolute(path)] ?: throw FilesException(FilesFailure.NotFound)
        var written = 0L
        var first = true
        for (piece in content.toList().chunked(chunk)) {
            sink.write(piece.toByteArray())
            written += piece.size
            onProgress(written)
            if (first) {
                first = false
                gate?.await()
                failMidway?.let { throw it }
            }
        }
        return written
    }
}

/** A picked document: its content, and what was done to it. */
internal class FakeDocument(
    override val name: String,
    content: ByteArray = ByteArray(0),
    override val sizeBytes: Long? = content.size.toLong(),
) : LocalDocument {
    var content: ByteArray? = content

    var deleted = false
    var deleteSucceeds = true
    var openFailure: IOException? = null

    /** Thrown by a stream's close, after it is closed, when set. */
    var closeFailure: IOException? = null

    var openStreams = 0
    val events = mutableListOf<String>()

    override fun openInput(): InputStream {
        events += "openInput"
        openFailure?.let { throw it }
        val bytes = content ?: throw IOException("gone")
        openStreams++
        return object : ByteArrayInputStream(bytes) {
            override fun close() {
                events += "closeInput"
                openStreams--
                super.close()
                closeFailure?.let { throw it }
            }
        }
    }

    override fun openOutput(): OutputStream {
        events += "openOutput"
        openFailure?.let { throw it }
        content = ByteArray(0)
        openStreams++
        return object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                super.write(b, off, len)
                content = toByteArray()
            }

            override fun close() {
                events += "closeOutput"
                openStreams--
                super.close()
                closeFailure?.let { throw it }
            }
        }
    }

    override fun delete(): Boolean {
        events += "delete"
        if (!deleteSucceeds) return false
        deleted = true
        content = null
        return true
    }
}
