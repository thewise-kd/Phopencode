package io.github.thewisekd.phopencode.feature.sessions.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.test.core.app.ApplicationProvider
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme

// The same audit is in every feature UI module's tests: test sources cannot be shared without a
// test fixtures module. Keep the copies alike.

/** The largest font scale Android offers in its display settings. */
internal const val LARGE_FONT_SCALE = 2f

/** The least touch target, each way, the accessibility guidelines ask for. */
private val MIN_TOUCH_TARGET = 48.dp

/** Float rounding slack, the same as Compose's own touch size checks. */
private val TOUCH_TOLERANCE = 0.5.dp

/** Draws [content] in the app's theme at [fontScale], the largest text by default. */
internal fun ComposeContentTestRule.setThemedAt(fontScale: Float = LARGE_FONT_SCALE, content: @Composable () -> Unit) {
    setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            PhopencodeTheme(content = content)
        }
    }
}

/**
 * Checks what is on screen the way a screen reader user meets it, and fails with every problem at once:
 * - every control that can be tapped has something to read out: its text or a content description;
 * - every control has a touch area of at least 48 dp each way to itself (see [crowdedBy]);
 * - no text that may wrap is cut off at the bottom by a box too short for it.
 *
 * [narrowByDesign] names, by test tag, controls drawn under 48 dp on purpose next to others, which
 * the touch check leaves out; each caller says why.
 */
internal fun ComposeContentTestRule.assertAccessible(narrowByDesign: Set<String> = emptySet()) {
    waitForIdle()
    val problems = mutableListOf<String>()
    val controls = onAllNodes(hasClickAction() or isToggleable).fetchSemanticsNodes()
        .filterNot { it.boundsInRoot.isEmpty }
    controls.forEach { node ->
        if (node.label().isBlank()) problems += "a control has no text or content description: ${node.describe()}"
        if (node.config.getOrNull(SemanticsProperties.TestTag) in narrowByDesign) return@forEach
        node.crowdedBy(controls)?.let { other ->
            problems += "a control's touch target is under 48 dp, it runs into ${other.describe()}: ${node.describe()}"
        }
    }
    onAllNodes(hasTextLayout, useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
        val layout = node.textLayout() ?: return@forEach
        if (layout.layoutInput.maxLines == Int.MAX_VALUE && layout.didOverflowHeight) {
            problems += "a text is cut off: ${node.describe()}"
        }
    }
    if (problems.isNotEmpty()) throw AssertionError(problems.joinToString("\n"))
}

/**
 * Checks that the error showing [message] is on screen and read out by TalkBack when it shows: the node
 * showing it, or the node that merges it, is a live region. A live region elsewhere on the screen, such
 * as a status line, does not count, and every node on screen that shows [message] must be announced.
 * A node a scroll container holds out of view is scrolled to first, as a user would.
 */
internal fun ComposeContentTestRule.assertErrorAnnounced(message: String) {
    waitForIdle()
    val showsMessage = hasText(message, substring = true) or hasContentDescription(message, substring = true)
    val matching = onAllNodes(showsMessage, useUnmergedTree = true)
    repeat(matching.fetchSemanticsNodes().size) { index -> runCatching { matching[index].performScrollTo() } }
    val shown = matching
        .fetchSemanticsNodes()
        .filterNot { it.boundsInRoot.isEmpty }
    if (shown.isEmpty()) throw AssertionError("the error \"$message\" is not on screen")
    val silent = shown.filterNot { it.isAnnounced() }
    if (silent.isNotEmpty()) {
        throw AssertionError("the error is not in a live region: ${silent.joinToString { it.describe() }}")
    }
}

/** [assertErrorAnnounced] for the string resource [message], in the test's language. */
internal fun ComposeContentTestRule.assertErrorAnnounced(@StringRes message: Int) =
    assertErrorAnnounced(ApplicationProvider.getApplicationContext<Context>().getString(message))

/** Whether this node of the unmerged tree is a live region itself or is merged into one. */
private fun SemanticsNode.isAnnounced(): Boolean {
    if (SemanticsProperties.LiveRegion in config) return true
    val merging = generateSequence(parent) { it.parent }.firstOrNull { it.config.isMergingSemanticsOfDescendants }
    return merging != null && SemanticsProperties.LiveRegion in merging.config
}

private val isToggleable = SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)

private val hasTextLayout = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)

private fun SemanticsNode.label(): String {
    val config = config
    val parts = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
        listOfNotNull(config.getOrNull(SemanticsProperties.EditableText)?.text)
    return parts.joinToString(" ")
}

/**
 * The control that takes part of this one's touch area, when this one is drawn under 48 dp; null when
 * it is big enough or has the room around it to itself.
 *
 * Compose widens the touch area of anything tappable that is drawn smaller to 48 dp, as Material's
 * icon buttons rely on: a 40 dp button in a 48 dp slot. That only helps while the widened area is
 * free: where it runs into another control's, a tap there may go to either, so the target is smaller.
 * Nested controls, a button in a tappable row, are not compared, nor are controls drawn over each
 * other, as a floating button over a list: scrolling moves them apart. Controls a scroll container
 * hides entirely are left out before.
 */
private fun SemanticsNode.crowdedBy(controls: List<SemanticsNode>): SemanticsNode? {
    val shown = layoutArea() ?: return null
    val area = touchArea() ?: return null
    if (area == shown) return null
    val root = root()
    return controls.firstOrNull { other ->
        val otherShown = other.layoutArea()
        other.id != id && other.root().id == root.id && !isRelatedTo(other) &&
            otherShown != null && !shown.overlaps(otherShown) &&
            other.touchArea()?.let { area.overlaps(it) } == true
    }
}

/** Where the control is laid out, not cut by a scroll container, shrunk by the slack so touching edges do not count. */
private fun SemanticsNode.layoutArea(): Rect? {
    if (size.width == 0 || size.height == 0) return null
    val slack = with(layoutInfo.density) { TOUCH_TOLERANCE.toPx() }
    return Rect(positionInRoot, size.toSize()).deflate(slack)
}

/** [layoutArea], widened about its centre to 48 dp each way where it is smaller. */
private fun SemanticsNode.touchArea(): Rect? {
    val shown = layoutArea() ?: return null
    val minimum = with(layoutInfo.density) { (MIN_TOUCH_TARGET - TOUCH_TOLERANCE * 2).toPx() }
    val center = shown.center
    val halfWidth = maxOf(shown.width, minimum) / 2
    val halfHeight = maxOf(shown.height, minimum) / 2
    return Rect(center.x - halfWidth, center.y - halfHeight, center.x + halfWidth, center.y + halfHeight)
}

private fun SemanticsNode.root(): SemanticsNode = generateSequence(this) { it.parent }.last()

private fun SemanticsNode.isRelatedTo(other: SemanticsNode): Boolean =
    generateSequence(parent) { it.parent }.any { it.id == other.id } ||
        generateSequence(other.parent) { it.parent }.any { it.id == id }

private fun SemanticsNode.textLayout(): TextLayoutResult? {
    val layouts = mutableListOf<TextLayoutResult>()
    config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
    return layouts.firstOrNull()
}

private fun SemanticsNode.describe(): String =
    "\"${label().take(60)}\" (${config.getOrNull(SemanticsProperties.TestTag) ?: "no tag"}) at $boundsInRoot"
