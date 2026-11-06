package io.github.thewisekd.phopencode.feature.git.ui.remote

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.core.navigation.GitRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitBranchStatus
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitRemote
import io.github.thewisekd.phopencode.feature.git.logic.contract.PushPlan
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteState

/** Made-up paths, remotes and branch names: no real server layout. */
internal const val ROOT = "/work/demo proje"
internal const val HASH = "0123456789abcdef0123456789abcdef01234567"

internal val route = GitRoute.Remote(ROOT)

internal val origin = GitRemote("origin", fetchUrl = "https://example.invalid/demo.git")
internal val upstreamRemote = GitRemote("upstream", fetchUrl = "https://example.invalid/fork.git")

internal val branch = GitBranchStatus(head = "özellik", commit = HASH, upstream = "origin/özellik", ahead = 3, behind = 1)

internal val loaded = RemoteState(
    directory = ROOT,
    loading = false,
    remotes = listOf(origin),
    selected = "origin",
    branch = branch,
)

internal val twoRemotes = loaded.copy(remotes = listOf(origin, upstreamRemote))

internal val plan = PushPlan(remote = "origin", branch = "özellik", ahead = 3, setUpstream = false)

internal fun ComposeContentTestRule.setRemoteScreen(state: RemoteState, onIntent: (RemoteIntent) -> Unit = {}) {
    setContent {
        PhopencodeTheme(darkTheme = false) {
            Surface { RemoteScreen(route = route, state = state, onIntent = onIntent) }
        }
    }
}

/**
 * Content can be set once per rule: this holder swaps the state shown, for tests that walk through
 * many states.
 */
internal class RemoteStates(private val compose: ComposeContentTestRule, private val onIntent: (RemoteIntent) -> Unit = {}) {
    private val shown = mutableStateOf<RemoteState?>(null)
    private var started = false

    fun show(state: RemoteState) {
        shown.value = state
        if (!started) {
            started = true
            compose.setContent {
                PhopencodeTheme(darkTheme = false) {
                    Surface { shown.value?.let { RemoteScreen(route = route, state = it, onIntent = onIntent) } }
                }
            }
        }
        compose.waitForIdle()
    }
}

/**
 * The screen's content is a plain scrolling column that composes all its parts: scroll it until
 * [matcher]'s node, which must lie inside it, is in view.
 */
internal fun ComposeContentTestRule.scrollRemoteTo(matcher: SemanticsMatcher) {
    onNode(matcher and hasAnyAncestor(hasTestTag(RemoteTags.LIST))).performScrollTo()
}

internal fun inDialog(text: String, substring: Boolean = false): SemanticsMatcher =
    hasText(text, substring = substring) and hasAnyAncestor(isDialog())
