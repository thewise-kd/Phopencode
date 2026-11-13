package io.github.thewisekd.phopencode.feature.preview.ui.page.console

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleEntry
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleLevel
import io.github.thewisekd.phopencode.feature.preview.ui.R

/**
 * How an entry of one level is drawn: its row's [container] (transparent for the quiet levels), its
 * text's [content] colour and its level icon's [icon] tint. Errors and warnings take the theme's
 * status container pairs, so their contrast is the one the theme tests. [accent] tints the level's
 * icon on the surface, as in its filter chip.
 */
internal data class LevelStyle(val container: Color, val content: Color, val icon: Color, val accent: Color)

/** Each level's own icon; the colour and the spoken name tell them apart as well. */
internal val ConsoleLevel.icon: ImageVector
    get() = when (this) {
        ConsoleLevel.Debug -> PhopencodeIcons.Search
        ConsoleLevel.Log -> PhopencodeIcons.ChevronRight
        ConsoleLevel.Info -> PhopencodeIcons.Info
        ConsoleLevel.Warning -> PhopencodeIcons.Warning
        ConsoleLevel.Error -> PhopencodeIcons.Close
    }

@Composable
@ReadOnlyComposable
internal fun ConsoleLevel.style(): LevelStyle {
    val scheme = MaterialTheme.colorScheme
    val status = PhopencodeTheme.status
    return when (this) {
        ConsoleLevel.Error -> status.danger.run { LevelStyle(container, onContainer, onContainer, color) }
        ConsoleLevel.Warning -> status.warning.run { LevelStyle(container, onContainer, onContainer, color) }
        ConsoleLevel.Info -> status.info.run { LevelStyle(Color.Transparent, scheme.onSurface, color, color) }
        ConsoleLevel.Log -> scheme.run { LevelStyle(Color.Transparent, onSurface, onSurfaceVariant, onSurfaceVariant) }
        ConsoleLevel.Debug -> scheme.run { LevelStyle(Color.Transparent, onSurfaceVariant, onSurfaceVariant, onSurfaceVariant) }
    }
}

/** The level's name, as the filter shows it and TalkBack reads it. */
@Composable
@ReadOnlyComposable
internal fun ConsoleLevel.label(): String = stringResource(
    when (this) {
        ConsoleLevel.Debug -> R.string.preview_console_level_debug
        ConsoleLevel.Log -> R.string.preview_console_level_log
        ConsoleLevel.Info -> R.string.preview_console_level_info
        ConsoleLevel.Warning -> R.string.preview_console_level_warning
        ConsoleLevel.Error -> R.string.preview_console_level_error
    },
)

/** Where the entry came from: its source and line, either alone, or null when it has neither. */
@Composable
@ReadOnlyComposable
internal fun ConsoleEntry.location(): String? {
    val from = this.source
    val at = this.line
    return when {
        from != null && at != null -> stringResource(R.string.preview_console_source_line, from, at)
        from != null -> from
        at != null -> stringResource(R.string.preview_console_line, at)
        else -> null
    }
}
