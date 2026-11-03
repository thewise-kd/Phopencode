package io.github.thewisekd.phopencode.core.session.reducer

import io.github.thewisekd.phopencode.core.api.EventMeta
import io.github.thewisekd.phopencode.core.model.SessionId
import kotlin.time.Instant

/**
 * When an event was written, for deciding whether it may replace a value another event wrote.
 *
 * The session's durable `seq` is the server's own order, so it decides when both events have one.
 * Otherwise the server's `created` time decides. With neither, the later arrival wins.
 */
internal class Stamp private constructor(private val seq: Long?, private val createdAt: Instant?) {
    /** True when this is older than [current], so a value [current] wrote must stay. */
    fun isOlderThan(current: Stamp?): Boolean {
        if (current == null) return false
        return (compareWith(current) ?: return false) < 0
    }

    /** Negative, zero or positive as this is older, level or newer; null when nothing orders the two. */
    fun compareWith(other: Stamp): Int? = when {
        seq != null && other.seq != null -> seq.compareTo(other.seq)
        createdAt != null && other.createdAt != null -> createdAt.compareTo(other.createdAt)
        else -> null
    }

    companion object {
        /** A point known only by its time, such as a message from a fetched list. */
        fun ofTime(at: Instant): Stamp = Stamp(null, at)

        /** A `seq` counts only for this session's own sequence. */
        fun of(meta: EventMeta, sessionId: SessionId): Stamp {
            val seq = meta.durable?.takeIf { it.aggregateId == sessionId.value }?.seq
            return Stamp(seq, meta.createdAt)
        }
    }
}

/**
 * The events already applied, so that a repeated one is skipped. Keyed on the event ID, else on the
 * durable position. An event with neither cannot be told from a new one and is always applied.
 *
 * Holds the most recent [capacity] keys: a repeat comes soon after the original, from a reconnect or a
 * resent batch, and a session can see far more events than are worth keeping.
 */
internal class SeenEvents(private val capacity: Int = DEFAULT_CAPACITY) {
    private val keys = LinkedHashSet<String>()

    /** Records the event; false when it was already recorded. */
    fun firstTime(meta: EventMeta): Boolean {
        val key = keyOf(meta) ?: return true
        if (!keys.add(key)) return false
        if (keys.size > capacity) keys.remove(keys.first())
        return true
    }

    private fun keyOf(meta: EventMeta): String? =
        meta.id?.let { "id:$it" } ?: meta.durable?.let { "seq:${it.aggregateId}:${it.seq}" }

    companion object {
        const val DEFAULT_CAPACITY = 8_192
    }
}

/** Earliest of two times, either of which may be unknown. */
internal fun earliest(first: Instant?, second: Instant?): Instant? = when {
    first == null -> second
    second == null -> first
    else -> minOf(first, second)
}

/** Sorts on a time, unknown times last, then on [arrival]. */
internal fun <T> orderOf(time: (T) -> Instant?, arrival: (T) -> Long): Comparator<T> =
    compareBy<T, Instant?>(nullsLast()) { time(it) }.thenBy { arrival(it) }
