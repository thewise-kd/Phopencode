package io.github.thewisekd.phopencode.feature.files.ui

import io.github.thewisekd.phopencode.core.navigation.FilesRoute
import io.github.thewisekd.phopencode.feature.files.logic.contract.ConnectionGitChanges
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorPresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileChanges
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileContent
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileListing
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsPresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileSearch
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileTransfers
import io.github.thewisekd.phopencode.feature.files.logic.contract.GitChanges
import io.github.thewisekd.phopencode.feature.files.logic.contract.NoFileService
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchPresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferPresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferQueue
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreePresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerPresenter
import io.github.thewisekd.phopencode.feature.files.logic.editor.DefaultEditorPresenter
import io.github.thewisekd.phopencode.feature.files.logic.ops.DefaultFileOpsPresenter
import io.github.thewisekd.phopencode.feature.files.logic.search.DefaultSearchPresenter
import io.github.thewisekd.phopencode.feature.files.logic.transfer.DefaultTransferPresenter
import io.github.thewisekd.phopencode.feature.files.logic.transfer.DefaultTransferQueue
import io.github.thewisekd.phopencode.feature.files.logic.tree.DefaultTreePresenter
import io.github.thewisekd.phopencode.feature.files.logic.viewer.DefaultViewerPresenter
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The files feature's definitions, which the app loads at startup. The connection manager and the
 * app's [CoroutineScope] come from the app's core modules.
 *
 * - The ports (`FilePorts.kt`) are single instances. The file ports are [NoFileService] until the
 *   file service of P8.1 (`core/files`) is wired in here, the one change this file still expects;
 *   [GitChanges] reads the active connection.
 * - [TransferQueue] is one for the app, on the app's scope, so transfers outlive their screen.
 * - Each presenter is a factory under its contract interface, built per destination with two
 *   parameters: the destination's route and its coroutine scope (see `rememberPresenter` in
 *   FilesGraph). The scope is a parameter, never `get()`: the graph's unqualified scope is the
 *   app's. The tree's destination builds both [TreePresenter] and [FileOpsPresenter] with the tree's
 *   route.
 */
val filesModule: Module = module {
    single<FileListing> { NoFileService }
    single<FileContent> { NoFileService }
    single<FileChanges> { NoFileService }
    single<FileTransfers> { NoFileService }
    single<FileSearch> { NoFileService }
    single<GitChanges> { ConnectionGitChanges(connection = get()) }
    single<TransferQueue> { DefaultTransferQueue(transfers = get(), scope = get()) }

    factory<TreePresenter> { (route: FilesRoute.Tree, scope: CoroutineScope) ->
        DefaultTreePresenter(directory = route.directory, files = get(), git = get(), scope = scope)
    }
    factory<FileOpsPresenter> { (route: FilesRoute.Tree, scope: CoroutineScope) ->
        DefaultFileOpsPresenter(directory = route.directory, files = get(), scope = scope)
    }
    factory<ViewerPresenter> { (route: FilesRoute.Viewer, scope: CoroutineScope) ->
        DefaultViewerPresenter(
            directory = route.directory,
            path = route.path,
            line = route.line,
            files = get(),
            scope = scope,
        )
    }
    factory<EditorPresenter> { (route: FilesRoute.Editor, scope: CoroutineScope) ->
        DefaultEditorPresenter(directory = route.directory, path = route.path, files = get(), scope = scope)
    }
    factory<SearchPresenter> { (route: FilesRoute.Search, scope: CoroutineScope) ->
        DefaultSearchPresenter(directory = route.directory, search = get(), scope = scope)
    }
    factory<TransferPresenter> { (route: FilesRoute.Transfer, scope: CoroutineScope) ->
        DefaultTransferPresenter(directory = route.directory, folder = route.path, queue = get(), scope = scope)
    }
}
