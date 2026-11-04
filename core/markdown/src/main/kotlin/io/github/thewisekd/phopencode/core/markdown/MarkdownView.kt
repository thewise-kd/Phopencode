package io.github.thewisekd.phopencode.core.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.rememberMarkdownState

/** The tag on the root of [MarkdownView], for tests in this module. */
internal const val MARKDOWN_VIEW_TAG = "markdown"

/** The tag on the plain-text fallback shown if the Markdown cannot be parsed. */
internal const val MARKDOWN_FALLBACK_TAG = "markdown:fallback"

/**
 * Renders [markdown] (GitHub flavor) with Material 3 styles: headings, emphasis, lists,
 * task lists, quotes, tables, inline code and code blocks.
 *
 * - **Streaming:** pass the text received so far and call again as it grows. Unfinished
 *   Markdown, such as an open code fence, a half table or a half link, renders as far as it
 *   goes. Parsing runs off the main thread, and the previous result stays on screen until
 *   the next one is ready, so the content does not flicker or jump back to empty.
 * - **Code:** a fenced block whose language is one of the declared names (Kotlin, Java,
 *   JavaScript, TypeScript, Python, shell, Go, Rust, Swift, C, C++, C#, Dart, PHP, Ruby,
 *   Perl, CoffeeScript, and common short names such as `kt`, `py` or `sh`) is
 *   syntax-highlighted in the current light or dark theme. Any other language, or none,
 *   renders as plain monospaced code.
 * - **Links:** tapping a link never opens it directly. An http or https link opens in the
 *   system only after the user confirms a dialog that shows its full target; any other
 *   scheme, or a relative link, is refused with a dialog that shows the target. Images are
 *   not loaded.
 *
 * The content is not scrollable by itself: put it in a scrolling container. It is user data
 * and never logged.
 */
@Composable
fun MarkdownView(markdown: String, modifier: Modifier = Modifier) {
    MarkdownBody(markdown = markdown, modifier = modifier, parseImmediately = false)
}

/**
 * [MarkdownView] with the parsing mode open. Tests parse on the composing thread to check
 * every step of a stream; the app parses off the main thread.
 */
@Composable
internal fun MarkdownBody(markdown: String, modifier: Modifier, parseImmediately: Boolean) {
    val links = rememberLinkConfirmation(LocalUriHandler.current)
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < HALF_LUMINANCE
    val components = remember(darkTheme, parseImmediately) { codeComponents(darkTheme, parseImmediately) }
    val state = rememberMarkdownState(markdown, retainState = true, immediate = parseImmediately)

    CompositionLocalProvider(LocalUriHandler provides links.handler) {
        Markdown(
            markdownState = state,
            modifier = modifier.testTag(MARKDOWN_VIEW_TAG),
            components = components,
            error = { errorModifier ->
                Text(text = markdown, modifier = errorModifier.testTag(MARKDOWN_FALLBACK_TAG))
            },
        )
    }
    LinkDialog(links)
}

private const val HALF_LUMINANCE = 0.5f

/** The library's components, with code blocks drawn by [CodeBlock] in place of its own. */
private fun codeComponents(darkTheme: Boolean, highlightImmediately: Boolean): MarkdownComponents =
    markdownComponents(
        codeFence = { model ->
            MarkdownCodeFence(model.content, model.node, model.typography.code) { code, language, style ->
                CodeBlock(code, language, style, darkTheme, highlightImmediately)
            }
        },
        codeBlock = { model ->
            MarkdownCodeBlock(model.content, model.node, model.typography.code) { code, language, style ->
                CodeBlock(code, language, style, darkTheme, highlightImmediately)
            }
        },
    )
