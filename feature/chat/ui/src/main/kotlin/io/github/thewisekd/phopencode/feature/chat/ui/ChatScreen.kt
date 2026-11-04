package io.github.thewisekd.phopencode.feature.chat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineKey
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageState
import io.github.thewisekd.phopencode.feature.chat.ui.composer.Composer
import io.github.thewisekd.phopencode.feature.chat.ui.reasoning.ReasoningCard
import io.github.thewisekd.phopencode.feature.chat.ui.status.StatusBanner
import io.github.thewisekd.phopencode.feature.chat.ui.timeline.Timeline
import io.github.thewisekd.phopencode.feature.chat.ui.tool.ToolCard
import io.github.thewisekd.phopencode.feature.chat.ui.usage.UsageBar

/** The tags of the chat's slots, for tests: each part's root carries its slot's tag. */
object ChatSlotTags {
    const val USAGE: String = "chat:slot:usage"
    const val STATUS: String = "chat:slot:status"
    const val TIMELINE: String = "chat:slot:timeline"
    const val COMPOSER: String = "chat:slot:composer"

    fun reasoning(key: TimelineKey): String = "chat:slot:reasoning:${key.value}"

    fun tool(key: TimelineKey): String = "chat:slot:tool:${key.value}"
}

/**
 * The chat: places each part, drawn by its own task, in its slot. Decides nothing; every part draws
 * its presenter's state and sends that presenter's intents.
 *
 * From top to bottom: the usage bar, the status banner, the timeline, the composer, all inside the
 * screen's insets, keyboard included, so the composer stays above it. When [StatusState.blocksChat]
 * holds, as for a session the active server does not have, the status takes the place of the
 * others.
 *
 * In the timeline, a reasoning or tool item draws the card of its key from [reasoning] or [tool];
 * a key without a card draws nothing.
 *
 * The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun ChatScreen(
    route: ChatRoute.Chat,
    status: StatusState,
    onStatusIntent: (StatusIntent) -> Unit,
    usage: UsageState,
    onUsageIntent: (UsageIntent) -> Unit,
    timeline: TimelineState,
    onTimelineIntent: (TimelineIntent) -> Unit,
    reasoning: ReasoningState,
    onReasoningIntent: (ReasoningIntent) -> Unit,
    tool: ToolState,
    onToolIntent: (ToolIntent) -> Unit,
    composer: ComposerState,
    onComposerIntent: (ComposerIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route")) {
        if (status.blocksChat) {
            StatusBanner(status, onStatusIntent, Modifier.fillMaxWidth().weight(1f).testTag(ChatSlotTags.STATUS))
        } else {
            UsageBar(usage, onUsageIntent, Modifier.fillMaxWidth().testTag(ChatSlotTags.USAGE))
            StatusBanner(status, onStatusIntent, Modifier.fillMaxWidth().testTag(ChatSlotTags.STATUS))
            Timeline(
                state = timeline,
                onIntent = onTimelineIntent,
                reasoningCard = { item ->
                    reasoning.cards[item.key]?.let { card ->
                        ReasoningCard(item.key, card, onReasoningIntent, Modifier.testTag(ChatSlotTags.reasoning(item.key)))
                    }
                },
                toolCard = { item ->
                    tool.cards[item.key]?.let { card ->
                        ToolCard(item.key, card, onToolIntent, Modifier.testTag(ChatSlotTags.tool(item.key)))
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f).testTag(ChatSlotTags.TIMELINE),
            )
            Composer(composer, onComposerIntent, Modifier.fillMaxWidth().testTag(ChatSlotTags.COMPOSER))
        }
    }
}
