package io.github.thewisekd.phopencode.feature.files.logic.editor

import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorBlock
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorPresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorState
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileContent
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesException
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Edits one text file (P8.8); the rules are on [EditorState].
 *
 * - Loading: [FileContent.stat] first, so a file above [maxBytes] is never read
 *   ([EditorBlock.TooLarge]); then [FileContent.read]. A file with a NUL byte is
 *   [EditorBlock.Binary]; one that is not valid UTF-8 is [EditorBlock.Malformed], since saving it
 *   would change bytes the user did not touch. A blocked file takes no edits and saves nothing.
 * - Line endings: the text is shown with `\n`, and each line's own ending, `\r\n`, `\r` or `\n`, is
 *   put back on save ([LineEndings.restore]): an untouched line keeps its ending even in a mixed
 *   file, new lines take the file's first one, and a missing final line break stays missing.
 * - Saving: [FileContent.stat] first. A `modifiedAt` or a size other than the ones of the load or
 *   the last save, or a file that is gone, means someone else changed it: that is
 *   [EditorState.conflict], and nothing is written until [EditorIntent.Overwrite]. The write is
 *   [FileContent.write], atomic on the server. A failed save sets [EditorState.saveError] and
 *   keeps the text as it is.
 * - Known limit, the check-then-write race: [FileContent.write] takes no expected `modifiedAt`,
 *   so the check and the write are two requests, and a change someone else makes between them is
 *   overwritten. The window is kept as small as the port allows: nothing suspends between the two
 *   calls but the calls themselves, and the bytes are ready before the check. After the write, the
 *   entry it returns is compared with what was sent: a size other than the bytes written means
 *   another write landed around ours. That write is unverified: the baseline stays the one before
 *   it, and [EditorState.conflict] shows, so the user reloads or overwrites knowingly. A change of
 *   the same size in that window cannot be seen.
 * - [EditorState.dirty] has one definition, applied in [commit] to every state published:
 *   the text differs from the baseline, OR a save is on its way, OR the last write is unverified.
 *   The baseline changes only on a load and on a verified save, to the text that save wrote; the
 *   unverified mark goes only on a load (also through [EditorIntent.Reload]) and on a verified save.
 *   So an undo back to the baseline cannot hide a save that may still store other text, nor a
 *   write the server answered oddly, and [EditorIntent.Back] asks first in both; a save that fails
 *   wrote nothing, so dirty is the text against the baseline again.
 *
 * Nothing runs in the constructor: loading starts in [scope] after a `yield()`. Nothing here logs;
 * the path and the text are user data.
 *
 * @param directory The project's folder, from the route.
 * @param path The file, from the route.
 * @param files Reads and saves the file.
 * @param maxBytes Above this, the file is not loaded for editing (`EditorBlock.TooLarge`).
 */
