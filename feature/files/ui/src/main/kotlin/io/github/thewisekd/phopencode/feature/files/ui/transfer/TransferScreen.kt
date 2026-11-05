package io.github.thewisekd.phopencode.feature.files.ui.transfer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.thewisekd.phopencode.core.navigation.FilesRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferState

/**
 * Stub from the contract (P8.2); P8.13 draws the screen: the queue with progress and cancel, the
 * overwrite prompt, and the system pickers (open documents to upload, create a document for
 * `TransferState.pendingDownload`). Draws [state], sends [onIntent]. The root keeps the
 * `placeholder:<route>` tag the app's navigation test looks for.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun TransferScreen(
    route: FilesRoute.Transfer,
    state: TransferState,
    onIntent: (TransferIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
