package io.github.thewisekd.phopencode.feature.files.ui.ops

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsState

/**
 * Stub from the contract (P8.2); P8.11 draws the file operations over the tree: the context menu of
 * an entry, the rename and new folder dialogs with their name problems, the delete confirmation,
 * and the error of a failed operation. Draws nothing while [state] has nothing open. Draws [state],
 * sends [onIntent].
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun FileOpsLayer(
    state: FileOpsState,
    onIntent: (FileOpsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}
