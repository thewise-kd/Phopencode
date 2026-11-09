package io.github.thewisekd.phopencode.feature.notify.ui.pending

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.FormField
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.FormKind
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.navigation.NotifyRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyFailure
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingIntent
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingItem
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingState
import java.util.Collections

/** A question in a root session with a title. */
internal val question = PendingItem.FormItem(
    form = Form(
        id = FormId("form-1"),
        sessionId = SessionId("session-1"),
        kind = FormKind.Question,
        title = "Which database?",
        fields = listOf(FormField.FreeText(key = "q0", title = "secret-field-title")),
    ),
    sessionTitle = "Refactor the parser",
    parentId = null,
)

/** Another kind of form, in a session without a title. */
internal val otherForm = PendingItem.FormItem(
    form = Form(id = FormId("form-2"), sessionId = SessionId("session-2"), kind = FormKind.Other("mcp")),
    sessionTitle = null,
    parentId = null,
)

/** A permission request of a subagent's session. Its resources must never be drawn. */
internal val permission = PendingItem.PermissionItem(
    request = PermissionRequest(
        id = PermissionRequestId("perm-1"),
        sessionId = SessionId("child-1"),
        action = "bash",
        resources = listOf("rm -rf /srv/secret-resource"),
        message = "secret-permission-message",
    ),
    sessionTitle = "Run the tests",
    parentId = SessionId("session-1"),
)

internal val listed = PendingState(loading = false, items = listOf(question, otherForm, permission))

/** The failures after which reading the list again can help; the others need a new connection. */
internal val CONNECTION_FAILURES = setOf(NotifyFailure.NotConnected, NotifyFailure.Unauthorized)
internal val RETRYABLE = NotifyFailure.entries.toSet() - CONNECTION_FAILURES

/** Records the intents the screen sends. */
internal class IntentLog {
    val intents: MutableList<PendingIntent> = Collections.synchronizedList(mutableListOf())

    fun send(intent: PendingIntent) {
        intents += intent
    }
}

internal fun ComposeContentTestRule.setPending(state: PendingState, log: IntentLog = IntentLog()): IntentLog {
    setContent { PhopencodeTheme { PendingScreen(NotifyRoute.Pending, state, log::send) } }
    return log
}
