package io.github.thewisekd.phopencode.feature.files.logic.editor

import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorPresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorState
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P8.2): shows the initial state and ignores intents. P8.8 fills it in
 * without changing the constructor; loading starts in [scope] after a `yield()`.
 *
 * @param directory The project's folder, from the route.
 * @param path The file, from the route.
 * @param files Reads and saves the file.
 * @param maxBytes Above this, the file is not loaded for editing (`EditorBlock.TooLarge`).
 */
@Suppress("unused")
class DefaultEditorPresenter(
    private val directory: String,
    private val path: String,
    private val files: FileContent,
    private val scope: CoroutineScope,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : EditorPresenter {
    private val mutableState = MutableStateFlow(EditorState(path = path))

    override val state: StateFlow<EditorState> = mutableState.asStateFlow()

    override val events: Flow<EditorEvent> = emptyFlow()

    override fun onIntent(intent: EditorIntent) = Unit

    companion object {
        /** 1 MiB. */
        const val DEFAULT_MAX_BYTES: Long = 1L shl 20
    }
}
