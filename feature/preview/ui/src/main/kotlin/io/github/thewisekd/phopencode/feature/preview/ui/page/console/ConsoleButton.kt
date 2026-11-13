package io.github.thewisekd.phopencode.feature.preview.ui.page.console

import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
import io.github.thewisekd.phopencode.feature.preview.ui.R

/** The badge shows counts up to this; above it reads [BADGE_OVERFLOW], TalkBack still the full count. */
private const val BADGE_MAX = 99
private const val BADGE_OVERFLOW = "99+"

/**
 * The preview toolbar's console button (P13.14): shows or hides the [ConsolePanel], with a badge
 * for [errorCount], the console's errors whatever its filter. TalkBack reads what a tap does and
 * the error count in full. A 48 dp target, as every icon button.
 */
@Composable
internal fun ConsoleButton(errorCount: Int, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val action = stringResource(if (expanded) R.string.preview_console_hide else R.string.preview_console_show)
    val description = if (errorCount > 0) {
        val errors = pluralStringResource(R.plurals.preview_console_errors, errorCount, errorCount)
        stringResource(R.string.preview_console_button_errors, action, errors)
    } else {
        action
    }
    IconButton(
        onClick = onToggle,
        modifier = modifier.testTag(ConsoleTags.BUTTON).semantics { contentDescription = description },
    ) {
        BadgedBox(
            modifier = Modifier.clearAndSetSemantics {},
            badge = {
                if (errorCount > 0) {
                    Badge(modifier = Modifier.testTag(ConsoleTags.BADGE)) {
                        Text(if (errorCount > BADGE_MAX) BADGE_OVERFLOW else errorCount.toString())
                    }
                }
            },
        ) {
            Icon(imageVector = PhopencodeIcons.Terminal, contentDescription = null)
        }
    }
}
