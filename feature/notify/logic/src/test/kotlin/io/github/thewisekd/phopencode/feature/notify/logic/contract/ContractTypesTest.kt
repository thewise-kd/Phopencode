package io.github.thewisekd.phopencode.feature.notify.logic.contract

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.FormKind
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
import io.github.thewisekd.phopencode.core.model.SessionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SENTINEL = "sentinel-7f3a"

class ContractTypesTest {
    private val form = Form(
        id = FormId("form-1"),
        sessionId = SessionId("child-1"),
        kind = FormKind.Question,
        title = SENTINEL,
    )

    private val request = PermissionRequest(
        id = PermissionRequestId("perm-1"),
        sessionId = SessionId("session-1"),
        action = "bash",
        resources = listOf(SENTINEL),
        message = SENTINEL,
    )

    @Test
    fun `the setup starts loading with the default server and nothing known about the service`() {
        val state = SetupState()

        assertTrue(state.loading)
        assertTrue(state.busy)
        assertEquals(NtfyServer.DEFAULT, state.server)
        assertEquals(NtfyServer.DEFAULT.url, state.serverText)
        assertNull(state.serverProblem)
        assertNull(state.subscriptionLink)
        assertNull(state.status)
        assertNull(state.operation)
        assertFalse(state.testSent)
        assertNull(state.error)
    }

    @Test
    fun `the setup is busy while loading or running a step`() {
        assertFalse(SetupState(loading = false).busy)
        SetupStep.entries.forEach { step -> assertTrue(SetupState(loading = false, operation = step).busy, "$step") }
    }

    @Test
    fun `the server field has unsaved changes until it matches the stored server`() {
        assertFalse(SetupState(loading = false).serverEdited)
        assertTrue(SetupState(loading = false, serverText = "https://push.example.invalid").serverEdited)
    }

    @Test
    fun `the setup never prints the link, the topic or a server of the user's own`() {
        val topic = NtfyTopic.of("a".repeat(NtfyTopic.MIN_LENGTH))!!
        val server = NtfyServer.parse("https://$SENTINEL.invalid")!!
        val state = SetupState(
            loading = false,
            server = server,
            serverText = "https://$SENTINEL.invalid",
            subscriptionLink = server.subscriptionLink(topic),
        )

        assertFalse(SENTINEL in state.toString())
        assertFalse(topic.reveal() in state.toString())
        assertFalse(SENTINEL in SetupIntent.EditServer(SENTINEL).toString())
    }

    @Test
    fun `setup cases are fixed`() {
        assertEquals(
            listOf(NotifyServiceStatus.NotInstalled, NotifyServiceStatus.Stopped, NotifyServiceStatus.Running),
            NotifyServiceStatus.entries,
        )
        assertEquals(
            listOf(SetupStep.Load, SetupStep.SaveServer, SetupStep.CheckStatus, SetupStep.Install, SetupStep.Remove, SetupStep.SendTest),
            SetupStep.entries,
        )
        assertEquals(
            listOf(NtfyServerProblem.Blank, NtfyServerProblem.NotHttps, NtfyServerProblem.Invalid),
            NtfyServerProblem.entries,
        )
    }

    @Test
    fun `failures carry no server text`() {
        assertEquals(NotifyFailure.NotConnected, NotifyFailure.of(ApiError.Closed))
        assertEquals(NotifyFailure.Unreachable, NotifyFailure.of(ApiError.Network))
        assertEquals(NotifyFailure.Unreachable, NotifyFailure.of(ApiError.Timeout))
        assertEquals(NotifyFailure.Unauthorized, NotifyFailure.of(ApiError.Unauthorized))
        assertEquals(NotifyFailure.NotFound, NotifyFailure.of(ApiError.NotFound))
        assertEquals(NotifyFailure.Rejected, NotifyFailure.of(ApiError.Forbidden))
        assertEquals(NotifyFailure.Rejected, NotifyFailure.of(ApiError.BadRequest(SENTINEL)))
        assertEquals(NotifyFailure.Rejected, NotifyFailure.of(ApiError.Conflict(SENTINEL)))
        assertEquals(NotifyFailure.ServerFailed, NotifyFailure.of(ApiError.Server(500, SENTINEL)))
        assertEquals(NotifyFailure.Unknown, NotifyFailure.of(ApiError.MalformedResponse))

        val exception = NotifyException(NotifyFailure.PythonMissing)
        assertEquals(NotifyFailure.PythonMissing, exception.failure)
        assertEquals("Notify failure: PythonMissing", exception.message)
    }

    @Test
    fun `failure cases are fixed`() {
        assertEquals(
            listOf(
                NotifyFailure.NotConnected,
                NotifyFailure.Unreachable,
                NotifyFailure.Unauthorized,
                NotifyFailure.NotFound,
                NotifyFailure.Rejected,
                NotifyFailure.ServerFailed,
                NotifyFailure.PythonMissing,
                NotifyFailure.PythonTooOld,
                NotifyFailure.InstallFailed,
                NotifyFailure.StorageFailed,
                NotifyFailure.NtfyFailed,
                NotifyFailure.Unknown,
            ),
            NotifyFailure.entries,
        )
    }

    @Test
    fun `the pending list starts loading and is empty only once read`() {
        val state = PendingState()

        assertTrue(state.loading)
        assertFalse(state.refreshing)
        assertEquals(emptyList(), state.items)
        assertNull(state.error)
        assertFalse(state.isEmpty)
        assertTrue(PendingState(loading = false).isEmpty)
        assertFalse(PendingState(loading = false, error = NotifyFailure.NotConnected).isEmpty)
    }

    @Test
    fun `pending items name their session and whether it is a child`() {
        val formItem = PendingItem.FormItem(form, sessionTitle = SENTINEL, parentId = SessionId("session-1"))
        val permissionItem = PendingItem.PermissionItem(request, sessionTitle = null, parentId = null)

        assertEquals(SessionId("child-1"), formItem.sessionId)
        assertTrue(formItem.isChild)
        assertEquals(SessionId("session-1"), permissionItem.sessionId)
        assertFalse(permissionItem.isChild)
        assertEquals("form:form-1", formItem.key)
        assertEquals("permission:perm-1", permissionItem.key)
        assertNotEquals(formItem.key, PendingItem.PermissionItem(request.copy(id = PermissionRequestId("form-1")), null, null).key)
    }

    @Test
    fun `pending items and the list never print what the server asked`() {
        val items = listOf(
            PendingItem.FormItem(form, sessionTitle = SENTINEL, parentId = null),
            PendingItem.PermissionItem(request, sessionTitle = SENTINEL, parentId = null),
        )

        items.forEach { assertFalse(SENTINEL in it.toString(), it.javaClass.simpleName) }
        assertFalse(SENTINEL in PendingState(loading = false, items = items).toString())
        assertFalse(SENTINEL in PendingIntent.Open(items[0]).toString())
    }

    @Test
    fun `opening an item names its session`() {
        val event = PendingEvent.OpenSession(SessionId("child-1"), isChild = true)

        assertEquals(SessionId("child-1"), event.sessionId)
        assertTrue(event.isChild)
    }
}
