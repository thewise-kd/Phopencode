package io.github.thewisekd.phopencode.feature.files.logic.viewer

import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileContent
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesException
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerContent
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerPresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerState
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
 * One file, read-only (P8.6); the rules are on [ViewerState].
 *
 * - A load is [FileContent.stat], then, by the size it gives:
 *   above [maxBytes], [ViewerContent.TooLarge] that cannot be opened, and nothing is read;
 *   above [warnBytes], [ViewerContent.TooLarge] that [ViewerIntent.OpenAnyway] opens;
 *   otherwise [FileContent.read] up to [warnBytes], or up to [maxBytes] once opened anyway.
 *   A size the stat does not know, or a file that grew between the stat and the read, is caught by
 *   the read's own limit and shows as [ViewerContent.TooLarge] as well.
 * - What the bytes are is [ViewerDetection]'s: the bytes decide, never the extension alone, and
 *   invalid UTF-8 is replaced, never thrown.
 * - One load at a time. [ViewerIntent.Refresh] keeps what is shown while it checks the stat, and
 *   skips the read only when both stats give a size and a `modifiedAt` and the path, type, size
 *   and `modifiedAt` match the loaded ones; anything it cannot prove unchanged is loaded again, so
 *   a file that became a folder shows [FilesFailure.Unsupported] without being read. After an error, with nothing loaded,
 *   it loads. A file opened anyway stays opened across refreshes.
 * - A failure of a port shows as [ViewerState.error] with no content; any other exception as
 *   [FilesFailure.Unknown]. Cancellation stops the work and is never shown.
 *
 * Nothing runs in the constructor: work starts in [scope] after a `yield()`. Nothing here logs;
 * the path and the content are user data.
 *
 * @param directory The project's folder, from the route.
 * @param path The file, from the route.
 * @param line The 1-based line to show, from the route.
 * @param files Reads the file.
 * @param warnBytes Above this, the file is read only after `ViewerIntent.OpenAnyway`.
 * @param maxBytes Above this, the file is never read.
 */
