package io.github.thewisekd.phopencode.feature.chat.ui.status

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.core.ui.R as CoreUiR
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.core.ui.theme.StatusColor
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatStatus
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusState
import io.github.thewisekd.phopencode.feature.chat.ui.R
import java.util.Date
import kotlin.time.Instant

/** The banner shown while the connection is down. */
const val STATUS_DISCONNECTED_TAG: String = "chat:status:disconnected"

/** The compact line of running or retrying work. */
const val STATUS_ACTIVITY_TAG: String = "chat:status:activity"

/** The error of the last execution. */
const val STATUS_FAILED_TAG: String = "chat:status:failed"

/** The status that takes the whole chat. */
const val STATUS_BLOCKING_TAG: String = "chat:status:blocking"

const val STATUS_RECONNECT_TAG: String = "chat:status:reconnect"
const val STATUS_CLOSE_TAG: String = "chat:status:close"

/** The provider's retry text is free and may be long; the line keeps it short. */
private const val PROVIDER_TEXT_MAX_LINES = 2

/**
 * The status of the chat (P6.14). Draws [state], sends [onIntent]; the presenter decides everything.
 *
 * Two sizes. As a slim tinted strip above the timeline (an icon, one line, its action): nothing
 * while idle; a still line while the work runs or retries, with the attempt, the time of the next
 * try and the provider's text when given; the last execution's error; and, while the connection is
 * down, a strip with Reconnect, which goes once the status is back to running or idle. When
 * [StatusState.blocksChat] holds, the chat gives the status its whole place through [modifier], and
 * it fills it in the style of core/ui's `EmptyState`, an icon in a soft circle above the text: a
 * still loading message, or a message with Close, and Reconnect when remaking the standing
 * connection can help.
 *
 * Nothing animates: running work is a still label rather than core/ui's `Loading`, whose spinner
 * never stops, and the app's ActivityScenario tests would never come to rest.
 */
@Composable
fun StatusBanner(
    state: StatusState,
    onIntent: (StatusIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val status = state.status) {
        ChatStatus.Idle -> Box(modifier)
        ChatStatus.Running -> ActivityLine(
            message = stringResource(R.string.chat_status_running),
            icon = PhopencodeIcons.Sparkle,
            modifier = modifier,
        )
        is ChatStatus.Retrying -> RetryLine(status, modifier)
        is ChatStatus.Failed -> FailedBanner(status.error, modifier)
        ChatStatus.Disconnected -> DisconnectedBanner(onIntent, modifier)
        ChatStatus.Loading -> Blocking(modifier) {
            IconCircle(PhopencodeIcons.Chat)
            Text(
                text = stringResource(R.string.chat_status_loading),
                modifier = Modifier.semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                    liveRegion = LiveRegionMode.Polite
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        // The connection has ended, and Reconnect remakes only a standing one: the way back is the
        // server list.
        ChatStatus.NotConnected -> BlockingMessage(
            title = stringResource(R.string.chat_status_not_connected_title),
            icon = PhopencodeIcons.Server,
            message = stringResource(R.string.chat_status_not_connected_message),
            canReconnect = false,
            onIntent = onIntent,
            modifier = modifier,
        )
        ChatStatus.NotFound -> BlockingMessage(
            title = stringResource(R.string.chat_status_not_found_title),
            icon = PhopencodeIcons.Search,
            message = stringResource(R.string.chat_status_not_found_message),
            canReconnect = false,
            onIntent = onIntent,
            modifier = modifier,
        )
        // Reconnecting would reach the same other server: only leaving helps.
        ChatStatus.OtherServer -> BlockingMessage(
            title = stringResource(R.string.chat_status_other_server_title),
            icon = PhopencodeIcons.Server,
            message = stringResource(R.string.chat_status_other_server_message),
            canReconnect = false,
            onIntent = onIntent,
            modifier = modifier,
        )
        is ChatStatus.Unavailable -> BlockingMessage(
            title = stringResource(R.string.chat_status_unavailable_title),
            icon = PhopencodeIcons.Warning,
            message = stringResource(status.failure.reasonRes()),
            canReconnect = status.failure.reconnectHelps(),
            onIntent = onIntent,
            modifier = modifier,
        )
    }
}

/**
 * A slim strip in [tone]'s soft container: an [icon] that only decorates, then [content] on the
 * line next to it, and the strip's [action] at its end.
 */
@Composable
private fun Strip(
    tone: StatusColor,
    icon: ImageVector,
    modifier: Modifier,
    stripModifier: Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val spacing = PhopencodeTheme.spacing
    Box(modifier.padding(horizontal = spacing.md, vertical = spacing.xs)) {
        Surface(
            modifier = Modifier.fillMaxWidth().then(stripModifier),
            color = tone.container,
            contentColor = tone.onContainer,
            shape = MaterialTheme.shapes.small,
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = STRIP_HEIGHT)
                    .padding(start = spacing.md, end = if (action != null) spacing.xs else spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(STRIP_ICON), tint = tone.color)
                Box(Modifier.weight(1f).padding(vertical = spacing.xs)) { content() }
                action?.invoke()
            }
        }
    }
}

