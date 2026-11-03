package io.github.thewisekd.phopencode.core.session.reducer

import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.SessionId
import kotlin.time.Instant

/**
 * A compaction message, from its delivery, its start, its failure or a fetched list.
 *
 * `session.compaction.started` and `.failed` name their compaction; `.delta` and `.ended` do not, so
 * [CompactionLedger] places those by `seq` and time.
 */
internal class CompactionDraft(val id: MessageId, private val sessionId: SessionId) {
    var seeded: Message.Compaction? = null
    var deliveredAt: Instant? = null
    var deliveredStamp: Stamp? = null
    var startedAt: Instant? = null
    var startStamp: Stamp? = null
    var reason: String? = null

    /** The failure, which names its compaction; an older one never replaces it. */
    var failure: ServerEvent.CompactionFailed? = null
    var failureStamp: Stamp? = null

    /** The list times a compaction at its delivery. */
    val createdAt: Instant?
        get() = seeded?.createdAt ?: deliveredAt ?: startedAt

    /** Where it began, against which an unnamed delta or end is placed. Null when nothing says. */
    val start: Stamp?
        get() = startStamp ?: deliveredStamp ?: seeded?.createdAt?.let(Stamp::ofTime)

    val failed: Boolean
        get() = failure != null || seeded?.status == STATUS_FAILED

    /** Already ended in the list it was seeded from, so no event can end it again. */
    private val seededEnded: Boolean
        get() = seeded?.status == STATUS_COMPLETED || seeded?.status == STATUS_FAILED

    /** As a message, with an [end], [live] text and an [unnamedFailure] matched to it, if any. */
    fun toMessage(
        end: ServerEvent.CompactionEnded?,
        live: String?,
        unnamedFailure: ServerEvent.CompactionFailed?,
    ): Message.Compaction {
        val base = (seeded ?: Message.Compaction(id, sessionId)).copy(createdAt = createdAt)
        val reason = reason ?: base.reason
        val failure = failure ?: unnamedFailure
        return when {
            failure != null -> base.copy(
                status = STATUS_FAILED,
                reason = failure.reason ?: reason,
                summary = null,
                usage = failure.usage,
                error = failure.error,
            )
            seededEnded -> base.copy(reason = reason)
            end != null -> base.copy(
                status = STATUS_COMPLETED,
                reason = end.reason ?: reason,
                model = end.model ?: base.model,
                summary = end.text ?: base.summary ?: live,
                usage = end.usage,
            )
            startStamp != null || base.status == STATUS_RUNNING ->
                base.copy(status = STATUS_RUNNING, reason = reason, summary = base.summary ?: live)
            else -> base.copy(reason = reason)
        }
    }

    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
    }
}

/**
 * The compaction events that do not name their compaction: deltas, ends, and failures without an
 * `inputID`. Each belongs to the compaction that began last at or before it, by durable `seq`, else
 * by time, since a session runs one compaction at a time.
 *
 * The match is made again whenever the result is read, so it does not depend on arrival order: an
 * event older than every known compaction waits for its own to appear. The conservative rules, for
 * what the data cannot decide:
 *
 * - If the event cannot be ordered against some known compaction (no `seq` or time in common), or
 *   two compactions began at the same point, it is matched to none.
 * - An end or failure whose compaction already failed is dropped, never moved to another one.
 * - Of several ends matched to one compaction, the latest wins.
 * - An event matched to a compaction a revert removed is dropped with it, never given to another.
 */
internal class CompactionLedger {
    private class Placed<T>(val stamp: Stamp, val at: Instant?, val event: T)

    private val ends = ArrayList<Placed<ServerEvent.CompactionEnded>>()
    private val deltas = ArrayList<Placed<String>>()
    private val failures = ArrayList<Placed<ServerEvent.CompactionFailed>>()

    fun end(stamp: Stamp, event: ServerEvent.CompactionEnded) {
        ends += Placed(stamp, event.meta.createdAt, event)
    }

    fun delta(stamp: Stamp, at: Instant?, text: String) {
        deltas += Placed(stamp, at, text)
    }

    fun unnamedFailure(stamp: Stamp, event: ServerEvent.CompactionFailed) {
        failures += Placed(stamp, event.meta.createdAt, event)
    }

    /**
     * Each compaction of [drafts] as a message, with the unnamed events matched to it. Events that match
     * one of [gone] are dropped.
     */
    fun messages(
        drafts: Collection<CompactionDraft>,
        gone: Collection<CompactionDraft> = emptyList(),
    ): Map<MessageId, Message.Compaction> {
        val placed = (drafts + gone).filter { it.start != null }
        val goneIds = gone.mapTo(HashSet()) { it.id }
        fun owner(stamp: Stamp): CompactionDraft? = owner(placed, stamp)?.takeIf { it.id !in goneIds }
        val failedHere = HashMap<MessageId, ServerEvent.CompactionFailed>()
        for (failure in failures) {
            val target = owner(failure.stamp) ?: continue
            if (target.failure == null && target.id !in failedHere) failedHere[target.id] = failure.event
        }
        val endOf = HashMap<MessageId, Placed<ServerEvent.CompactionEnded>>()
        for (end in ends) {
            val target = owner(end.stamp) ?: continue
            if (target.failed || target.id in failedHere) continue
            val current = endOf[target.id]
            if (current == null || current.stamp.compareWith(end.stamp)?.let { it < 0 } == true) endOf[target.id] = end
        }
        val liveOf = HashMap<MessageId, TimedText>()
        for (delta in deltas) {
            val target = owner(delta.stamp) ?: continue
            liveOf.getOrPut(target.id, ::TimedText).add(delta.at, delta.event)
        }
        return drafts.associate { draft ->
            draft.id to draft.toMessage(endOf[draft.id]?.event, liveOf[draft.id]?.text, failedHere[draft.id])
        }
    }

    /** The compaction that began last at or before [stamp]; null when that cannot be decided. */
    private fun owner(drafts: List<CompactionDraft>, stamp: Stamp): CompactionDraft? {
        var best: CompactionDraft? = null
        var tied = false
        for (draft in drafts) {
            val start = draft.start ?: continue
            val order = start.compareWith(stamp) ?: return null
            if (order > 0) continue
            val current = best
            if (current == null) {
                best = draft
                continue
            }
            when (start.compareWith(current.start!!) ?: return null) {
                0 -> tied = true
                in 1..Int.MAX_VALUE -> {
                    best = draft
                    tied = false
                }
            }
        }
        return if (tied) null else best
    }
}
