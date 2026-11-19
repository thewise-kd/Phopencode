package io.github.thewisekd.phopencode.core.session.reducer

import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.SessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Printing a [SessionState] shows the status kind and counts, never the server's text. */
class SessionStateToStringTest {
    // Assembled at runtime, so that the source holds no secret-shaped literal.
    private val marker = listOf("server", "said", "10.0.0.9", "token", "42").joinToString("-")
    private val state = SessionState(session = Session(id = SessionId("ses_1")))

    private fun printed(status: SessionStatus): String = state.copy(status = status).toString()

    @Test
    fun `a retrying status prints its kind and attempt, never its message`() {
        val text = printed(SessionStatus.Retrying(attempt = 3, message = marker))
        assertFalse(marker in text, text)
        assertTrue("Retrying" in text, text)
        assertTrue("3" in text, text)
    }

    @Test
    fun `a failed status prints its kind, never its message`() {
        val text = printed(SessionStatus.Failed(message = marker))
        assertFalse(marker in text, text)
        assertTrue("Failed" in text, text)
    }

    @Test
    fun `an unknown status prints its kind, never the raw text`() {
        val text = printed(SessionStatus.Unknown(raw = marker))
        assertFalse(marker in text, text)
        assertTrue("Unknown" in text, text)
    }

    @Test
    fun `statuses with and without text print the same`() {
        assertEquals(printed(SessionStatus.Failed(message = marker)), printed(SessionStatus.Failed()))
        assertEquals(printed(SessionStatus.Retrying(attempt = 1, message = marker)), printed(SessionStatus.Retrying(attempt = 1)))
        assertEquals(printed(SessionStatus.Unknown(raw = marker)), printed(SessionStatus.Unknown(raw = "other")))
    }

    @Test
    fun `idle and running print their kind and the counts`() {
        val idle = printed(SessionStatus.Idle)
        assertTrue("status=Idle" in idle, idle)
        assertTrue("messages=0" in idle, idle)
        assertTrue("status=Running" in printed(SessionStatus.Running))
    }
}
