package io.github.thewisekd.phopencode.feature.chat.logic.timeline

import io.github.thewisekd.phopencode.core.api.InboxContent
import io.github.thewisekd.phopencode.core.api.InboxItem
import io.github.thewisekd.phopencode.core.model.AssistantBlock
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.SessionOutcome
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineItem
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineKey
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineNotice
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TurnOutcome

/**
 * The items of [messages], then of the [inbox] items not yet in [messages], oldest first, following
 * the rules of [TimelineItem]. A key seen once is not used again, so that a list keyed by it never
 * holds two items under one key: an inbox item already delivered, or a tool call the server
 * repeated, gives nothing the second time.
 */
internal fun timelineItems(messages: List<Message>, inbox: List<InboxItem>): List<TimelineItem> {
    val items = ArrayList<TimelineItem>(messages.size + inbox.size)
    val keys = HashSet<TimelineKey>()
    fun add(item: TimelineItem) {
        if (keys.add(item.key)) items += item
    }
    messages.forEach { message -> itemsOf(message, ::add) }
    inbox.forEach { waiting -> itemOf(waiting)?.let(::add) }
    return items
}

private fun itemsOf(message: Message, add: (TimelineItem) -> Unit) {
    val id = message.id
    val key = TimelineKey.message(id)
    when (message) {
        is Message.User -> add(
            TimelineItem.User(
                key = key,
                messageId = id,
                text = message.text,
                files = message.files,
                agents = message.agents,
                skills = message.skills,
                createdAt = message.createdAt,
            ),
        )
        is Message.Assistant -> {
            message.blocks.forEachIndexed { index, block ->
                when (block) {
                    is AssistantBlock.Text -> add(
                        TimelineItem.AssistantText(
                            key = TimelineKey.text(id, index),
                            messageId = id,
                            text = block.text,
                            streaming = block.completedAt == null && message.completedAt == null,
                        ),
                    )
                    is AssistantBlock.Reasoning -> add(TimelineItem.Reasoning(TimelineKey.reasoning(id, index), id))
                    is AssistantBlock.Tool -> add(
                        TimelineItem.Tool(TimelineKey.tool(id, block.id), id, callId = block.id, name = block.name),
                    )
                    is AssistantBlock.Unknown -> Unit
                }
            }
            message.error?.let { add(TimelineItem.AssistantError(key, id, it)) }
        }
        is Message.Shell -> add(TimelineItem.Shell(key, id, message.command, message.output))
        is Message.Compaction -> add(TimelineItem.Compaction(key, id, message.status, message.summary))
        is Message.Idle -> add(TimelineItem.TurnEnded(key, id, outcomeOf(message.outcome), message.error))
        is Message.AgentSwitched -> add(TimelineItem.Notice(key, id, TimelineNotice.AgentSwitched(message.agent, message.previous)))
        is Message.ModelSwitched -> add(TimelineItem.Notice(key, id, TimelineNotice.ModelSwitched(message.model, message.previous)))
        is Message.LocationSwitched -> add(TimelineItem.Notice(key, id, TimelineNotice.LocationSwitched(message.directory)))
        is Message.SkillInvoked -> add(TimelineItem.Notice(key, id, TimelineNotice.SkillInvoked(message.name)))
        is Message.Synthetic -> add(TimelineItem.Notice(key, id, TimelineNotice.Synthetic(message.text)))
        is Message.System -> add(TimelineItem.Notice(key, id, TimelineNotice.System(message.text)))
        is Message.Unknown -> add(TimelineItem.Unknown(key, id, message.rawType))
    }
}

/**
 * A prompt waiting in the inbox gives a pending [TimelineItem.User] under the key its message will
 * have. Other inbox items are not prompts: they show as the message they will become, without a
 * pending mark, since only [TimelineItem.User] carries one.
 */
private fun itemOf(waiting: InboxItem): TimelineItem? {
    val id = waiting.id
    val key = TimelineKey.message(id)
    return when (val content = waiting.content) {
        is InboxContent.User -> TimelineItem.User(
            key = key,
            messageId = id,
            text = content.text,
            files = content.files,
            agents = content.agents,
            skills = content.skills,
            createdAt = waiting.createdAt,
            pending = waiting.delivery,
        )
        is InboxContent.Synthetic -> TimelineItem.Notice(key, id, TimelineNotice.Synthetic(content.text))
        InboxContent.Compaction -> TimelineItem.Compaction(key, id)
        is InboxContent.Unknown -> TimelineItem.Unknown(key, id, content.rawType)
    }
}

private fun outcomeOf(outcome: SessionOutcome?): TurnOutcome = when (outcome) {
    SessionOutcome.Succeeded -> TurnOutcome.Succeeded
    SessionOutcome.Failed -> TurnOutcome.Failed
    SessionOutcome.Interrupted -> TurnOutcome.Interrupted
    is SessionOutcome.Unknown, null -> TurnOutcome.Unknown
}
