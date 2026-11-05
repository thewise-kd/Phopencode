package io.github.thewisekd.phopencode.feature.chat.ui.timeline

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
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
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

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
    val turns: Map<TurnOutcome, String>,
    /** For the message `Rate limited`. */
    val turnFailedDetail: String,
    /** For `poll`. */
    val unknown: String,
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

    @Test
    fun `a prompt and a reply say who wrote them`() {
        val harness = compose.setTimeline(loaded(listOf(user(1))))
        shows(strings.you)

        harness.showOnly(answer(1, text = "", streaming = true))
        shows(strings.assistant)
        says(strings.writing)
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
    fun `the end of the work says how it ended`() {
        val harness = compose.setTimeline(loaded(emptyList()))
        assertEquals(TurnOutcome.entries.toSet(), strings.turns.keys)
        assertEquals(strings.turns.size, strings.turns.values.toSet().size, "messages must differ")

        for ((outcome, text) in strings.turns) {
            harness.showOnly(TimelineItem.TurnEnded(key, id, outcome))
            shows(text)
        }
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
