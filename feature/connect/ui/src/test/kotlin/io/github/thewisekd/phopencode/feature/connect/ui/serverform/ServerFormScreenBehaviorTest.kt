package io.github.thewisekd.phopencode.feature.connect.ui.serverform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormAuthMethod
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A made-up passwordLabel, only to look for it on screen. */
private const val TYPED_PASSWORD_TEXT = "test-only-secret-42"

/** Level 36 has no arm64 Linux runtime, so the Pi passes 26 only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class ServerFormScreenBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val intents = mutableListOf<ServerFormIntent>()

    private fun setForm(state: ServerFormState, route: ConnectRoute.ServerForm = NEW_SERVER) =
        compose.setForm({ state }, route) { intents += it }

    private val isPassword = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)

    /** A node that shows [text]: in a text node, or as a text field's displayed text. */
    private fun showsText(text: String) = SemanticsMatcher("shows text containing the given value") { node ->
        val shown = node.config.getOrElse(SemanticsProperties.Text) { emptyList() } +
            listOfNotNull(node.config.getOrNull(SemanticsProperties.EditableText))
        shown.any { it.text.contains(text) }
    }

    @Test
    fun `the root keeps the placeholder tag of its route`() {
        val route = ConnectRoute.ServerForm(serverId = "server-1")
        setForm(loadedState(isNew = false), route)

        compose.onNodeWithTag("placeholder:$route").assertExists()
    }

    @Test
    fun `typing in each field sends its intent`() {
        // The text fields show what the state holds, so the state follows the intents as a presenter's does.
        var state by mutableStateOf(loadedState().copy(port = ""))
        compose.setForm({ state }) { intent ->
            intents += intent
            state = when (intent) {
                is ServerFormIntent.NameChanged -> state.copy(name = intent.name)
                is ServerFormIntent.HostChanged -> state.copy(host = intent.host)
                is ServerFormIntent.PortChanged -> state.copy(port = intent.port)
                is ServerFormIntent.UsernameChanged -> state.copy(username = intent.username)
                else -> state
            }
        }

        compose.onNodeWithTag(ServerFormTags.NAME).performTextInput("Lab")
        compose.onNodeWithTag(ServerFormTags.HOST).performTextInput("server.example.invalid")
        compose.onNodeWithTag(ServerFormTags.PORT).performTextInput("2222")
        compose.onNodeWithTag(ServerFormTags.USERNAME).performTextInput("tester")

        assertEquals(
            listOf(
                ServerFormIntent.NameChanged("Lab"),
                ServerFormIntent.HostChanged("server.example.invalid"),
                ServerFormIntent.PortChanged("2222"),
                ServerFormIntent.UsernameChanged("tester"),
            ),
            intents,
        )
    }

    @Test
    fun `the opencode port field shows the saved port and sends what is typed`() {
        var state by mutableStateOf(loadedState(isNew = false).copy(opencodePort = "4096"))
        compose.setForm({ state }) { intent ->
            intents += intent
            if (intent is ServerFormIntent.OpencodePortChanged) state = state.copy(opencodePort = intent.port)
        }

        compose.onNodeWithTag(ServerFormTags.OPENCODE_PORT).performScrollTo().assert(showsText("4096"))
        compose.onNodeWithTag(ServerFormTags.OPENCODE_PORT).performTextClearance()
        compose.onNodeWithTag(ServerFormTags.OPENCODE_PORT).performTextInput("5000")

        assertEquals(
            listOf<ServerFormIntent>(ServerFormIntent.OpencodePortChanged(""), ServerFormIntent.OpencodePortChanged("5000")),
            intents,
        )
        compose.onNodeWithTag(ServerFormTags.OPENCODE_PORT).assert(showsText("5000"))
    }

    @Test
    fun `the password fields are masked and never show the typed text`() {
        setForm(loadedState())

        compose.onNodeWithTag(ServerFormTags.PASSWORD_FIELD).performScrollTo().performTextInput(TYPED_PASSWORD_TEXT)
        compose.onNodeWithTag(ServerFormTags.OPENCODE_PASSWORD_FIELD).performScrollTo().performTextInput(TYPED_PASSWORD_TEXT)

        compose.onNodeWithTag(ServerFormTags.PASSWORD_FIELD).assert(isPassword)
        compose.onNodeWithTag(ServerFormTags.OPENCODE_PASSWORD_FIELD).assert(isPassword)
        // What is drawn and read out: a text node, or a text field's shown text. `InputText` is left out on
        // purpose: Compose keeps the untransformed value there for tests and autofill, never for display.
        compose.onAllNodes(showsText(TYPED_PASSWORD_TEXT), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(showsText("•".repeat(TYPED_PASSWORD_TEXT.length)), useUnmergedTree = true)
            .assertCountEquals(2)
        assertEquals(
            listOf(
                ServerFormIntent.PasswordChanged(TYPED_PASSWORD_TEXT),
                ServerFormIntent.OpencodePasswordChanged(TYPED_PASSWORD_TEXT),
            ),
            intents,
        )
    }

    @Test
    fun `removing the saved opencode password also drops a typed replacement`() {
        setForm(loadedState(isNew = false).copy(storedOpencodePassword = true))

        compose.onNodeWithTag(ServerFormTags.OPENCODE_PASSWORD_FIELD).performScrollTo().performTextInput(TYPED_PASSWORD_TEXT)
        compose.onNodeWithTag(ServerFormTags.CLEAR_OPENCODE_PASSWORD_BUTTON).performScrollTo().performClick()

        compose.onAllNodes(showsText("•"), useUnmergedTree = true).assertCountEquals(0)
        assertEquals(
            listOf(
                ServerFormIntent.OpencodePasswordChanged(TYPED_PASSWORD_TEXT),
                ServerFormIntent.OpencodePasswordChanged(""),
                ServerFormIntent.ClearOpencodePassword,
            ),
            intents,
        )
    }

    @Test
    fun `the password fields empty when the presenter no longer holds a typed password`() {
        var state by mutableStateOf(loadedState())
        compose.setForm({ state }) { intents += it }
        compose.onNodeWithTag(ServerFormTags.PASSWORD_FIELD).performScrollTo().performTextInput(TYPED_PASSWORD_TEXT)
        compose.onNodeWithTag(ServerFormTags.OPENCODE_PASSWORD_FIELD).performScrollTo().performTextInput(TYPED_PASSWORD_TEXT)
        state = state.copy(passwordEntered = true, opencodePasswordEntered = true)
        compose.waitForIdle()
        compose.onAllNodes(showsText("•".repeat(TYPED_PASSWORD_TEXT.length)), useUnmergedTree = true)
            .assertCountEquals(2)

        // As after a successful save: the presenter dropped what was typed.
        state = state.copy(passwordEntered = false, opencodePasswordEntered = false)
        compose.waitForIdle()

        compose.onAllNodes(showsText("•"), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `the auth switch sends the chosen method`() {
        var state by mutableStateOf(loadedState())
        compose.setForm({ state }) { intents += it }

        compose.onNodeWithTag(ServerFormTags.AUTH_PASSWORD_OPTION).assertIsSelected()
        // The auth rows sit at the bottom edge of Robolectric's 320x470dp screen, and how far down depends on
        // the SDK's window insets. A tap lands at the node's on-screen center, so a row below the fold gets no
        // tap at all: scroll each one into view first.
        compose.onNodeWithTag(ServerFormTags.AUTH_KEY_OPTION).performScrollTo().performClick()
        assertEquals(listOf<ServerFormIntent>(ServerFormIntent.AuthMethodChanged(ServerFormAuthMethod.Key)), intents)

        state = state.copy(authMethod = ServerFormAuthMethod.Key)
        compose.waitForIdle()
        compose.onNodeWithTag(ServerFormTags.AUTH_KEY_OPTION).assertIsSelected()
        compose.onAllNodesWithTag(ServerFormTags.PASSWORD_FIELD).assertCountEquals(0)

        compose.onNodeWithTag(ServerFormTags.AUTH_PASSWORD_OPTION).performScrollTo().performClick()
        assertEquals(ServerFormIntent.AuthMethodChanged(ServerFormAuthMethod.Password), intents.last())
    }

    @Test
    fun `key login lists the keys and sends the picked one`() {
        setForm(
            loadedState().copy(
                authMethod = ServerFormAuthMethod.Key,
                keys = listOf(HOME_KEY, WORK_KEY),
                selectedKey = HOME_KEY.id,
            ),
        )

        compose.onNodeWithTag(ServerFormTags.key(HOME_KEY.id)).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(ServerFormTags.key(WORK_KEY.id)).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag(ServerFormTags.MANAGE_KEYS).performScrollTo().performClick()

        assertEquals(listOf(ServerFormIntent.KeySelected(WORK_KEY.id), ServerFormIntent.ManageKeys), intents)
    }

    @Test
    fun `save, cancel, back and removing the opencode password send their intents`() {
        setForm(loadedState(isNew = false).copy(storedOpencodePassword = true))

        compose.onNodeWithTag(ServerFormTags.CLEAR_OPENCODE_PASSWORD_BUTTON).performScrollTo().performClick()
        compose.onNodeWithTag(ServerFormTags.SAVE).performScrollTo().performClick()
        compose.onNodeWithTag(ServerFormTags.CANCEL).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Go back").performClick()

        assertEquals(
            listOf(
                ServerFormIntent.ClearOpencodePassword,
                ServerFormIntent.Save,
                ServerFormIntent.Cancel,
                ServerFormIntent.Cancel,
            ),
            intents,
        )
    }

    @Test
    fun `the keyboard action of the last field saves`() {
        setForm(loadedState())

        compose.onNodeWithTag(ServerFormTags.OPENCODE_PASSWORD_FIELD).performScrollTo().performImeAction()

        assertEquals(listOf<ServerFormIntent>(ServerFormIntent.Save), intents)
    }

    @Test
    fun `dismissing a form error sends its intent`() {
        setForm(loadedState().copy(error = ServerFormError.SaveFailed))

        compose.onNodeWithContentDescription("Dismiss error").performClick()

        assertEquals(listOf<ServerFormIntent>(ServerFormIntent.DismissError), intents)
    }

    @Test
    fun `while loading the form is not shown`() {
        setForm(ServerFormState(isNew = false))

        compose.onNodeWithContentDescription("Loading server…").assertIsDisplayed()
        compose.onAllNodesWithTag(ServerFormTags.NAME).assertCountEquals(0)
        compose.onAllNodesWithTag(ServerFormTags.SAVE).assertCountEquals(0)
    }

    @Test
    fun `while saving nothing can be changed or sent again`() {
        setForm(loadedState().copy(saving = true, storedOpencodePassword = true))

        listOf(
            ServerFormTags.NAME,
            ServerFormTags.HOST,
            ServerFormTags.PORT,
            ServerFormTags.USERNAME,
            ServerFormTags.AUTH_PASSWORD_OPTION,
            ServerFormTags.AUTH_KEY_OPTION,
            ServerFormTags.PASSWORD_FIELD,
            ServerFormTags.OPENCODE_PASSWORD_FIELD,
            ServerFormTags.CLEAR_OPENCODE_PASSWORD_BUTTON,
            ServerFormTags.SAVE,
        ).forEach { compose.onNodeWithTag(it).performScrollTo().assertIsNotEnabled() }
        compose.onNodeWithTag(ServerFormTags.CANCEL).performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(ServerFormTags.SAVE).performClick()
        assertTrue(intents.isEmpty(), "a disabled save sends nothing: $intents")
    }

    @Test
    fun `every tap target is at least 48dp`() {
        var state by mutableStateOf(loadedState(isNew = false).copy(storedOpencodePassword = true))
        compose.setForm({ state })

        listOf(
            ServerFormTags.AUTH_PASSWORD_OPTION,
            ServerFormTags.AUTH_KEY_OPTION,
            ServerFormTags.CLEAR_OPENCODE_PASSWORD_BUTTON,
            ServerFormTags.SAVE,
            ServerFormTags.CANCEL,
        ).forEach { compose.onNodeWithTag(it).performScrollTo().assertTouchSizeIsAtLeast(48.dp) }

        state = state.copy(authMethod = ServerFormAuthMethod.Key, keys = listOf(WORK_KEY))
        compose.waitForIdle()
        listOf(ServerFormTags.key(WORK_KEY.id), ServerFormTags.MANAGE_KEYS)
            .forEach { compose.onNodeWithTag(it).performScrollTo().assertTouchSizeIsAtLeast(48.dp) }
    }
}
