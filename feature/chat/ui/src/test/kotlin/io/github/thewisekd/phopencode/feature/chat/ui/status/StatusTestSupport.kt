package io.github.thewisekd.phopencode.feature.chat.ui.status

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusState
import org.robolectric.RuntimeEnvironment
import java.util.Date
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The tag the harness puts on the status's modifier, as the chat puts its slot tag there. */
internal const val SLOT_TAG = "test:status"

/** The tag of what the chat draws under a banner, in the harness. */
internal const val BELOW_TAG = "test:below"

/** A provider's made-up retry text, with Turkish letters so a lost character would show. */
internal const val PROVIDER_TEXT = "Sağlayıcı aşırı yüklü, lütfen bekleyin."

/** A server's made-up error text. */
internal const val ERROR_TEXT = "Model bağlamı aşıldı."

/**
 * An error of many lines, as a stack trace is, ending in a word to look for. Lines rather than one
 * long line: Robolectric does not measure glyphs, so only line breaks make a text taller in a test.
 */
internal val LONG_ERROR_TEXT: String = (1..12).joinToString("\n") { "Ayrıntı $it çöktü." } + "\nSON"

/** The last line of [HUGE_ERROR_TEXT], to look for. */
internal const val HUGE_ERROR_END = "END-OF-ERROR"

/**
 * An error of several thousand characters, as a provider's full stack trace is: two hundred lines,
 * ending in [HUGE_ERROR_END]. Lines, as for [LONG_ERROR_TEXT], so the text is tall in a test too.
 */
internal val HUGE_ERROR_TEXT: String =
    (1..200).joinToString("\n") { "Ayrıntı $it: yığın satırı çöktü." } + "\n" + HUGE_ERROR_END

/** A fixed moment for the next retry. */
internal val NEXT_TRY: Instant = Instant.fromEpochMilliseconds(1_790_000_000_000)

/** [instant] as the banner shows the time of day, in the device's time format. */
internal fun timeOfDay(instant: Instant): String =
    DateFormat.getTimeFormat(RuntimeEnvironment.getApplication()).format(Date(instant.toEpochMilliseconds()))

/**
 * The status in the app theme, placed the way `ChatScreen` places it: the whole chat when
 * [StatusState.blocksChat] holds, otherwise a full-width banner above the rest. [state] is
 * changeable by the test and every intent is recorded.
 */
internal class StatusHarness(initial: StatusState) {
    val state: MutableState<StatusState> = mutableStateOf(initial)
    val intents = mutableListOf<StatusIntent>()

    @Composable
    fun Content() {
        PhopencodeTheme(darkTheme = false) {
            Surface {
                val current = state.value
                if (current.blocksChat) {
                    Column(Modifier.fillMaxSize()) {
                        StatusBanner(current, { intents += it }, Modifier.fillMaxWidth().weight(1f).testTag(SLOT_TAG))
                    }
                } else {
                    // Scrollable so tests can scroll a node into view first: at API 35+ the insets push content down.
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        StatusBanner(current, { intents += it }, Modifier.fillMaxWidth().testTag(SLOT_TAG))
                        Box(Modifier.fillMaxWidth().testTag(BELOW_TAG))
                    }
                }
            }
        }
    }
}

internal fun ComposeContentTestRule.setStatus(state: StatusState): StatusHarness {
    val harness = StatusHarness(state)
    setContent { harness.Content() }
    return harness
}

/** The most a failure strip may take, in px: [FAILED_MAX_WINDOW_SHARE] of the window's height. */
internal fun ComposeContentTestRule.failedCapPx(): Float = with(density) {
    (RuntimeEnvironment.getApplication().resources.configuration.screenHeightDp.dp * FAILED_MAX_WINDOW_SHARE).toPx()
}

/**
 * The shown failure strip holds [HUGE_ERROR_TEXT] within its cap: the text runs past the strip's
 * scrolling area, and scrolling that area to its end brings [HUGE_ERROR_END] into it.
 */
internal fun ComposeContentTestRule.assertHugeFailureScrollsToItsEnd() {
    waitForIdle()
    val strip = onNodeWithTag(STATUS_FAILED_TAG).fetchSemanticsNode().boundsInRoot
    assertTrue(strip.height <= failedCapPx() + 1f, "the strip keeps to its share of the window: $strip, cap ${failedCapPx()}px")

    val area = onNodeWithTag(STATUS_FAILED_TEXT_TAG, useUnmergedTree = true)
    val range = area.fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
    assertTrue(range != null && range.maxValue() > 0f, "the error scrolls inside the strip: $range")
    val before = endOfError()
    assertTrue(before.bottom > area.fetchSemanticsNode().boundsInRoot.bottom + 1f, "the error runs past the strip: $before")

    area.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 1_000_000f) }
    waitForIdle()
    val box = area.fetchSemanticsNode().boundsInRoot
    val after = endOfError()
    assertTrue(
        after.bottom <= box.bottom + 1f && after.bottom > box.top,
        "the error's end is reachable by scrolling: text $after in $box",
    )
}

/** Where the error's text lies, past the strip's clip too. */
private fun ComposeContentTestRule.endOfError(): Rect = with(density) {
    val dp = onNode(hasText(HUGE_ERROR_END, substring = true), useUnmergedTree = true).getUnclippedBoundsInRoot()
    Rect(dp.left.toPx(), dp.top.toPx(), dp.right.toPx(), dp.bottom.toPx())
}
