package io.github.thewisekd.phopencode.feature.chat.ui.palette

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteState

/**
 * Stub from the contract (P7.1); P7.9 draws the list that opens over the composer while a `/` or
 * `@` word is typed; a tap sends `PaletteIntent.Pick`. Draws [state], sends [onIntent]; draws
 * nothing while closed.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun CommandPalette(
    state: PaletteState,
    onIntent: (PaletteIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}
