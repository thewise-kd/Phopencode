package io.github.thewisekd.phopencode.feature.chat.ui.timeline

import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TurnOutcome
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishTimelineTest : TimelineLanguageContract() {
    override val strings = ExpectedTimelineStrings(
        loading = "Loading messages…",
        emptyTitle = "No messages yet",
        emptyMessage = "Write a message below to start.",
        loadingOlder = "Loading older messages…",
        olderFailed = "Older messages could not be loaded.",
        retry = "Try again",
        jumpToLatest = "Jump to the latest message",
        unseenOne = "1 new message",
        unseenFive = "5 new messages",
        you = "You",
        assistant = "Assistant",
        writing = "Writing…",
        pending = mapOf(
            PromptDelivery.Steer to "Waiting to steer the running work",
            PromptDelivery.Queue to "Queued until the work ends",
        ),
        files = "Files: a.kt, b.kt",
        agents = "Agents: plan",
        skills = "Skills: review",
        answerFailed = "The answer failed: Rate limited",
        shellLabel = "Shell command",
        shellRunning = "Running…",
        compactionRunning = "Compacting the conversation…",
        compactionDone = "The conversation was compacted.",
        compactionFailed = "Compacting the conversation failed.",
        agentSwitched = "Agent: build",
        agentSwitchedFrom = "Agent changed: plan → build",
        modelSwitched = "Model: openai/gpt",
        modelSwitchedFrom = "Model changed: anthropic/claude → openai/gpt",
        locationSwitched = "Folder: /home/dev/app",
        skillInvoked = "Skill used: review",
        turns = mapOf(
            TurnOutcome.Succeeded to "The work is done.",
            TurnOutcome.Failed to "The work failed.",
            TurnOutcome.Interrupted to "The work was stopped.",
            TurnOutcome.Unknown to "The work ended.",
        ),
        turnFailedDetail = "The work failed: Rate limited",
        unknown = "This message type is not supported yet (poll).",
    )
}