private val STRIP_HEIGHT = 36.dp
private val STRIP_ICON = 18.dp

/**
 * A still progress strip. TalkBack hears its texts, an indeterminate progress and a polite live
 * region, as for core/ui's `Loading`, but nothing animates.
 */
@Composable
private fun ActivityLine(
    message: String,
    icon: ImageVector,
    detail: String? = null,
    providerText: String? = null,
    modifier: Modifier = Modifier,
) {
    val spacing = PhopencodeTheme.spacing
    val tone = PhopencodeTheme.status.info
    Strip(
        tone = tone,
        icon = icon,
        modifier = modifier,
        stripModifier = Modifier
            .testTag(STATUS_ACTIVITY_TAG)
            .semantics(mergeDescendants = true) {
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Text(text = message, style = MaterialTheme.typography.labelLarge)
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (providerText != null) {
                Text(
                    text = providerText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = PROVIDER_TEXT_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The retry: the attempt when counted, the next try's time of day when given, the provider's text. */
@Composable
private fun RetryLine(status: ChatStatus.Retrying, modifier: Modifier) {
    val message = status.attempt?.let { stringResource(R.string.chat_status_retrying_attempt, it) }
        ?: stringResource(R.string.chat_status_retrying)
    val detail = status.nextAttemptAt?.let { stringResource(R.string.chat_status_next_try, timeOfDay(it)) }
    ActivityLine(
        message = message,
        icon = PhopencodeIcons.Refresh,
        detail = detail,
        providerText = status.message?.takeIf { it.isNotBlank() },
        modifier = modifier,
    )
}

/**
 * [instant] as a time of day in the device's format, 12 or 24 hours. A fixed time rather than a
 * countdown: a countdown would tick, and the chat would never come to rest.
 */
@Composable
private fun timeOfDay(instant: Instant): String {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(instant, context, configuration) {
        DateFormat.getTimeFormat(context).format(Date(instant.toEpochMilliseconds()))
    }
}

/**
 * The last execution's error: a strip in the error's tone, which TalkBack hears as an error. The
 * whole error shows, however long: nothing of it is cut.
 */
@Composable
private fun FailedBanner(error: StructuredError?, modifier: Modifier) {
    val description = error?.description
    val errorLabel = stringResource(CoreUiR.string.ui_common_status_danger)
    Strip(
        tone = PhopencodeTheme.status.danger,
        icon = PhopencodeIcons.Warning,
        modifier = modifier,
        stripModifier = Modifier
            .testTag(STATUS_FAILED_TAG)
            .semantics(mergeDescendants = true) {
                stateDescription = errorLabel
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Text(
            text = if (description != null) {
                stringResource(R.string.chat_status_failed, description)
            } else {
                stringResource(R.string.chat_status_failed_generic)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** The connection is down and what shows may be behind; Reconnect tries now. */
@Composable
private fun DisconnectedBanner(onIntent: (StatusIntent) -> Unit, modifier: Modifier) {
    val spacing = PhopencodeTheme.spacing
    val tone = PhopencodeTheme.status.warning
    Strip(
        tone = tone,
        icon = PhopencodeIcons.Warning,
        modifier = modifier,
        stripModifier = Modifier.testTag(STATUS_DISCONNECTED_TAG),
        action = {
            TextButton(
                onClick = { onIntent(StatusIntent.Reconnect) },
                modifier = Modifier
                    .heightIn(min = spacing.touchTarget)
                    .testTag(STATUS_RECONNECT_TAG),
                colors = ButtonDefaults.textButtonColors(contentColor = tone.onContainer),
            ) {
                Text(stringResource(R.string.chat_status_reconnect), style = MaterialTheme.typography.labelLarge)
            }
        },
    ) {
        Text(
            text = stringResource(R.string.chat_status_disconnected),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The whole place the chat gives through [modifier], its content centered, and scrolling when it
 * does not fit, as in landscape with a large font. Given an unbounded height, as inside a
 * scrolling parent, it has no place to center in and lets that parent scroll: a scrolling container
 * measured with an unbounded height throws.
 */
@Composable
private fun Blocking(modifier: Modifier, content: @Composable () -> Unit) {
    val spacing = PhopencodeTheme.spacing
    BoxWithConstraints(modifier) {
        val bounded = constraints.hasBoundedHeight
        val body = @Composable { minHeight: Dp ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    .padding(spacing.xl),
                verticalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                content()
            }
        }
        if (bounded) {
            val height = maxHeight
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(STATUS_BLOCKING_TAG)
                    .verticalScroll(rememberScrollState()),
            ) {
                body(height)
            }
        } else {
            Box(Modifier.fillMaxWidth().testTag(STATUS_BLOCKING_TAG)) {
                body(0.dp)
            }
        }
    }
}

/**
 * The icon above a blocking status, as core/ui's `EmptyState` draws it: large, in a soft tinted
 * circle. It only decorates; the text says what it means.
 */
@Composable
private fun IconCircle(icon: ImageVector) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.padding(bottom = PhopencodeTheme.spacing.sm).size(ICON_CIRCLE),
        shape = CircleShape,
        color = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
        }
    }
}

private val ICON_CIRCLE = 88.dp
private val ICON_SIZE = 40.dp

/** The widest a blocking status's text gets, so lines stay short on a tablet. */
private val TEXT_WIDTH = 360.dp

@Composable
private fun BlockingMessage(
    title: String,
    icon: ImageVector,
    message: String,
    canReconnect: Boolean,
    onIntent: (StatusIntent) -> Unit,
    modifier: Modifier,
) {
    val spacing = PhopencodeTheme.spacing
    Blocking(modifier) {
        IconCircle(icon)
        Text(
            text = title,
            modifier = Modifier.widthIn(max = TEXT_WIDTH).semantics { heading() },
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = message,
            modifier = Modifier.widthIn(max = TEXT_WIDTH),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(spacing.sm))
        if (canReconnect) {
            Button(
                onClick = { onIntent(StatusIntent.Reconnect) },
                modifier = Modifier.heightIn(min = spacing.touchTarget).testTag(STATUS_RECONNECT_TAG),
            ) {
                Text(stringResource(R.string.chat_status_reconnect))
            }
        }
        OutlinedButton(
            onClick = { onIntent(StatusIntent.Close) },
            modifier = Modifier.heightIn(min = spacing.touchTarget).testTag(STATUS_CLOSE_TAG),
        ) {
            Text(stringResource(R.string.chat_status_close))
        }
    }
}

/** What the user reads of an error: the server's message, or its type when it gave none. */
private val StructuredError.description: String?
    get() = message?.takeIf { it.isNotBlank() } ?: type.takeIf { it.isNotBlank() }

/**
 * Remaking the standing connection may fix a failing link, never a refusal or a missing session. With
 * no connection standing, Reconnect does nothing: the way back is the server list.
 */
private fun ChatFailure.reconnectHelps(): Boolean = when (this) {
    ChatFailure.Unreachable,
    ChatFailure.ServerFailed,
    ChatFailure.Unknown,
    -> true
    ChatFailure.NotConnected,
    ChatFailure.Unauthorized,
    ChatFailure.NotFound,
    ChatFailure.Conflict,
    ChatFailure.Rejected,
    -> false
}

@StringRes
private fun ChatFailure.reasonRes(): Int = when (this) {
    ChatFailure.NotConnected -> R.string.chat_status_reason_not_connected
    ChatFailure.Unreachable -> R.string.chat_status_reason_unreachable
    ChatFailure.Unauthorized -> R.string.chat_status_reason_unauthorized
    ChatFailure.NotFound -> R.string.chat_status_reason_not_found
    ChatFailure.Conflict -> R.string.chat_status_reason_conflict
    ChatFailure.Rejected -> R.string.chat_status_reason_rejected
    ChatFailure.ServerFailed -> R.string.chat_status_reason_server_failed
    ChatFailure.Unknown -> R.string.chat_status_reason_unknown
}
