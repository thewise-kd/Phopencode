package io.github.thewisekd.phopencode.feature.files.ui.tree

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.navigation.FilesRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.files.logic.contract.GitBadge
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeEntry
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeState

/** Made-up paths: no real server layout or user name. */
internal const val ROOT = "/work/demo proje"
internal const val ROOT_NAME = "demo proje"
internal const val SRC = "$ROOT/src"
internal const val MAIN = "$SRC/main"

internal val route = FilesRoute.Tree(directory = ROOT)

internal val docsFolder = TreeEntry(FileEntry(path = "$ROOT/docs", type = FileEntryType.Directory), GitBadge.ContainsChanges)
internal val buildFolder = TreeEntry(FileEntry(path = "$ROOT/build", type = FileEntryType.Directory, ignored = true))
internal val readme = TreeEntry(FileEntry(path = "$ROOT/README.md", type = FileEntryType.File, sizeBytes = 2048), GitBadge.Modified)
internal val newFile = TreeEntry(FileEntry(path = "$ROOT/notes.txt", type = FileEntryType.File), GitBadge.Untracked)
internal val link = TreeEntry(FileEntry(path = "$ROOT/current", type = FileEntryType.Symlink))
internal val socket = TreeEntry(FileEntry(path = "$ROOT/agent.sock", type = FileEntryType.Unknown("socket")))

internal val listing = TreeState(
    directory = ROOT,
    loading = false,
    entries = listOf(docsFolder, buildFolder, readme, newFile, link, socket),
)

internal fun file(index: Int): TreeEntry =
    TreeEntry(FileEntry(path = "$ROOT/file-$index.txt", type = FileEntryType.File, sizeBytes = index.toLong()))

/** A large folder: [count] files, named so their order is their index. */
internal fun largeFolder(count: Int): TreeState =
    TreeState(directory = ROOT, loading = false, entries = List(count) { file(it) })

@Composable
internal fun TreeContent(state: TreeState, onIntent: (TreeIntent) -> Unit) {
    PhopencodeTheme(darkTheme = false) {
        Surface { TreeScreen(route = route, state = state, onIntent = onIntent) }
    }
}

internal fun ComposeContentTestRule.setTreeScreen(state: TreeState, onIntent: (TreeIntent) -> Unit = {}) {
    setContent { TreeContent(state, onIntent) }
}
