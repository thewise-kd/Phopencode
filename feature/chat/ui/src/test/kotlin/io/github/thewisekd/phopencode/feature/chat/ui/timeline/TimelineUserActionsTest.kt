package io.github.thewisekd.phopencode.feature.chat.ui.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineItem
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * A long press on a delivered prompt opens its actions slot, which the chat fills with the prompt's
 * actions (P7.15); a prompt still waiting in the inbox has none. Nothing else of the timeline
 * changes.
 *
 * Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class TimelineUserActionsTest {
    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<TimelineItem.User>()

    private fun actionsTag(item: TimelineItem.User) = "test:actions:${item.key.value}"

    private fun setTimeline(state: TimelineState) {
        compose.setContent {
            PhopencodeTheme(darkTheme = false) {
                Surface {
                    Timeline(
                        state = state,
                        onIntent = {},
                        reasoningCard = {},
                        toolCard = {},
                        modifier = Modifier.fillMaxWidth().height(TIMELINE_HEIGHT),
                        userActions = { item, dismiss ->
                            opened += item
                            Box(Modifier.testTag(actionsTag(item))) {
                                TextButton(onClick = dismiss, modifier = Modifier.testTag("test:dismiss")) { Text("Close") }
                            }
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bubble() = compose.onNode(hasTestTag(TIMELINE_USER_BUBBLE_TAG), useUnmergedTree = true)

    private fun row() = compose.onNode(hasTestTag(TIMELINE_USER_ROW_TAG), useUnmergedTree = true)

    @Test
    fun `a long press on a prompt opens its actions, and they can be dismissed`() {
        val prompt = user(1, "Fix the build")
        setTimeline(loaded(listOf(prompt)))
        compose.onNodeWithTag(actionsTag(prompt)).assertDoesNotExist()

        bubble().performScrollTo().performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onNodeWithTag(actionsTag(prompt)).assertExists()
        assertEquals(prompt, opened.last())

        compose.onNodeWithTag("test:dismiss").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(actionsTag(prompt)).assertDoesNotExist()
    }

    @Test
    fun `the actions are offered to accessibility services as a long click`() {
        setTimeline(loaded(listOf(user(1))))

        row().assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
    }

    @Test
    fun `a prompt still waiting in the inbox has no actions`() {
        val waiting = user(2).copy(pending = PromptDelivery.Queue)
        setTimeline(loaded(listOf(waiting)))

        bubble().performScrollTo().performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onNodeWithTag(actionsTag(waiting)).assertDoesNotExist()
        row().assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
    }
}
