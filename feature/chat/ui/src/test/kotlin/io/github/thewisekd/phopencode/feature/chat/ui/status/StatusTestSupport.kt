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
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusState
import org.robolectric.RuntimeEnvironment
import java.util.Date
import kotlin.time.Instant

/** The tag the harness puts on the status's modifier, as the chat puts its slot tag there. */
internal const val SLOT_TAG = "test:status"

/** The tag of what the chat draws under a banner, in the harness. */
internal const val BELOW_TAG = "test:below"

/** A provider's made-up retry text, with Turkish letters so a lost character would show. */
internal const val PROVIDER_TEXT = "Sağlayıcı aşırı yüklü, lütfen bekleyin."

/** A server's made-up error text. */
internal const val ERROR_TEXT = "Model bağlamı aşıldı."

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
