package io.github.thewisekd.phopencode.feature.chat.ui.permission

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionCardState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionIntent

/** The request's ID in the tests. */
internal val REQUEST: PermissionRequestId = PermissionRequestId("per_1")

/** The session of the request when it comes from a subagent. */
internal val CHILD: SessionId = SessionId("ses_child")

/** The tag the harness puts on the card's modifier, as the chat puts its slot tag there. */
internal const val CARD_TAG = "test:permission"

/** The tag the harness puts on the list error's modifier. */
internal const val LIST_ERROR_TAG = "test:permission_list_error"

/** A made-up command, with Turkish letters so a lost character would show. */
internal const val COMMAND = "rm -rf çıktı/ && ./gradlew test"

/** A shell request of this session for [resources], remembering [save] when always allowed. */
internal fun bashCard(
    resources: List<String> = listOf(COMMAND),
    save: List<String> = listOf("rm *"),
) = PermissionCardState(
    requestId = REQUEST,
    sessionId = SessionId("ses_own"),
    action = "bash",
    resources = resources,
    save = save,
)

/** The card in the app theme, with [state] changeable by the test and every intent recorded. */
internal class PermissionHarness(initial: PermissionCardState) {
    val state: MutableState<PermissionCardState> = mutableStateOf(initial)
    val intents = mutableListOf<PermissionIntent>()

    @Composable
    fun Content() {
        PhopencodeTheme(darkTheme = false) {
            Surface {
                // Scrollable as the chat's interaction area is, so tests can scroll a node into view.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PermissionCard(
                        card = state.value,
                        onIntent = { intents += it },
                        modifier = Modifier.fillMaxWidth().testTag(CARD_TAG),
                    )
                }
            }
        }
    }
}

internal fun ComposeContentTestRule.setPermission(state: PermissionCardState): PermissionHarness {
    val harness = PermissionHarness(state)
    setContent { harness.Content() }
    return harness
}

/** The list error in the app theme, with every intent recorded. */
internal class PermissionListErrorHarness(initial: ChatFailure) {
    val error: MutableState<ChatFailure> = mutableStateOf(initial)
    val intents = mutableListOf<PermissionIntent>()

    @Composable
    fun Content() {
        PhopencodeTheme(darkTheme = false) {
            Surface {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PermissionListError(
                        error = error.value,
                        onIntent = { intents += it },
                        modifier = Modifier.fillMaxWidth().testTag(LIST_ERROR_TAG),
                    )
                }
            }
        }
    }
}

internal fun ComposeContentTestRule.setPermissionListError(error: ChatFailure): PermissionListErrorHarness {
    val harness = PermissionListErrorHarness(error)
    setContent { harness.Content() }
    return harness
}

/** The text a node shows, its parts joined. */
internal fun SemanticsNodeInteraction.text(): String =
    fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }

/** The text a text field holds. */
internal fun SemanticsNodeInteraction.editableText(): String =
    fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
