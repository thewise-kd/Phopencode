package io.github.thewisekd.phopencode.feature.chat.ui.subagent

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the badge, header and list send and show, in English: the texts of both languages are in `SubagentLanguageContract`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class SubagentBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val placeholder = "placeholder:$LIST_ROUTE"

    // The badge

    @Test
    fun `the badge draws nothing without subagents, keeping only its empty slot`() {
        val harness = compose.setBadge(SubagentState())
        assertEmptySlot()

        harness.state.value = SubagentState(loading = false)
        assertEmptySlot()

        harness.state.value = LOADED
        compose.onNodeWithTag(BADGE_TAG).assertHasClickAction().assertTextEquals("2 running")
    }

    private fun assertEmptySlot() {
        val node = compose.onNodeWithTag(BADGE_TAG).assertHasNoClickAction().fetchSemanticsNode()
        assertNull(node.config.getOrNull(SemanticsProperties.Text))
        assertEquals(0f, node.size.width.toFloat())
    }

    @Test
    fun `tapping the badge sends OpenList and nothing else`() {
        val harness = compose.setBadge(LOADED)

        compose.onNodeWithTag(BADGE_TAG).performScrollTo().performClick()

        assertEquals(listOf<SubagentIntent>(SubagentIntent.OpenList), harness.intents)
    }

    @Test
    fun `the badge is big enough to tap`() {
        compose.setBadge(LOADED)

        compose.onNodeWithTag(BADGE_TAG).performScrollTo().assertTouchSizeIsAtLeast(48.dp)
    }

    @Test
    fun `the badge is live only while a subagent runs`() {
        val harness = compose.setBadge(LOADED)
        compose.onNodeWithTag(SubagentTags.LIVE_DOT, useUnmergedTree = true).assertExists()

        harness.state.value = LOADED.copy(subagents = TREE.map { it.ended() })
        compose.onNodeWithTag(SubagentTags.LIVE_DOT, useUnmergedTree = true).assertDoesNotExist()
        val node = compose.onNodeWithTag(BADGE_TAG).fetchSemanticsNode()
        assertNull(node.config.getOrNull(SemanticsProperties.StateDescription))
    }

    @Test
    fun `the badge counts a nested running subagent`() {
        val nestedOnly = listOf(TREE[0].copy(status = TREE[1].status), TREE[1])
        compose.setBadge(LOADED.copy(subagents = nestedOnly))

        compose.onNodeWithText("1 running").assertIsDisplayed()
    }

    // The header

    @Test
    fun `going back sends OpenParent and nothing else`() {
        val harness = compose.setHeader(CHILD)

        compose.onNodeWithTag(SubagentTags.HEADER_BACK).performScrollTo().performClick()

        assertEquals(listOf<SubagentIntent>(SubagentIntent.OpenParent), harness.intents)
    }

    @Test
    fun `the way back is big enough to tap`() {
        compose.setHeader(CHILD)

        compose.onNodeWithTag(SubagentTags.HEADER_BACK).performScrollTo().assertTouchSizeIsAtLeast(48.dp)
    }

    @Test
    fun `there is no way back before the parent is known`() {
        val harness = compose.setHeader(SubagentState())
        compose.onNodeWithTag(SubagentTags.HEADER_BACK).assertDoesNotExist()

        harness.state.value = CHILD.copy(parentId = null)
        compose.onNodeWithTag(SubagentTags.HEADER_BACK).assertDoesNotExist()
    }

    @Test
    fun `the header opens its own subagents only when it has some`() {
        val harness = compose.setHeader(CHILD)

        compose.onNodeWithTag(SubagentTags.HEADER_LIST).performScrollTo().performClick()
        assertEquals(listOf<SubagentIntent>(SubagentIntent.OpenList), harness.intents)

        harness.state.value = CHILD.copy(subagents = emptyList())
        compose.onNodeWithTag(SubagentTags.HEADER_LIST).assertDoesNotExist()
    }

    @Test
    fun `the header title is a heading`() {
        compose.setHeader(CHILD)

        val node = compose.onNodeWithText(EXPLORE_TITLE).fetchSemanticsNode()
        assertNotNull(node.config.getOrNull(SemanticsProperties.Heading))
    }

    @Test
    fun `a failed header offers a retry that sends Retry`() {
        val harness = compose.setHeader(CHILD.copy(error = ChatFailure.Unreachable))

        compose.onNodeWithText(EXPLORE_TITLE).assertIsDisplayed()
        compose.onNodeWithText("Try again").performScrollTo().performClick()

        assertEquals(listOf<SubagentIntent>(SubagentIntent.Retry), harness.intents)
    }

    // The list

    @Test
    fun `the list keeps its route's tag in every state`() {
        val harness = compose.setList(SubagentState())
        compose.onNodeWithTag(placeholder).assertIsDisplayed()

        listOf(LOADED, SubagentState(loading = false), LOADED.copy(error = ChatFailure.Unreachable)).forEach {
            harness.state.value = it
            compose.onNodeWithTag(placeholder).assertIsDisplayed()
        }
    }

    @Test
    fun `the list title is a heading`() {
        compose.setList(LOADED)

        val node = compose.onNodeWithText("Subagents").fetchSemanticsNode()
        assertNotNull(node.config.getOrNull(SemanticsProperties.Heading))
    }

    @Test
    fun `leaving the list sends Close and nothing else`() {
        val harness = compose.setList(LOADED)

        compose.onNodeWithContentDescription("Go back").performClick()

        assertEquals(listOf<SubagentIntent>(SubagentIntent.Close), harness.intents)
    }

    @Test
    fun `tapping a subagent at any depth opens its timeline`() {
        val harness = compose.setList(LOADED)

        listOf(EXPLORE, NESTED, REVIEW).forEach {
            compose.onNodeWithTag(SubagentTags.row(it)).performScrollTo().performClick()
        }

        assertEquals(listOf(EXPLORE, NESTED, REVIEW).map<SessionId, SubagentIntent> { SubagentIntent.Open(it) }, harness.intents)
    }

    @Test
    fun `a nested subagent sits under its parent, further in`() {
        compose.setList(LOADED)

        val parent = compose.onNodeWithTag(SubagentTags.row(EXPLORE)).performScrollTo().fetchSemanticsNode()
        val nested = compose.onNodeWithTag(SubagentTags.row(NESTED)).performScrollTo().fetchSemanticsNode()
        val next = compose.onNodeWithTag(SubagentTags.row(REVIEW)).performScrollTo().fetchSemanticsNode()
        val parentText = compose.onNodeWithText(EXPLORE_TITLE, useUnmergedTree = true).fetchSemanticsNode()
        val nestedText = compose.onNodeWithText(NESTED_TITLE, useUnmergedTree = true).fetchSemanticsNode()

        assertTrue(nested.boundsInRoot.top >= parent.boundsInRoot.bottom)
        assertTrue(next.boundsInRoot.top >= nested.boundsInRoot.bottom)
        assertTrue(nestedText.boundsInRoot.left > parentText.boundsInRoot.left)
    }

    @Test
    fun `every row is big enough to tap`() {
        compose.setList(LOADED)

        listOf(EXPLORE, NESTED, REVIEW).forEach {
            compose.onNodeWithTag(SubagentTags.row(it)).performScrollTo().assertTouchSizeIsAtLeast(48.dp)
        }
    }

    @Test
    fun `retry sends Retry and the loaded rows still show`() {
        val harness = compose.setList(LOADED.copy(error = ChatFailure.Unreachable))

        listOf(EXPLORE, NESTED, REVIEW).forEach {
            compose.onNodeWithTag(SubagentTags.row(it)).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("Try again").performScrollTo().performClick()

        assertEquals(listOf<SubagentIntent>(SubagentIntent.Retry), harness.intents)
    }

    @Test
    fun `no error shows without a failure`() {
        compose.setList(LOADED)

        compose.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun `a row leaves out what is not known`() {
        compose.setList(LOADED.copy(subagents = listOf(TREE[0].children[0].copy(duration = null, agent = null))))

        compose.onNodeWithTag(SubagentTags.row(NESTED)).performScrollTo()
            .assertTextEquals(NESTED_TITLE, "Running")
        compose.onNodeWithTag(SubagentTags.SUMMARY).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the list follows the state as it changes`() {
        val harness = compose.setList(LOADED)
        compose.onNodeWithTag(SubagentTags.row(SessionId("ses_9"))).assertDoesNotExist()

        harness.state.value = LOADED.copy(subagents = TREE + TREE[1].copy(sessionId = SessionId("ses_9")))

        compose.onNodeWithTag(SubagentTags.row(SessionId("ses_9"))).performScrollTo().assertIsDisplayed()
    }
}
