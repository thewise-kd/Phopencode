package io.github.thewisekd.phopencode.feature.files.ui.ops

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileAction
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsDialog
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsRequest
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsState

/** Made-up paths: no real server layout or user name. */
internal const val OPS_ROOT = "/work/demo proje"
internal const val OPS_FOLDER = "$OPS_ROOT/src"

internal val opsFile = FileEntry(path = "$OPS_FOLDER/ana kod.kt", type = FileEntryType.File, sizeBytes = 120)
internal val opsFolder = FileEntry(path = "$OPS_FOLDER/çizimler", type = FileEntryType.Directory)

internal val fileMenu = FileOpsState(
    menu = FileOpsRequest.Actions(opsFile, OPS_FOLDER),
    menuActions = FileAction.entries,
)
internal val folderMenu = FileOpsState(
    menu = FileOpsRequest.Actions(opsFolder, OPS_FOLDER),
    menuActions = listOf(FileAction.Rename, FileAction.Delete),
)
internal val renaming = FileOpsState(dialog = FileOpsDialog.Rename(opsFile, name = opsFile.name))
internal val newFolder = FileOpsState(dialog = FileOpsDialog.NewFolder(OPS_FOLDER))
internal val deletingFile = FileOpsState(dialog = FileOpsDialog.ConfirmDelete(opsFile))
internal val deletingFolder = FileOpsState(dialog = FileOpsDialog.ConfirmDelete(opsFolder))

/** The layer in the app theme, with [state] changeable by the test and every intent recorded. */
internal class FileOpsHarness(initial: FileOpsState) {
    val state: MutableState<FileOpsState> = mutableStateOf(initial)
    val intents = mutableListOf<FileOpsIntent>()

    @Composable
    fun Content() {
        PhopencodeTheme(darkTheme = false) {
            FileOpsLayer(state = state.value, onIntent = { intents += it })
        }
    }
}

internal fun ComposeContentTestRule.setFileOps(state: FileOpsState): FileOpsHarness {
    val harness = FileOpsHarness(state)
    setContent { harness.Content() }
    return harness
}

/** Whether some node carries [tag]. */
internal fun ComposeContentTestRule.hasTag(tag: String): Boolean =
    onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
