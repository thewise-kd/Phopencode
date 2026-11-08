package io.github.thewisekd.phopencode.feature.catalog.ui.skills

import androidx.compose.material3.Surface
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.model.Skill
import io.github.thewisekd.phopencode.core.navigation.CatalogRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsState

/** Made-up skills: no real project, path or text. */
internal val review = Skill(
    id = "review",
    name = "Code review",
    description = "Reviews a change for bugs and style.",
    autoInvoke = true,
    path = "/work/sample/.opencode/skill/review/SKILL.md",
    content = "# Review steps\n\nRead the *whole* diff first.\n\n- Check the tests\n- Check the names\n",
)

internal val release = Skill(
    id = "release",
    name = "Release notes",
    description = null,
    path = "/work/sample/.opencode/skill/release/SKILL.md",
    content = "Write the notes.",
)

internal val listedSkills = SkillsState(loading = false, skills = listOf(review, release), shown = listOf(review, release))

internal const val SHORT_SOURCE = "~/.opencode/skill/review/SKILL.md"

internal val loadedSkill = SkillDetailState(skillId = review.id, loading = false, skill = review, sourcePath = SHORT_SOURCE)

/** The failures after which reading again can help. */
internal val RETRYABLE: Set<CatalogFailure> =
    CatalogFailure.entries.toSet() - setOf(CatalogFailure.NotConnected, CatalogFailure.Unauthorized)

/** How long a test waits for Markdown, which parses off the main thread. */
internal const val WAIT_MS = 5_000L

internal fun ComposeContentTestRule.setSkills(state: SkillsState, onIntent: (SkillsIntent) -> Unit = {}) {
    setContent {
        PhopencodeTheme(darkTheme = false) {
            Surface { SkillsScreen(route = CatalogRoute.Skills, state = state, onIntent = onIntent) }
        }
    }
}

internal fun ComposeContentTestRule.setSkillDetail(state: SkillDetailState, onIntent: (SkillDetailIntent) -> Unit = {}) {
    setContent {
        PhopencodeTheme(darkTheme = false) {
            Surface {
                SkillDetailScreen(route = CatalogRoute.SkillDetail(state.skillId), state = state, onIntent = onIntent)
            }
        }
    }
}

/** Waits until some node shows [text], as Markdown parses off the main thread. */
internal fun ComposeContentTestRule.awaitText(text: String) {
    waitUntil(WAIT_MS) {
        onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
}

/** The smallest size of anything tappable, as `PhopencodeSpacing.touchTarget`. */
internal val TOUCH_TARGET = 48.dp

/** Float rounding slack, the same as Compose's own `assertTouchHeightIsEqualTo`. */
private val TOUCH_SIZE_TOLERANCE = 0.5.dp

/** Asserts the node's touch bounds are at least [minimum] in both directions. */
internal fun SemanticsNodeInteraction.assertTouchSizeIsAtLeast(minimum: Dp = TOUCH_TARGET): SemanticsNodeInteraction {
    val node = fetchSemanticsNode("Failed to check the touch size.")
    val bounds: Rect = node.touchBoundsInRoot
    val (width, height) = with(node.layoutInfo.density) { bounds.width.toDp() to bounds.height.toDp() }
    if (width < minimum - TOUCH_SIZE_TOLERANCE || height < minimum - TOUCH_SIZE_TOLERANCE) {
        throw AssertionError("Actual touch size is $width x $height, expected at least $minimum")
    }
    return this
}
