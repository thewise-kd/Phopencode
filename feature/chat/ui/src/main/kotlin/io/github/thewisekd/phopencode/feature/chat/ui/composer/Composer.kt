package io.github.thewisekd.phopencode.feature.chat.ui.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerState

/**
 * Stub from the contract (P6.1); P6.6 draws the composer. Draws [state], sends [onIntent]. The chat
 * places it under the timeline, inside the screen's insets, keyboard included.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun Composer(
    state: ComposerState,
    onIntent: (ComposerIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}
