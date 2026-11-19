package io.github.thewisekd.phopencode.feature.chat.ui.timeline

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineItem
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineKey
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineNotice
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TurnOutcome
import io.github.thewisekd.phopencode.feature.chat.ui.actions.assertTouchSizeIsAtLeast
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Rule
import org.junit.Test

/** The texts of the timeline in one language, each with its arguments filled in by the test. */
internal data class ExpectedTimelineStrings(
    val loading: String,
    val emptyTitle: String,
    val emptyMessage: String,
    val loadingOlder: String,
    val olderFailed: String,
    val retry: String,
    val jumpToLatest: String,
    /** For 1 and 5 unseen items. */
    val unseenOne: String,
    val unseenFive: String,
    val you: String,
    val assistant: String,
    val writing: String,
    val pending: Map<PromptDelivery, String>,
    /** For `a.kt, b.kt`, `plan` and `review`. */
    val files: String,
    val agents: String,
    val skills: String,
    /** For the message `Rate limited`. */
    val answerFailed: String,
    val shellLabel: String,
    val shellRunning: String,
    val compactionRunning: String,
    val compactionDone: String,
    val compactionFailed: String,
    /** For `build`, then `plan` to `build`. */
    val agentSwitched: String,
    val agentSwitchedFrom: String,
    /** For `openai/gpt`, then `anthropic/claude` to `openai/gpt`. */
    val modelSwitched: String,
    val modelSwitchedFrom: String,
    /** For `/home/dev/app`. */
    val locationSwitched: String,
    /** For `review`. */
    val skillInvoked: String,
    /** The ends that draw a chip: every outcome but [TurnOutcome.Succeeded], which draws nothing. */
    val turns: Map<TurnOutcome, String>,
    /** For the message `Rate limited`. */
    val turnFailedDetail: String,
    /** For `poll`. */
    val unknown: String,
    /** The label of a finished reply's Copy button. */
    val copyAnswer: String,
    /** The short notice after a copy, on Android 12L and below. */
    val copied: String,
)

