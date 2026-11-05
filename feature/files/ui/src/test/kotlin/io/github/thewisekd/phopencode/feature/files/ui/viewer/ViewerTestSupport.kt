package io.github.thewisekd.phopencode.feature.files.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import io.github.thewisekd.phopencode.core.navigation.FilesRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.files.logic.contract.ImageFormat
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerContent
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerState

internal const val ROOT = "/srv/çalışma alanı"
internal const val CODE_PATH = "src/main kodu.kt"
internal const val MARKDOWN_PATH = "docs/README.md"
internal const val IMAGE_PATH = "art/logo.png"

private const val WAIT_MS = 5_000L

internal fun routeOf(path: String, line: Int? = null) = FilesRoute.Viewer(directory = ROOT, path = path, line = line)

internal fun loaded(path: String, content: ViewerContent) = ViewerState(path = path, loading = false, content = content)

internal val CODE = loaded(CODE_PATH, ViewerContent.Text("fun main() = println(\"merhaba\")\n"))
internal val MARKDOWN = loaded(MARKDOWN_PATH, ViewerContent.Text("# Başlık\n\nSome *text* here.\n", markdown = true))
internal val MALFORMED = loaded(CODE_PATH, ViewerContent.Text("a�b\n", malformed = true))

/**
 * A valid 2 x 2 PNG, every pixel opaque red (RGB 8-bit, no alpha), written out as hex so that no
 * binary file is committed. It was made with Python's zlib and struct; any PNG decoder reads it.
 */
internal val PNG_BYTES: ByteArray =
    (
        "89504e470d0a1a0a0000000d4948445200000002000000020802000000fdd49a73" +
            "000000104944415478da63f8cfc000440c100a001fee03fd635ebb5b" +
            "0000000049454e44ae426082"
        ).chunked(2).map { it.toInt(radix = 16).toByte() }.toByteArray()

/** Only the PNG signature: the format is detected, but no decoder can read an image from it. */
internal val PNG_SIGNATURE_ONLY = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

internal val IMAGE = loaded(IMAGE_PATH, ViewerContent.Image(PNG_BYTES, ImageFormat.Png))
internal val BROKEN_IMAGE = loaded(IMAGE_PATH, ViewerContent.Image(PNG_SIGNATURE_ONLY, ImageFormat.Png))

/** The viewer in the app theme, with [state] changeable by the test and every intent recorded. */
internal class ViewerHarness(val route: FilesRoute.Viewer, initial: ViewerState) {
    val state: MutableState<ViewerState> = mutableStateOf(initial)
    val intents = mutableListOf<ViewerIntent>()

    @Composable
    fun Content() {
        PhopencodeTheme(darkTheme = false) {
            ViewerScreen(route = route, state = state.value, onIntent = { intents += it })
        }
    }
}

internal fun ComposeContentTestRule.setViewer(
    state: ViewerState,
    route: FilesRoute.Viewer = routeOf(state.path),
): ViewerHarness {
    val harness = ViewerHarness(route, state)
    setContent { harness.Content() }
    return harness
}

/** Waits until some node shows [text], as Markdown parses off the main thread. */
internal fun ComposeContentTestRule.awaitText(text: String, substring: Boolean = false) {
    waitUntil(WAIT_MS) {
        onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
}

/** Waits until some node carries [tag], as an image decodes off the main thread. */
internal fun ComposeContentTestRule.awaitTag(tag: String) {
    waitUntil(WAIT_MS) { hasTag(tag) }
}

/** Whether some node carries [tag]. */
internal fun ComposeContentTestRule.hasTag(tag: String): Boolean =
    onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
