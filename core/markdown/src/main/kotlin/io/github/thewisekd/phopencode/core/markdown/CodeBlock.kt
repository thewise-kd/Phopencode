package io.github.thewisekd.phopencode.core.markdown

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCode
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes

/**
 * One code block: highlighted when [language] is declared in [CodeLanguages], plain
 * otherwise. Each block gets its own Highlights builder, because a builder is mutable and
 * blocks may be highlighted at the same time.
 */
@Composable
internal fun CodeBlock(
    code: String,
    language: String?,
    style: TextStyle,
    darkTheme: Boolean,
    highlightImmediately: Boolean,
) {
    val syntax = CodeLanguages.find(language)
    if (syntax == null) {
        PlainCode(code, style)
        return
    }
    val builder = remember(darkTheme) { Highlights.Builder(theme = SyntaxThemes.default(darkMode = darkTheme)) }
    MarkdownHighlightedCode(
        code = code,
        language = syntax.name,
        style = style,
        highlightsBuilder = builder,
        showHeader = false,
        immediate = highlightImmediately,
    )
}

@Composable
private fun PlainCode(code: String, style: TextStyle) {
    MarkdownCodeBackground(
        color = LocalMarkdownColors.current.codeBackground,
        shape = RoundedCornerShape(LocalMarkdownDimens.current.codeBackgroundCornerSize),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = code,
            color = LocalMarkdownColors.current.text,
            style = style,
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(CODE_PADDING),
        )
    }
}

private val CODE_PADDING = 8.dp
