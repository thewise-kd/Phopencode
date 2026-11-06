package io.github.thewisekd.phopencode.feature.git.ui

import io.github.thewisekd.phopencode.core.navigation.GitRoute
import io.github.thewisekd.phopencode.feature.git.logic.branch.DefaultBranchesPresenter
import io.github.thewisekd.phopencode.feature.git.logic.commit.DefaultCommitPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchesPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.ConnectionGitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffTarget
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemotePresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusPresenter
import io.github.thewisekd.phopencode.feature.git.logic.diff.DefaultDiffPresenter
import io.github.thewisekd.phopencode.feature.git.logic.log.DefaultCommitDetailPresenter
import io.github.thewisekd.phopencode.feature.git.logic.log.DefaultLogPresenter
import io.github.thewisekd.phopencode.feature.git.logic.remote.DefaultRemotePresenter
import io.github.thewisekd.phopencode.feature.git.logic.status.DefaultStatusPresenter
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The git feature's definitions, which the app loads at startup. The connection manager comes from
 * the app's core modules.
 *
 * - [GitConnection] is a single instance on the connection manager: every presenter runs `git` on
 *   the connection that stands when a command starts.
 * - Each presenter is a factory under its contract interface, built per destination with two
 *   parameters: the destination's route and its coroutine scope (see `rememberPresenter` in
 *   GitGraph). The scope is a parameter, never `get()`: the graph binds no bare scope, and the
 *   app's singletons take the app's through a type of their own that no presenter is given as a
 *   parameter.
 *   [DiffPresenter] takes either diff route, `GitRoute.FileDiff` or `GitRoute.CommitFileDiff`.
 */
val gitModule: Module = module {
    single<GitConnection> { ConnectionGitConnection(connection = get()) }

    factory<StatusPresenter> { (route: GitRoute.Status, scope: CoroutineScope) ->
        DefaultStatusPresenter(directory = route.directory, git = get(), scope = scope)
    }
    factory<DiffPresenter> { (route: GitRoute, scope: CoroutineScope) ->
        when (route) {
            is GitRoute.FileDiff ->
                DefaultDiffPresenter(directory = route.directory, target = DiffTarget.of(route), git = get(), scope = scope)
            is GitRoute.CommitFileDiff ->
                DefaultDiffPresenter(directory = route.directory, target = DiffTarget.of(route), git = get(), scope = scope)
            else -> throw IllegalArgumentException("Not a diff route")
        }
    }
    factory<LogPresenter> { (route: GitRoute.Log, scope: CoroutineScope) ->
        DefaultLogPresenter(directory = route.directory, git = get(), scope = scope)
    }
    factory<CommitDetailPresenter> { (route: GitRoute.CommitDetail, scope: CoroutineScope) ->
        DefaultCommitDetailPresenter(directory = route.directory, commit = route.commit, git = get(), scope = scope)
    }
    factory<CommitPresenter> { (route: GitRoute.Commit, scope: CoroutineScope) ->
        DefaultCommitPresenter(directory = route.directory, git = get(), scope = scope)
    }
    factory<BranchesPresenter> { (route: GitRoute.Branches, scope: CoroutineScope) ->
        DefaultBranchesPresenter(directory = route.directory, git = get(), scope = scope)
    }
    factory<RemotePresenter> { (route: GitRoute.Remote, scope: CoroutineScope) ->
        DefaultRemotePresenter(directory = route.directory, git = get(), scope = scope)
    }
}