/** What the timeline shows, the same in each language; a subclass sets the language and its texts. */
abstract class TimelineLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedTimelineStrings

    private val id = MessageId("msg_1")
    private val key = TimelineKey.message(id)

    private fun shows(text: String) = compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()

    private fun says(text: String) = compose.onNodeWithContentDescription(text).performScrollTo().assertIsDisplayed()

    private fun TimelineHarness.showOnly(item: TimelineItem) {
        state.value = loaded(listOf(item))
        compose.waitForIdle()
    }

    private fun notice(notice: TimelineNotice) = TimelineItem.Notice(key, id, notice)

    @Test
    fun `loading says so`() {
        compose.setTimeline(TimelineState())

        says(strings.loading)
    }

    @Test
    fun `an empty session says so`() {
        compose.setTimeline(TimelineState(loading = false))

        shows(strings.emptyTitle)
        shows(strings.emptyMessage)
    }

    @Test
    fun `older items say when they load or failed`() {
        val harness = compose.setTimeline(loaded(prompts(3), followLatest = false).copy(hasOlder = true, loadingOlder = true))
        says(strings.loadingOlder)

        harness.state.value = harness.state.value.copy(loadingOlder = false, olderError = ChatFailure.Unreachable)
        compose.waitForIdle()

        shows(strings.olderFailed)
        shows(strings.retry)
    }

    @Test
    fun `the jump button counts what is unseen`() {
        val harness = compose.setTimeline(loaded(prompts(3), followLatest = false))
        shows(strings.jumpToLatest)

        harness.state.value = harness.state.value.copy(unseenCount = 1)
        compose.waitForIdle()
        shows(strings.unseenOne)

        harness.state.value = harness.state.value.copy(unseenCount = 5)
        compose.waitForIdle()
        shows(strings.unseenFive)
    }

    /** Nothing on screen reads [text]: a caption no longer drawn. */
    private fun hides(text: String) = compose.assertNotDrawn(text)

    /** Screen readers hear [author] first in the item that holds [text]. */
    private fun spokenFirst(author: String, text: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(author, compose.spokenTexts(text).firstOrNull(), "who wrote the item holding $text")
    }

    @Test
    fun `a prompt and a reply tell screen readers who wrote them, with no caption on screen`() {
        val harness = compose.setTimeline(loaded(listOf(user(1, "Testleri çalıştır"))))
        spokenFirst(strings.you, "Testleri çalıştır")
        hides(strings.you)

        harness.showOnly(answer(1, text = "Done."))
        spokenFirst(strings.assistant, "Done.")
        hides(strings.assistant)

        harness.showOnly(answer(1, text = "", streaming = true))
        says(strings.writing)
        hides(strings.assistant)
    }

    @Test
    fun `a reply that fills the screen still says who wrote it first`() {
        val harness = compose.setTimeline(loaded(emptyList()))

        harness.showOnly(answer(1, text = streamed(40)))

        spokenFirst(strings.assistant, "Paragraph 1 of the reply")
        hides(strings.assistant)
    }

    @Test
    fun `a finished reply copies the whole reply and says so`() {
        val whole = "# Plan\n\nFirst **step**  \n\n```kotlin\nval x = 1\n```\n"
        val harness = compose.setTimeline(loaded(listOf(answer(1, text = "Last block.", whole = whole))))

        val copy = compose.onNodeWithContentDescription(strings.copyAnswer).performScrollTo()
        copy.assertIsDisplayed().assertHasClickAction().assertTouchSizeIsAtLeast(48.dp)
        // A node of its own: the reply's merged "Assistant" text does not hold it.
        val reply = compose.onAllNodes(hasText("Last block.", substring = true)).fetchSemanticsNodes().first()
        assertFalse(strings.copyAnswer in reply.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty())
        assertEquals(strings.assistant, compose.spokenTexts("Last block.").first())

        copy.performClick()
        compose.waitForIdle()

        assertEquals(whole, clipboardText())
        assertCopiedNotice(strings.copied)

        harness.showOnly(answer(2, text = "Still part of a reply."))
        compose.onNodeWithContentDescription(strings.copyAnswer).assertDoesNotExist()
    }

    @Test
    fun `a prompt shows what goes with it and how it waits`() {
        val prompt = user(1).copy(files = listOf("a.kt", "b.kt"), agents = listOf("plan"), skills = listOf("review"))
        val harness = compose.setTimeline(loaded(listOf(prompt)))
        shows(strings.files)
        shows(strings.agents)
        shows(strings.skills)

        assertEquals(PromptDelivery.entries.toSet(), strings.pending.keys)
        for ((delivery, text) in strings.pending) {
            harness.showOnly(user(1).copy(pending = delivery))
            shows(text)
        }
    }

    @Test
    fun `a failed reply says why`() {
        val harness = compose.setTimeline(loaded(emptyList()))

        harness.showOnly(TimelineItem.AssistantError(key, id, StructuredError("provider.rate", message = "Rate limited")))

        shows(strings.answerFailed)
    }

    @Test
    fun `a shell command shows its command and output`() {
        val harness = compose.setTimeline(loaded(emptyList()))

        harness.showOnly(TimelineItem.Shell(key, id, command = "ls -la"))
        shows(strings.shellLabel)
        shows("ls -la")
        says(strings.shellRunning)

        harness.showOnly(TimelineItem.Shell(key, id, command = "ls -la", output = "README.md"))
        shows("README.md")
    }

    @Test
    fun `a compaction says how it went`() {
        val harness = compose.setTimeline(loaded(emptyList()))

        harness.showOnly(TimelineItem.Compaction(key, id, status = "running"))
        says(strings.compactionRunning)

        harness.showOnly(TimelineItem.Compaction(key, id, status = "completed"))
        shows(strings.compactionDone)

        harness.showOnly(TimelineItem.Compaction(key, id, status = "failed"))
        shows(strings.compactionFailed)
    }

    @Test
    fun `notices say what changed`() {
        val harness = compose.setTimeline(loaded(emptyList()))
        val gpt = ModelRef(providerId = "openai", modelId = "gpt")
        val claude = ModelRef(providerId = "anthropic", modelId = "claude")
        val cases = listOf(
            TimelineNotice.AgentSwitched("build") to strings.agentSwitched,
            TimelineNotice.AgentSwitched("build", previous = "plan") to strings.agentSwitchedFrom,
            TimelineNotice.ModelSwitched(gpt) to strings.modelSwitched,
            TimelineNotice.ModelSwitched(gpt, previous = claude) to strings.modelSwitchedFrom,
            TimelineNotice.LocationSwitched("/home/dev/app") to strings.locationSwitched,
            TimelineNotice.SkillInvoked("review") to strings.skillInvoked,
            TimelineNotice.Synthetic("Command output here") to "Command output here",
            TimelineNotice.System("A system note") to "A system note",
        )

        for ((notice, text) in cases) {
            harness.showOnly(notice(notice))
            shows(text)
        }
    }

    @Test
    fun `the end of the work says how it ended, unless it succeeded`() {
        val harness = compose.setTimeline(loaded(emptyList()))
        assertEquals(TurnOutcome.entries.toSet() - TurnOutcome.Succeeded, strings.turns.keys)
        assertEquals(strings.turns.size, strings.turns.values.toSet().size, "messages must differ")

        for ((outcome, text) in strings.turns) {
            harness.showOnly(TimelineItem.TurnEnded(key, id, outcome))
            shows(text)
        }
        harness.showOnly(TimelineItem.TurnEnded(key, id, TurnOutcome.Succeeded))
        strings.turns.values.forEach { hides(it) }
        harness.showOnly(
            TimelineItem.TurnEnded(key, id, TurnOutcome.Failed, StructuredError("provider.rate", message = "Rate limited")),
        )
        shows(strings.turnFailedDetail)
    }

    @Test
    fun `an unknown message says it is not supported`() {
        val harness = compose.setTimeline(loaded(emptyList()))

        harness.showOnly(TimelineItem.Unknown(key, id, rawType = "poll"))

        shows(strings.unknown)
    }
}
