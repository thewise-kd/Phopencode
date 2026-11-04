package io.github.thewisekd.phopencode.core.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp

/** Wide enough for a phone in portrait. Layout checks compare against it. */
internal val CONTAINER_WIDTH = 360.dp

/** Records what the component asks the system to open. */
internal class RecordingUriHandler(private val failure: RuntimeException? = null) : UriHandler {
    val opened = mutableListOf<String>()

    override fun openUri(uri: String) {
        opened += uri
        failure?.let { throw it }
    }
}

internal fun ComposeContentTestRule.setMarkdownContent(
    uriHandler: UriHandler = RecordingUriHandler(),
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    setContent {
        MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                Surface {
                    Box(Modifier.width(CONTAINER_WIDTH)) { content() }
                }
            }
        }
    }
}

/** The text a node shows, with its styles, read from the unmerged tree. */
internal fun SemanticsNodeInteraction.annotatedText(): AnnotatedString =
    fetchSemanticsNode().config[SemanticsProperties.Text].single()

/** Taps the middle of [label] inside the text node that shows it, the way a finger would. */
internal fun ComposeContentTestRule.tapLink(label: String) {
    val node = onNodeWithText(label, substring = true, useUnmergedTree = true)
    val text = node.annotatedText().text
    val start = text.indexOf(label)
    check(start >= 0) { "link text not found" }
    val layouts = mutableListOf<TextLayoutResult>()
    checkNotNull(node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts)
    val box = layouts.single().getBoundingBox(start + label.length / 2)
    node.performTouchInput { click(box.center) }
}