class DefaultEditorPresenter(
    private val directory: String,
    private val path: String,
    private val files: FileContent,
    private val scope: CoroutineScope,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : EditorPresenter {
    /**
     * What the server had when the file was loaded or last saved.
     *
     * @property text The file as split at its line breaks; [SplitText.text] is the clean text.
     * @property modifiedAt [FileEntry.modifiedAt] then.
     * @property sizeBytes The file's size then.
     */
    private class Base(val text: SplitText, val modifiedAt: Instant?, val sizeBytes: Long) {
        override fun toString(): String = "Base(modifiedAt=$modifiedAt, sizeBytes=$sizeBytes)"
    }

    private sealed interface Loaded {
        class Text(val base: Base) : Loaded

        class Blocked(val block: EditorBlock) : Loaded

        class Failed(val failure: FilesFailure) : Loaded
    }

    private sealed interface Saved {
        class Written(val base: Base) : Saved

        data object Conflict : Saved

        /**
         * Written, but the server reports another size than the bytes sent: someone else wrote
         * around it. Nothing is verified; the baseline stays, so the next check sees a conflict.
         */
        data object Unverified : Saved

        class Failed(val failure: FilesFailure) : Saved
    }

    private val lock = Any()

    /**
     * The baseline: the file as loaded or as the last verified save wrote it. Set once a file loads
     * as text; guarded by [lock].
     */
    private var base: Base? = null

    /** The last write was answered oddly and is not verified; guarded by [lock]. */
    private var unverified = false

    private val mutableState = MutableStateFlow(EditorState(path = path))

    override val state: StateFlow<EditorState> = mutableState.asStateFlow()

    private val eventChannel = Channel<EditorEvent>(Channel.BUFFERED)

    override val events: Flow<EditorEvent> = eventChannel.receiveAsFlow()

    init {
        scope.launch {
            yield()
            load()
        }
    }

    override fun onIntent(intent: EditorIntent) {
        when (intent) {
            is EditorIntent.TextChanged -> edit(intent.text)
            EditorIntent.Save -> save(check = true) { it.canSave }
            EditorIntent.Overwrite -> save(check = false) { it.conflict && !it.saving && it.blocked == null }
            EditorIntent.Reload -> reload()
            EditorIntent.DismissConflict -> change { it.copy(conflict = false) }
            EditorIntent.Back -> back()
            EditorIntent.ConfirmLeave -> confirmLeave()
            EditorIntent.CancelLeave -> change { it.copy(confirmingLeave = false) }
            EditorIntent.Retry -> retry()
            EditorIntent.DismissError -> change { it.copy(saveError = null) }
        }
    }

    // Editing

    private fun edit(raw: String) {
        val text = LineEndings.normalize(raw)
        synchronized(lock) {
            val current = mutableState.value
            if (base == null || current.loading || current.blocked != null || current.loadError != null) return
            commit(current.copy(text = text))
        }
    }

    // Loading

    private fun retry() {
        synchronized(lock) {
            val current = mutableState.value
            if (current.loading || current.loadError == null) return
            commit(current.copy(loading = true, loadError = null))
        }
        scope.launch { load() }
    }

    private fun reload() {
        synchronized(lock) {
            val current = mutableState.value
            if (!current.conflict || current.loading || current.saving) return
            // The edits are dropped: the user chose the server's version.
            base = null
            unverified = false
            commit(EditorState(path = path))
        }
        scope.launch { load() }
    }

    private suspend fun load() {
        val loaded = fetch()
        synchronized(lock) {
            val current = mutableState.value
            val next = when (loaded) {
                is Loaded.Text -> {
                    base = loaded.base
                    unverified = false
                    current.copy(
                        loading = false,
                        text = loaded.base.text.text,
                        lineEnding = loaded.base.text.lineEnding,
                        conflict = false,
                        blocked = null,
                        loadError = null,
                        saveError = null,
                    )
                }
                is Loaded.Blocked -> current.copy(loading = false, blocked = loaded.block)
                is Loaded.Failed -> current.copy(loading = false, loadError = loaded.failure)
            }
            commit(next)
        }
    }

    private suspend fun fetch(): Loaded {
        val entry: FileEntry
        val bytes: ByteArray
        try {
            entry = files.stat(directory, path)
            if ((entry.sizeBytes ?: 0L) > maxBytes) return Loaded.Blocked(EditorBlock.TooLarge)
            bytes = files.read(directory, path, maxBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FilesException) {
            return if (e.failure == FilesFailure.TooLarge) {
                Loaded.Blocked(EditorBlock.TooLarge)
            } else {
                Loaded.Failed(e.failure)
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            return Loaded.Failed(FilesFailure.Unknown)
        }
        if (bytes.size > maxBytes) return Loaded.Blocked(EditorBlock.TooLarge)
        if (bytes.any { it == 0.toByte() }) return Loaded.Blocked(EditorBlock.Binary)
        val text = decode(bytes) ?: return Loaded.Blocked(EditorBlock.Malformed)
        return Loaded.Text(Base(LineEndings.split(text), entry.modifiedAt, bytes.size.toLong()))
    }

    /** Strict UTF-8; null when [bytes] are not valid UTF-8, so that a save cannot change them. */
    private fun decode(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    // Saving

    private inline fun save(check: Boolean, allowed: (EditorState) -> Boolean) {
        val claimed = synchronized(lock) {
            val current = mutableState.value
            val loaded = base ?: return
            if (!allowed(current)) return
            commit(current.copy(saving = true, conflict = false, saveError = null))
            current.text to loaded
        }
        scope.launch { write(claimed.first, claimed.second, check) }
    }

    private suspend fun write(text: String, from: Base, check: Boolean) {
        val saved = store(text, from, check)
        synchronized(lock) {
            val current = mutableState.value.copy(saving = false)
            val next = when (saved) {
                is Saved.Written -> {
                    // Verified: the new baseline is exactly what was written.
                    base = saved.base
                    unverified = false
                    current.copy(lineEnding = saved.base.text.lineEnding)
                }
                Saved.Conflict -> current.copy(conflict = true)
                Saved.Unverified -> {
                    // Written, but not as sent: what the server holds is unknown.
                    unverified = true
                    current.copy(conflict = true)
                }
                is Saved.Failed -> current.copy(saveError = saved.failure)
            }
            commit(next)
        }
    }

    private suspend fun store(text: String, from: Base, check: Boolean): Saved {
        val split = LineEndings.restore(text, from.text)
        val bytes = split.encode().toByteArray(Charsets.UTF_8)
        return try {
            // Nothing but the two port calls runs between the check and the write.
            if (check && changedOnServer(from)) return Saved.Conflict
            val written = files.write(directory, path, bytes)
            val sent = Base(split, written.modifiedAt, bytes.size.toLong())
            val size = written.sizeBytes
            if (size != null && size != sent.sizeBytes) Saved.Unverified else Saved.Written(sent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FilesException) {
            Saved.Failed(e.failure)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Saved.Failed(FilesFailure.Unknown)
        }
    }

    /** Whether the file on the server is no longer the one [from] describes; a gone file is. */
    private suspend fun changedOnServer(from: Base): Boolean {
        val now = try {
            files.stat(directory, path)
        } catch (e: FilesException) {
            if (e.failure == FilesFailure.NotFound) return true
            throw e
        }
        val size = now.sizeBytes
        return now.modifiedAt != from.modifiedAt || (size != null && size != from.sizeBytes)
    }

    // Leaving

    private fun back() {
        val close = synchronized(lock) {
            val current = mutableState.value
            if (current.dirty) {
                commit(current.copy(confirmingLeave = true))
                false
            } else {
                true
            }
        }
        if (close) eventChannel.trySend(EditorEvent.Close)
    }

    private fun confirmLeave() {
        synchronized(lock) {
            val current = mutableState.value
            if (!current.confirmingLeave) return
            commit(current.copy(confirmingLeave = false))
        }
        eventChannel.trySend(EditorEvent.Close)
    }

    private inline fun change(transform: (EditorState) -> EditorState) {
        synchronized(lock) { commit(transform(mutableState.value)) }
    }

    /**
     * Publishes [next] with [EditorState.dirty] set by its one rule: the text differs from the
     * baseline, or a save is on its way, or the last write is unverified. Every state change goes
     * through here; call it holding [lock].
     */
    private fun commit(next: EditorState) {
        val baseline = base
        val dirty = baseline != null && (next.text != baseline.text.text || next.saving || unverified)
        mutableState.value = next.copy(dirty = dirty)
    }

    companion object {
        /** 1 MiB. */
        const val DEFAULT_MAX_BYTES: Long = 1L shl 20
    }
}
