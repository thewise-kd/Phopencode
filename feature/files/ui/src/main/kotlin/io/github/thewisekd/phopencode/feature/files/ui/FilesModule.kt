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
import io.github.thewisekd.phopencode.feature.files.logic.service.ConnectionFileService
import io.github.thewisekd.phopencode.feature.files.logic.search.DefaultSearchPresenter
import io.github.thewisekd.phopencode.feature.files.logic.transfer.DefaultTransferPresenter
import io.github.thewisekd.phopencode.feature.files.logic.transfer.DefaultTransferQueue
import io.github.thewisekd.phopencode.feature.files.logic.tree.DefaultTreePresenter
import io.github.thewisekd.phopencode.feature.files.logic.viewer.DefaultViewerPresenter
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The scope the app's one [TransferQueue] runs on: the app's own, which the app's core modules bind
 * here. A type of its own, so that Koin never answers it with a screen's [CoroutineScope], which
 * every presenter here takes as a parameter; nothing passes a [TransferQueueScope] as one.
 */
class TransferQueueScope(val scope: CoroutineScope)

/**
 * The files feature's definitions, which the app loads at startup. The connection manager and the
 * [TransferQueueScope] come from the app's core modules.
 *
 * - The ports (`FilePorts.kt`) are single instances. The five file ports are one
 *   [ConnectionFileService], the file service of `core/files` on the server of the active
 *   connection; [NoFileService] stays available for builds or tests without one. [GitChanges] reads
 *   the active connection.
 * - [TransferQueue] is one for the app, on the app's scope through [TransferQueueScope], so
 *   transfers outlive their screen, even the screen whose presenter first created the queue.
 * - Each presenter is a factory under its contract interface, built per destination with two
 *   parameters: the destination's route and its coroutine scope (see `rememberPresenter` in
 *   FilesGraph). The scope is a parameter, never `get()`, and the graph binds no bare
 *   [CoroutineScope]: a single that needs the app's scope asks for a wrapper type such as
 *   [TransferQueueScope], which no presenter is given as a parameter. The tree's destination builds both [TreePresenter] and [FileOpsPresenter] with the tree's
 *   route.
 */
val filesModule: Module = module {
    single { ConnectionFileService(connection = get()) }
    single<FileListing> { get<ConnectionFileService>() }
    single<FileContent> { get<ConnectionFileService>() }
    single<FileChanges> { get<ConnectionFileService>() }
    single<FileTransfers> { get<ConnectionFileService>() }
    single<FileSearch> { get<ConnectionFileService>() }
    single<GitChanges> { ConnectionGitChanges(connection = get()) }
    single<TransferQueue> { DefaultTransferQueue(transfers = get(), scope = get<TransferQueueScope>().scope) }

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
