package io.github.thewisekd.phopencode.feature.files.logic.viewer

import io.github.thewisekd.phopencode.feature.files.logic.contract.FileContent
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerPresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P8.2): shows the initial state and ignores intents. P8.6 fills it in
 * without changing the constructor; loading starts in [scope] after a `yield()`.
 *
 * @param directory The project's folder, from the route.
 * @param path The file, from the route.
 * @param line The 1-based line to show, from the route.
 * @param files Reads the file.
 * @param warnBytes Above this, the file is read only after `ViewerIntent.OpenAnyway`.
 * @param maxBytes Above this, the file is never read.
 */
@Suppress("unused")
class DefaultViewerPresenter(
    private val directory: String,
    private val path: String,
    private val line: Int?,
    private val files: FileContent,
    private val scope: CoroutineScope,
    private val warnBytes: Long = DEFAULT_WARN_BYTES,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : ViewerPresenter {
    private val mutableState = MutableStateFlow(ViewerState(path = path, line = line))

    override val state: StateFlow<ViewerState> = mutableState.asStateFlow()

    override val events: Flow<ViewerEvent> = emptyFlow()

    override fun onIntent(intent: ViewerIntent) = Unit

    companion object {
        /** 1 MiB. */
        const val DEFAULT_WARN_BYTES: Long = 1L shl 20

        /** 10 MiB. */
        const val DEFAULT_MAX_BYTES: Long = 10L shl 20
    }
}
