package io.github.thewisekd.phopencode.feature.chat.ui.form

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormCardState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormIntent

/**
 * Stub from the contract (P7.1); P7.3 draws draws one pending form: its fields as radio buttons,
 * checkboxes, "your own answer", text, number and yes/no inputs, one step at a time for a question,
 * and its submit and decline. Draws [card], sends [onIntent] for it.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun FormCard(
    card: FormCardState,
    onIntent: (FormIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}
