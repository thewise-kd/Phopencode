package io.github.thewisekd.phopencode.feature.chat.ui.subagent

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentNode
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentStatus
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The texts of the subagent badge, header and list in one language. */
internal data class ExpectedSubagentStrings(
    /** The badge for [LOADED]: 2 running. */
    val badgeRunning: String,
    /** The badge for 3 subagents, none running. */
    val badgeTotal: String,
    /** The badge for 1 subagent, not running. */
    val badgeOne: String,
    val badgeOpen: String,
    val live: String,
    val back: String,
    /** The header's own subagents for [CHILD]: 1 running. */
    val headerList: String,
    val headerLoading: String,
    val untitled: String,
    val title: String,
    /** The summary above the list for [LOADED]. */
    val summary: String,
    val rowOpen: String,
    /** Each row of [TREE], its texts in order. */
    val rows: Map<SessionId, List<String>>,
    val statuses: Map<SubagentStatus, String>,
    val durations: Map<Duration, String>,
    val loading: String,
    val emptyTitle: String,
    val errors: Map<ChatFailure, String>,
    val retry: String,
)

/** What the badge, header and list say, the same in each language; a subclass sets the language and its texts. */
abstract class SubagentLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedSubagentStrings

    @Test
    fun `the badge counts the running subagents, else all of them`() {
        val harness = compose.setBadge(LOADED)
        val badge = compose.onNodeWithTag(BADGE_TAG).performScrollTo()
        badge.assertTextEquals(strings.badgeRunning)
        val node = badge.fetchSemanticsNode()
        assertEquals(strings.badgeOpen, node.config.getOrNull(SemanticsActions.OnClick)?.label)
        assertEquals(strings.live, node.config.getOrNull(SemanticsProperties.StateDescription))

        harness.state.value = LOADED.copy(subagents = TREE.map { it.ended() })
        compose.onNodeWithTag(BADGE_TAG).performScrollTo().assertTextEquals(strings.badgeTotal)

        harness.state.value = LOADED.copy(subagents = listOf(TREE[1]))
        compose.onNodeWithTag(BADGE_TAG).performScrollTo().assertTextEquals(strings.badgeOne)
    }

    @Test
    fun `the header names the session, its agent and the way back`() {
        val harness = compose.setHeader(CHILD)

        compose.onNodeWithText(EXPLORE_TITLE).assertIsDisplayed()
        compose.onNodeWithText("explore").assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.back).assertIsDisplayed()
        compose.onNodeWithTag(SubagentTags.HEADER_LIST).performScrollTo().assertTextEquals(strings.headerList)

        harness.state.value = CHILD.copy(title = null)
        compose.onNodeWithText(strings.untitled).assertIsDisplayed()

        harness.state.value = SubagentState()
        compose.onNodeWithText(strings.headerLoading).assertIsDisplayed()
    }

    @Test
    fun `the list sums up and each row shows status, duration, tokens and tools in the language's format`() {
        compose.setList(LOADED)

        compose.onNodeWithText(strings.title).assertIsDisplayed()
        compose.onNodeWithTag(SubagentTags.SUMMARY).performScrollTo().assertTextEquals(strings.summary)
        strings.rows.forEach { (id, texts) ->
            val row = compose.onNodeWithTag(SubagentTags.row(id)).performScrollTo()
            row.assertTextEquals(*texts.toTypedArray())
            assertEquals(strings.rowOpen, row.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label)
        }
    }

    @Test
    fun `every status has its own label`() {
        val harness = compose.setList(LOADED)
        strings.statuses.forEach { (status, label) ->
            harness.state.value = LOADED.copy(subagents = listOf(SubagentNode(EXPLORE, title = EXPLORE_TITLE, status = status)))
            compose.onNodeWithTag(SubagentTags.row(EXPLORE)).performScrollTo().assertTextEquals(EXPLORE_TITLE, label)
        }
        kotlin.test.assertEquals(SubagentStatus.entries.toSet(), strings.statuses.keys)
    }

    @Test
    fun `durations are written in the language's units`() {
        val harness = compose.setList(LOADED)
        strings.durations.forEach { (duration, shown) ->
            harness.state.value = LOADED.copy(
                subagents = listOf(SubagentNode(EXPLORE, title = EXPLORE_TITLE, status = SubagentStatus.Succeeded, duration = duration)),
            )
            compose.onNodeWithTag(SubagentTags.row(EXPLORE)).performScrollTo()
                .assertTextEquals(EXPLORE_TITLE, strings.statuses.getValue(SubagentStatus.Succeeded), shown)
        }
        assertEquals(SAMPLE_DURATIONS.toSet(), strings.durations.keys)
    }

    @Test
    fun `a row without a title is named by its agent, else as untitled`() {
        val harness = compose.setList(LOADED.copy(subagents = listOf(SubagentNode(EXPLORE, agent = "explore"))))
        compose.onNodeWithTag(SubagentTags.row(EXPLORE)).performScrollTo()
            .assertTextEquals("explore", strings.statuses.getValue(SubagentStatus.Idle))

        harness.state.value = LOADED.copy(subagents = listOf(SubagentNode(EXPLORE)))
        compose.onNodeWithTag(SubagentTags.row(EXPLORE)).performScrollTo()
            .assertTextEquals(strings.untitled, strings.statuses.getValue(SubagentStatus.Idle))
    }

    @Test
    fun `the list says when it is loading or has nothing yet`() {
        val harness = compose.setList(SubagentState())
        compose.onNodeWithText(strings.loading).performScrollTo().assertIsDisplayed()

        harness.state.value = SubagentState(loading = false)
        compose.onNodeWithText(strings.emptyTitle).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `every failure has its own message and a retry`() {
        val harness = compose.setList(LOADED.copy(error = ChatFailure.Unknown))
        strings.errors.forEach { (failure, message) ->
            harness.state.value = LOADED.copy(error = failure)
            compose.onNodeWithText(message).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText(strings.retry).performScrollTo().assertIsDisplayed()
        kotlin.test.assertEquals(ChatFailure.entries.toSet(), strings.errors.keys)
    }

    private fun assertEquals(expected: Any?, actual: Any?) = kotlin.test.assertEquals(expected, actual)
}

/** This node and its own, ended. */
internal fun SubagentNode.ended(): SubagentNode =
    copy(status = SubagentStatus.Succeeded, children = children.map { it.ended() })

/** Durations every language writes, each to the text the subclass gives. */
internal val SAMPLE_DURATIONS: List<Duration> =
    listOf(0.seconds, 45.seconds, 2.minutes, 125.seconds, 1.hours, 63.minutes, 1.hours + 30.seconds, 1_000.hours)
