package io.github.thewisekd.phopencode.feature.chat.ui.subagent

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentNode
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentStatus
import io.github.thewisekd.phopencode.feature.chat.ui.R
import java.math.BigInteger
import java.text.NumberFormat
import java.util.Locale
import kotlin.time.Duration

private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L
private const val SECONDS_PER_HOUR = SECONDS_PER_MINUTE * MINUTES_PER_HOUR

private val INT_MAX: BigInteger = BigInteger.valueOf(Int.MAX_VALUE.toLong())

/** The language the screen is drawn in, which sets the number format. */
@Composable
internal fun subagentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

/** A count with the language's grouping, such as "1,234" in English and "1.234" in Turkish. */
internal fun formatCount(value: Long, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(value)

internal fun formatCount(value: BigInteger, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(value)

/**
 * The badge's text: how many run, such as "2 running", while any do, else how many there are at any
 * depth, such as "3 subagents".
 */
@Composable
internal fun countText(state: SubagentState, locale: Locale): String {
    val running = state.runningCount
    return if (running > 0) {
        pluralStringResource(R.plurals.chat_subagent_running, running, formatCount(running.toLong(), locale))
    } else {
        totalText(state, locale)
    }
}

/** How many subagents there are at any depth, such as "3 subagents". */
@Composable
internal fun totalText(state: SubagentState, locale: Locale): String {
    val total = state.totalCount
    return pluralStringResource(R.plurals.chat_subagent_count, total, formatCount(total.toLong(), locale))
}

/** The parts joined as the language joins a line's parts, such as "a · b · c"; null for none. */
@Composable
internal fun joined(parts: List<String>): String? {
    if (parts.isEmpty()) return null
    val join = stringResource(R.string.chat_subagent_join)
    val locale = subagentLocale()
    return parts.reduce { line, next -> join.format(locale, line, next) }
}

/**
 * Whole seconds, rounded down, in the language's units: "45 s", "2 min 5 s", and from an hour on
 * hours and minutes only, "1 h 3 min", as seconds no longer matter there.
 */
@Composable
internal fun durationText(duration: Duration, locale: Locale): String {
    val total = duration.inWholeSeconds.coerceAtLeast(0)
    val hours = total / SECONDS_PER_HOUR
    val minutes = total % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val seconds = total % SECONDS_PER_MINUTE
    val h = formatCount(hours, locale)
    val m = formatCount(minutes, locale)
    val s = formatCount(seconds, locale)
    return when {
        hours > 0 && minutes == 0L -> stringResource(R.string.chat_subagent_hours, h)
        hours > 0 -> stringResource(R.string.chat_subagent_hours_minutes, h, m)
        minutes == 0L -> stringResource(R.string.chat_subagent_seconds, s)
        seconds == 0L -> stringResource(R.string.chat_subagent_minutes, m)
        else -> stringResource(R.string.chat_subagent_minutes_seconds, m, s)
    }
}

/** What a row calls the subagent: its title, else its agent, else "Untitled subagent". */
@Composable
internal fun nodeName(title: String?, agent: String?): String =
    title?.takeIf { it.isNotBlank() }
        ?: agent?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.chat_subagent_untitled)

/** The agent, when the title names the subagent, its duration and its tokens: "explore · 2 min 5 s · 1,234 tokens". */
@Composable
internal fun metaText(node: SubagentNode, locale: Locale): String? {
    val parts = buildList {
        if (!node.title.isNullOrBlank()) node.agent?.takeIf { it.isNotBlank() }?.let(::add)
        node.duration?.let { add(durationText(it, locale)) }
        node.usage?.totalTokens?.takeIf { it.signum() > 0 }?.let {
            add(pluralStringResource(R.plurals.chat_subagent_tokens, it.min(INT_MAX).toInt(), formatCount(it, locale)))
        }
    }
    return joined(parts)
}

/** The tool calls so far: "5 tool calls · 1 failed · Now: bash"; null before the first. */
@Composable
internal fun toolsText(node: SubagentNode, locale: Locale): String? {
    val tools = node.tools
    if (tools.total <= 0) return null
    val parts = buildList {
        add(pluralStringResource(R.plurals.chat_subagent_tools, tools.total, formatCount(tools.total.toLong(), locale)))
        if (tools.failed > 0) add(stringResource(R.string.chat_subagent_tools_failed, formatCount(tools.failed.toLong(), locale)))
        tools.current?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.chat_subagent_tool_current, it)) }
    }
    return joined(parts)
}

@StringRes
internal fun SubagentStatus.labelRes(): Int = when (this) {
    SubagentStatus.Running -> R.string.chat_subagent_status_running
    SubagentStatus.Succeeded -> R.string.chat_subagent_status_succeeded
    SubagentStatus.Failed -> R.string.chat_subagent_status_failed
    SubagentStatus.Interrupted -> R.string.chat_subagent_status_interrupted
    SubagentStatus.Idle -> R.string.chat_subagent_status_idle
}

/** Why the subagents could not be loaded, as a sentence. */
@StringRes
internal fun ChatFailure.reasonRes(): Int = when (this) {
    ChatFailure.NotConnected -> R.string.chat_subagent_reason_not_connected
    ChatFailure.Unreachable -> R.string.chat_subagent_reason_unreachable
    ChatFailure.Unauthorized -> R.string.chat_subagent_reason_unauthorized
    ChatFailure.NotFound -> R.string.chat_subagent_reason_not_found
    ChatFailure.Conflict -> R.string.chat_subagent_reason_conflict
    ChatFailure.Rejected -> R.string.chat_subagent_reason_rejected
    ChatFailure.ServerFailed -> R.string.chat_subagent_reason_server
    ChatFailure.Unknown -> R.string.chat_subagent_reason_unknown
}

/** The error line for [failure], with its reason. */
@Composable
internal fun errorText(failure: ChatFailure): String =
    stringResource(R.string.chat_subagent_error, stringResource(failure.reasonRes()))
