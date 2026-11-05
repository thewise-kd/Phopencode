package io.github.thewisekd.phopencode.feature.files.logic.contract

/**
 * One file, read-only, `FilesRoute.Viewer` (P8.6 logic, P8.7 screen).
 *
 * Where it comes from: [FileContent.stat] for the size, then [FileContent.read]. A file above the
 * warning limit is not read until [ViewerIntent.OpenAnyway]; one above the hard limit is never
 * read. The type comes from the bytes and the extension: an image the screen can show, text,
 * Markdown (`.md`, `.markdown`), or binary. Text is decoded as UTF-8 with invalid bytes replaced,
 * never failing ([ViewerContent.Text.malformed]).
 *
 * @property path The file, from the route.
 * @property line The 1-based line to scroll to, from the route; null shows the top.
 * @property content Null while loading or after [error].
 * @property mode How Markdown shows; other text is always raw.
 * @property error The file could not be read.
 */
data class ViewerState(
    val path: String,
    val line: Int? = null,
    val loading: Boolean = true,
    val content: ViewerContent? = null,
    val mode: ViewerMode = ViewerMode.Rendered,
    val error: FilesFailure? = null,
) {
    /** Loaded text that decoded cleanly; the editor (`FilesRoute.Editor`) can take it. */
    val canEdit: Boolean
        get() = !loading && (content as? ViewerContent.Text)?.malformed == false

    override fun toString(): String =
        "ViewerState(path=***, line=$line, loading=$loading, content=$content, mode=$mode, error=$error)"
}

sealed interface ViewerContent {
    /**
     * Highlighted by the code view of P8.3, which takes the language from the path's extension.
     *
     * @property markdown Shown rendered or raw by [ViewerState.mode].
     * @property malformed Some bytes were not valid UTF-8 and show as replacement characters.
     */
    data class Text(val text: String, val markdown: Boolean = false, val malformed: Boolean = false) : ViewerContent {
        override fun toString(): String = "Text(length=${text.length}, markdown=$markdown, malformed=$malformed)"
    }

    /** Compared by content: two reads of the same file are equal. */
    class Image(val bytes: ByteArray, val format: ImageFormat) : ViewerContent {
        override fun equals(other: Any?): Boolean =
            other is Image && format == other.format && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * format.hashCode() + bytes.contentHashCode()

        override fun toString(): String = "Image(format=$format, bytes=${bytes.size})"
    }

    /** Not text and not an image the screen can show. */
    data class Binary(val sizeBytes: Long) : ViewerContent

    /**
     * Not read: above the warning limit, or above the hard limit.
     *
     * @property canOpenAnyway Below the hard limit, so [ViewerIntent.OpenAnyway] reads it.
     */
    data class TooLarge(val sizeBytes: Long, val limitBytes: Long, val canOpenAnyway: Boolean) : ViewerContent
}

/** The image formats the viewer previews; others show as [ViewerContent.Binary]. */
enum class ImageFormat {
    Png,
    Jpeg,
    Gif,
    Webp,
    Bmp,
}

enum class ViewerMode {
    Rendered,
    Raw,
}

sealed interface ViewerIntent {
    data class SetMode(val mode: ViewerMode) : ViewerIntent

    /** Reads a [ViewerContent.TooLarge] file whose `canOpenAnyway` is set. */
    data object OpenAnyway : ViewerIntent

    /** Sends [ViewerEvent.OpenEditor] when [ViewerState.canEdit]. */
    data object Edit : ViewerIntent

    /**
     * Reads the file again if it changed: [FileContent.stat] first, and a read only when its
     * `modifiedAt` or size differ from the loaded ones. The graph sends it whenever the screen is
     * shown again, as after the editor, after the app came back, and after a rotation.
     */
    data object Refresh : ViewerIntent

    /** Reads again after [ViewerState.error]. */
    data object Retry : ViewerIntent

    data object Close : ViewerIntent
}

sealed interface ViewerEvent {
    /** The graph opens `FilesRoute.Editor` for the same file. */
    data object OpenEditor : ViewerEvent

    data object Close : ViewerEvent
}

interface ViewerPresenter : Presenter<ViewerState, ViewerIntent, ViewerEvent>
