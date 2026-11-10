package io.github.thewisekd.phopencode.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.navigation.SessionsRoute
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionCreateEvent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionCreateIntent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionCreatePresenter
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListIntent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListPresenter
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManageEvent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManageIntent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManagePresenter
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A session, end to end: created in the project folder from the new-session screen, listed by the
 * session list, deleted from the manage screen, and gone from the list and the server. Creating a
 * session sends no prompt, so no LLM is involved; the server has no provider at all.
 */
@RunWith(AndroidJUnit4::class)
class SessionEndToEndTest {
    private lateinit var app: E2eApp

    @Before
    fun setUp() = runBlocking {
        app = E2eApp(E2eEnvironment.fromArguments())
        app.setUp()
    }

    @After
    fun tearDown() = runBlocking {
        if (::app.isInitialized) app.tearDown()
    }

    @Test
    fun createsListsAndDeletesASessionInTheProjectFolder() = runBlocking<Unit> {
        val connected = app.connect()
        val directory = app.env.projectDir
        val title = "e2e-${UUID.randomUUID()}"

        val create = app.open<SessionCreatePresenter>(SessionsRoute.Create(directory = directory)).presenter
        E2eApp.await(create.state, "the new-session screen") { it.canCreate }
        create.onIntent(SessionCreateIntent.TitleChanged(title))
        create.onIntent(SessionCreateIntent.Create)
        val opened = E2eApp.await(create.events, "the new session")
        val id = (opened as? SessionCreateEvent.OpenChat ?: throw AssertionError("Not opened: $opened")).id
        assertNull(create.state.value.error)

        val list = app.open<SessionListPresenter>(SessionsRoute.SessionList(directory)).presenter
        val listed = E2eApp.await(list.state, "the session in the list") { state ->
            !state.loading && state.rows.any { it.id == id }
        }
        assertEquals(title, listed.rows.single { it.id == id }.title)

        val manage = app.open<SessionManagePresenter>(SessionsRoute.Manage(id.value)).presenter
        val shown = E2eApp.await(manage.state, "the session's manage screen") { !it.loading }
        assertNull(shown.toString(), shown.loadError)
        assertEquals(directory, shown.directory?.trimEnd('/'))
        manage.onIntent(SessionManageIntent.RequestDelete)
        E2eApp.await(manage.state, "the delete confirmation") { it.confirmingDelete }
        manage.onIntent(SessionManageIntent.ConfirmDelete)
        val deleted = E2eApp.await(manage.events, "the delete")
        assertEquals(SessionManageEvent.Deleted::class, deleted::class)

        list.onIntent(SessionListIntent.Refresh)
        E2eApp.await(list.state, "the session to leave the list") { state ->
            !state.loading && !state.refreshing && state.rows.none { it.id == id }
        }
        val failure = assertThrows(ApiException::class.java) {
            runBlocking { connected.client.sessions.get(id) }
        }
        assertEquals(ApiError.NotFound, failure.error)
    }
}
