package io.github.thewisekd.phopencode.feature.git.ui.branch

import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import io.github.thewisekd.phopencode.core.navigation.GitRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchesState
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitBranch

/** Made-up paths and branch names: no real server layout. */
internal const val ROOT = "/work/demo proje"
internal const val HASH = "0123456789abcdef0123456789abcdef01234567"

internal val branchesRoute = GitRoute.Branches(ROOT)

internal val mainBranch = GitBranch(
    name = "main",
    isCurrent = true,
    upstream = "origin/main",
    ahead = 2,
    behind = 1,
    commit = HASH,
    subject = "Ana dal düzeltmesi",
)
internal val featureBranch = GitBranch(name = "feature/giriş", commit = HASH, subject = "Giriş ekranı")
internal val fixBranch = GitBranch(name = "fix/çıkış", upstream = "origin/fix/çıkış", commit = HASH)
internal val remoteMain = GitBranch(name = "origin/main", isRemote = true, commit = HASH)
internal val remoteFeature = GitBranch(name = "origin/feature/giriş", isRemote = true, commit = HASH)

internal val loadedBranches = BranchesState(
    directory = ROOT,
    loading = false,
    local = listOf(mainBranch, featureBranch, fixBranch),
    remote = listOf(remoteMain, remoteFeature),
)

internal fun ComposeContentTestRule.setBranchesScreen(state: BranchesState, onIntent: (BranchIntent) -> Unit = {}) {
    setContent {
        PhopencodeTheme(darkTheme = false) {
            Surface { BranchesScreen(route = branchesRoute, state = state, onIntent = onIntent) }
        }
    }
}

/** Content can be set once per rule: the returned holder swaps the state shown. */
internal fun ComposeContentTestRule.setSwappableBranchesScreen(
    state: BranchesState,
    onIntent: (BranchIntent) -> Unit = {},
): MutableState<BranchesState> {
    val holder = mutableStateOf(state)
    setContent {
        PhopencodeTheme(darkTheme = false) {
            Surface { BranchesScreen(route = branchesRoute, state = holder.value, onIntent = onIntent) }
        }
    }
    return holder
}

/** The list composes only the rows in view: scroll it until [matcher]'s node exists. */
internal fun ComposeContentTestRule.scrollBranchesTo(matcher: SemanticsMatcher) {
    onNodeWithTag(BranchTags.LIST).performScrollToNode(matcher)
}

/** The row of [branch]: its name is its headline, its tag says its section. */
internal fun rowOf(branch: GitBranch): SemanticsMatcher =
    hasTestTag(if (branch.isRemote) BranchTags.ROW_REMOTE else BranchTags.ROW_LOCAL) and hasText(branch.name)