class DefaultViewerPresenter(
    private val directory: String,
    private val path: String,
    private val line: Int?,
    private val files: FileContent,
    private val scope: CoroutineScope,
    private val warnBytes: Long = DEFAULT_WARN_BYTES,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : ViewerPresenter {
    /**
     * What a refresh compares: the stat behind what is shown. The path and the type are the file's
     * identity; [FileEntry.ignored] is a `.gitignore` marker, not the content's, and is left out.
     */
    private data class Stamp(
        val path: String,
        val type: FileEntryType,
        val sizeBytes: Long?,
        val modifiedAt: Instant?,
    )

    /**
     * @property busy A load or a refresh is on its way.
     * @property entry The last stat, behind what is shown; null before the first one.
     * @property openAnyway The user opened the file above the warning limit.
     */
    private data class Model(
        val state: ViewerState,
        val busy: Boolean = true,
        val entry: FileEntry? = null,
        val openAnyway: Boolean = false,
    ) {
        override fun toString(): String = "Model(state=$state, busy=$busy, openAnyway=$openAnyway)"
    }

    private val lock = Any()

    private var model = Model(ViewerState(path = path, line = line))

    private val mutableState = MutableStateFlow(model.state)

    override val state: StateFlow<ViewerState> = mutableState.asStateFlow()

    private val eventChannel = Channel<ViewerEvent>(Channel.BUFFERED)

    override val events: Flow<ViewerEvent> = eventChannel.receiveAsFlow()

    init {
        scope.launch {
            yield()
            load(entry = null)
        }
    }

    override fun onIntent(intent: ViewerIntent) {
        when (intent) {
            is ViewerIntent.SetMode -> change { it.copy(state = it.state.copy(mode = intent.mode)) }
            ViewerIntent.OpenAnyway -> openAnyway()
            ViewerIntent.Edit -> if (state.value.canEdit) eventChannel.trySend(ViewerEvent.OpenEditor)
            ViewerIntent.Refresh -> refresh()
            ViewerIntent.Retry -> retry()
            ViewerIntent.Close -> eventChannel.trySend(ViewerEvent.Close)
        }
    }

    // Intents

    private fun retry() {
        synchronized(lock) {
            if (model.busy || model.state.error == null) return
            commit(model.copy(busy = true, state = model.state.copy(loading = true, error = null, content = null)))
        }
        scope.launch {
            yield()
            load(entry = null)
        }
    }

    private fun openAnyway() {
        val entry = synchronized(lock) {
            val tooLarge = model.state.content as? ViewerContent.TooLarge
            if (model.busy || tooLarge == null || !tooLarge.canOpenAnyway) return
            commit(
                model.copy(
                    busy = true,
                    openAnyway = true,
                    state = model.state.copy(loading = true, content = null),
                ),
            )
            model.entry
        }
        scope.launch {
            yield()
            load(entry)
        }
    }

    private fun refresh() {
        synchronized(lock) {
            if (model.busy) return
            if (model.state.error != null) {
                commit(model.copy(busy = true, state = model.state.copy(loading = true, error = null, content = null)))
                scope.launch {
                    yield()
                    load(entry = null)
                }
                return
            }
            commit(model.copy(busy = true))
        }
        scope.launch {
            yield()
            check()
        }
    }

    // Loading

    /** A refresh: loads with the new stat only if it differs from the shown one. */
    private suspend fun check() {
        val entry = try {
            files.stat(directory, path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finish(entry = null, result = Result.failure(e))
            return
        }
        val unchanged = synchronized(lock) {
            val same = provablySame(model.entry, entry)
            if (same) commit(model.copy(busy = false))
            same
        }
        if (!unchanged) load(entry)
    }

    /** Stats the file unless [entry] is given, then reads what the limits allow. */
    private suspend fun load(entry: FileEntry?) {
        var stat: FileEntry? = entry
        val result = try {
            val known = stat ?: files.stat(directory, path).also { stat = it }
            Result.success(content(known))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        finish(stat, result)
    }

    private suspend fun content(entry: FileEntry): ViewerContent {
        if (entry.type == FileEntryType.Directory) throw FilesException(FilesFailure.Unsupported)
        val size = entry.sizeBytes
        val opened = synchronized(lock) { model.openAnyway }
        if (size != null && size > maxBytes) return ViewerContent.TooLarge(size, maxBytes, canOpenAnyway = false)
        if (size != null && size > warnBytes && !opened) {
            return ViewerContent.TooLarge(size, warnBytes, canOpenAnyway = true)
        }
        val limit = if (opened) maxBytes else warnBytes
        val bytes = try {
            files.read(directory, path, limit)
        } catch (e: FilesException) {
            if (e.failure != FilesFailure.TooLarge) throw e
            // Unknown size, or the file grew since the stat: it is above the limit of this read.
            val atLeast = maxOf(size ?: 0L, limit + 1)
            return ViewerContent.TooLarge(atLeast, limit, canOpenAnyway = limit < maxBytes)
        }
        return ViewerDetection.detect(path, bytes)
    }

    private fun finish(entry: FileEntry?, result: Result<ViewerContent>) {
        val failure = result.exceptionOrNull()?.let { (it as? FilesException)?.failure ?: FilesFailure.Unknown }
        change { current ->
            current.copy(
                busy = false,
                entry = if (failure == null) entry else null,
                state = current.state.copy(
                    loading = false,
                    content = result.getOrNull(),
                    error = failure,
                ),
            )
        }
    }

    /**
     * Whether [new] provably shows the same file as [old]: both stats give a size and a
     * `modifiedAt`, and the path, the type, the size and `modifiedAt` all match. A missing value on
     * either side proves nothing, so the file is loaded again; a load never reads a folder.
     */
    private fun provablySame(old: FileEntry?, new: FileEntry): Boolean {
        if (old == null) return false
        if (old.sizeBytes == null || old.modifiedAt == null) return false
        if (new.sizeBytes == null || new.modifiedAt == null) return false
        return stamp(old) == stamp(new)
    }

    private fun stamp(entry: FileEntry): Stamp = Stamp(entry.path, entry.type, entry.sizeBytes, entry.modifiedAt)

    // State

    private inline fun change(transform: (Model) -> Model) {
        synchronized(lock) { commit(transform(model)) }
    }

    /** Called with [lock] held. */
    private fun commit(next: Model) {
        model = next
        mutableState.value = next.state
    }

    companion object {
        /** 1 MiB. */
        const val DEFAULT_WARN_BYTES: Long = 1L shl 20

        /** 10 MiB. */
        const val DEFAULT_MAX_BYTES: Long = 10L shl 20
    }
}
