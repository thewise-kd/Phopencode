package io.github.thewisekd.phopencode.feature.connect.ui.serverlist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerListError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerListIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerListState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TITLE = "Servers"
private const val LOADING = "Loading servers…"
private const val EMPTY_TITLE = "No servers yet"
private const val EMPTY_MESSAGE = "A server is the computer that runs opencode serve. The app reaches it over SSH."
private const val ADD = "Add server"
private const val KEYS = "SSH keys"
private const val SETTINGS = "Settings"
private const val DELETE = "Delete"
private const val CANCEL = "Cancel"
private const val DELETE_TITLE = "Delete server?"
private const val MODE_KEY = "SSH key"
private const val MODE_PASSWORD = "Password"

/** core/ui's tag for the circle around an empty state's icon. */
private const val EMPTY_STATE_ICON_TAG = "ui-common:empty-state-icon"

private val listed = ServerListState(loading = false, servers = listOf(firstServer, secondServer))

/** What the server list draws for each state, and which intents its controls send. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class ServerListScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val intents = mutableListOf<ServerListIntent>()

    private fun show(state: ServerListState) {
        compose.setThemedContent {
            ServerListScreen(route = ConnectRoute.ServerList, state = state, onIntent = { intents += it })
        }
    }

    /** A screen whose state answers delete intents the way the presenter's contract says. */
    private fun showWithDeleteFlow(initial: ServerListState) {
        var state by mutableStateOf(initial)
        compose.setThemedContent {
            ServerListScreen(
                route = ConnectRoute.ServerList,
                state = state,
                onIntent = { intent ->
                    intents += intent
                    state = when (intent) {
                        is ServerListIntent.RequestDelete ->
                            state.copy(pendingDelete = state.servers.first { it.id == intent.id })
                        ServerListIntent.CancelDelete -> state.copy(pendingDelete = null)
                        ServerListIntent.ConfirmDelete -> state.copy(
                            servers = state.servers - checkNotNull(state.pendingDelete),
                            pendingDelete = null,
                        )
                        else -> state
                    }
                },
            )
        }
    }

    private fun rowOf(name: String) = compose.onNodeWithText(name, useUnmergedTree = false)

    @Test
    fun `the root keeps the placeholder tag the navigation tests look for`() {
        show(listed)

        compose.onNodeWithTag("placeholder:${ConnectRoute.ServerList}").assertIsDisplayed()
    }

    @Test
    fun `the title is a heading`() {
        show(listed)

        compose.onNodeWithText(TITLE).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun `loading without servers shows progress and no empty state`() {
        show(ServerListState(loading = true))

        compose.onNodeWithContentDescription(LOADING)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        compose.onNodeWithText(EMPTY_TITLE).assertDoesNotExist()
    }

    @Test
    fun `no servers shows the empty state whose button adds one`() {
        show(ServerListState(loading = false))

        compose.onNodeWithText(EMPTY_TITLE).assertIsDisplayed()
        compose.onNodeWithText(EMPTY_MESSAGE).assertIsDisplayed()
        compose.onNodeWithTag(EMPTY_STATE_ICON_TAG).assertIsDisplayed()
        compose.onAllNodesWithText(ADD).assertCountEquals(1)
        compose.onNodeWithText(ADD).assertTouchSizeIsAtLeast().performClick()

        compose.runOnIdle { assertEquals(listOf<ServerListIntent>(ServerListIntent.Add), intents) }
    }

    @Test
    fun `servers are listed with their address in order`() {
        show(listed)

        compose.onNodeWithText(firstServer.name).assertIsDisplayed()
        compose.onNodeWithText("dev@build.example.invalid:22").assertIsDisplayed()
        compose.onNodeWithText(secondServer.name).assertIsDisplayed()
        compose.onNodeWithText("ops@lab.example.invalid:2222").assertIsDisplayed()
        compose.onNodeWithText(EMPTY_TITLE).assertDoesNotExist()
        val firstTop = rowOf(firstServer.name).fetchSemanticsNode().boundsInRoot.top
        val secondTop = rowOf(secondServer.name).fetchSemanticsNode().boundsInRoot.top
        assertTrue(firstTop < secondTop, "the presenter's order is kept")
    }

    @Test
    fun `each server shows the first letter of its name in an avatar`() {
        show(listed)

        compose.onNodeWithTag(serverAvatarTag(firstServer.id), useUnmergedTree = true)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(AvatarLetterKey, "B"))
        compose.onNodeWithTag(serverAvatarTag(secondServer.id), useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(AvatarLetterKey, "L"))
    }

    @Test
    fun `the avatar letter is the first visible letter, upper-cased, or a stand-in`() {
        assertEquals("R", avatarLetter("  raspberry", Locale.ENGLISH))
        assertEquals("7", avatarLetter("7th floor", Locale.ENGLISH))
        assertEquals("\uD83D\uDE80", avatarLetter("\uD83D\uDE80 rocket", Locale.ENGLISH))
        assertEquals("?", avatarLetter("   ", Locale.ENGLISH))
    }

    @Test
    fun `the avatar letter is not read out as part of the row`() {
        show(listed)

        rowOf(firstServer.name).assert(
            SemanticsMatcher("row text without the avatar letter") { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.none { it.text == "B" }
            },
        )
    }

    @Test
    fun `each server shows how it logs in as a chip`() {
        show(listed)

        compose.onNodeWithText(MODE_KEY, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(MODE_PASSWORD, useUnmergedTree = true).assertIsDisplayed()
        rowOf(firstServer.name).assert(hasText(MODE_KEY))
        rowOf(secondServer.name).assert(hasText(MODE_PASSWORD))
    }

    @Test
    fun `a server row says that a tap connects`() {
        show(listed)

        rowOf(firstServer.name).assert(
            SemanticsMatcher("click label is Connect") { node ->
                node.config.getOrElseNullable(SemanticsActions.OnClick) { null }?.label == "Connect"
            },
        )
    }

    @Test
    fun `tapping a server selects it`() {
        show(listed)

        rowOf(secondServer.name).assertTouchSizeIsAtLeast().performClick()

        compose.runOnIdle {
            assertEquals(listOf<ServerListIntent>(ServerListIntent.Select(secondServer.id)), intents)
        }
    }

    @Test
    fun `each server has a labelled edit button of tappable size`() {
        show(listed)

        compose.onNodeWithContentDescription("Edit ${firstServer.name}")
            .assertTouchSizeIsAtLeast()
            .performClick()

        compose.runOnIdle {
            assertEquals(listOf<ServerListIntent>(ServerListIntent.Edit(firstServer.id)), intents)
        }
    }

    @Test
    fun `the extended add button shows its label and adds a server when servers are listed`() {
        show(listed)

        compose.onNodeWithText(ADD).assertIsDisplayed().assertTouchSizeIsAtLeast().performClick()

        compose.runOnIdle { assertEquals(listOf<ServerListIntent>(ServerListIntent.Add), intents) }
    }

    @Test
    fun `the top bar opens keys and settings from named icon buttons`() {
        show(listed)

        compose.onNodeWithText(KEYS).assertDoesNotExist()
        compose.onNodeWithText(SETTINGS).assertDoesNotExist()
        compose.onNodeWithContentDescription(KEYS).assertTouchSizeIsAtLeast().performClick()
        compose.onNodeWithContentDescription(SETTINGS).assertTouchSizeIsAtLeast().performClick()

        compose.runOnIdle {
            assertEquals(listOf(ServerListIntent.OpenKeys, ServerListIntent.OpenSettings), intents)
        }
    }

    @Test
    fun `swiping a server to the start only asks to delete it`() {
        show(listed)

        rowOf(firstServer.name).performTouchInput { swipeLeft() }

        compose.runOnIdle {
            assertEquals(listOf<ServerListIntent>(ServerListIntent.RequestDelete(firstServer.id)), intents)
        }
        rowOf(firstServer.name).assertIsDisplayed()
    }

    @Test
    fun `swiping a server to the end does nothing`() {
        show(listed)

        rowOf(firstServer.name).performTouchInput { swipeRight() }

        compose.runOnIdle { assertEquals(emptyList(), intents) }
    }

    @Test
    fun `a delete the user can reach without swiping is offered to accessibility services`() {
        show(listed)

        val actions = rowOf(firstServer.name).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        val delete = actions.single { it.label == DELETE }
        compose.runOnIdle { delete.action() }

        compose.runOnIdle {
            assertEquals(listOf<ServerListIntent>(ServerListIntent.RequestDelete(firstServer.id)), intents)
        }
    }

    @Test
    fun `a pending delete asks for confirmation naming the server`() {
        show(listed.copy(pendingDelete = secondServer))

        compose.onNodeWithText(DELETE_TITLE).assertIsDisplayed()
        compose.onNode(hasText(secondServer.name, substring = true) and hasText("removed", substring = true))
            .assertIsDisplayed()
    }

    @Test
    fun `confirming the dialog sends confirm delete`() {
        show(listed.copy(pendingDelete = secondServer))

        compose.onNodeWithText(DELETE).assertTouchSizeIsAtLeast().performClick()

        compose.runOnIdle { assertEquals(listOf<ServerListIntent>(ServerListIntent.ConfirmDelete), intents) }
    }

    @Test
    fun `cancelling the dialog sends cancel delete`() {
        show(listed.copy(pendingDelete = secondServer))

        compose.onNodeWithText(CANCEL).assertTouchSizeIsAtLeast().performClick()

        compose.runOnIdle { assertEquals(listOf<ServerListIntent>(ServerListIntent.CancelDelete), intents) }
    }

    @Test
    fun `no dialog is shown without a pending delete`() {
        show(listed)

        compose.onNodeWithText(DELETE_TITLE).assertDoesNotExist()
    }

    @Test
    fun `swipe then cancel keeps the server in place`() {
        showWithDeleteFlow(listed)

        rowOf(firstServer.name).performTouchInput { swipeLeft() }
        compose.onNodeWithText(DELETE_TITLE).assertIsDisplayed()
        compose.onNodeWithText(CANCEL).performClick()

        compose.onNodeWithText(DELETE_TITLE).assertDoesNotExist()
        rowOf(firstServer.name).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(
                listOf(ServerListIntent.RequestDelete(firstServer.id), ServerListIntent.CancelDelete),
                intents,
            )
        }
    }

    @Test
    fun `swipe then confirm leaves the deletion to the presenter`() {
        showWithDeleteFlow(listed)

        rowOf(firstServer.name).performTouchInput { swipeLeft() }
        compose.onNodeWithText(DELETE).performClick()

        compose.onNodeWithText(DELETE_TITLE).assertDoesNotExist()
        compose.onNodeWithText(firstServer.name).assertDoesNotExist()
        rowOf(secondServer.name).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(
                listOf(ServerListIntent.RequestDelete(firstServer.id), ServerListIntent.ConfirmDelete),
                intents,
            )
        }
    }

    @Test
    fun `each error is shown with its own message and can be dismissed`() {
        val messages = mapOf(
            ServerListError.LoadFailed to "The saved servers could not be read.",
            ServerListError.SaveFailed to "The connection did not start: the server could not be updated.",
            ServerListError.DeleteFailed to "The server could not be deleted.",
        )
        var state by mutableStateOf(listed)
        compose.setThemedContent {
            ServerListScreen(route = ConnectRoute.ServerList, state = state, onIntent = { intents += it })
        }

        messages.forEach { (error, message) ->
            state = listed.copy(error = error)
            compose.onNodeWithText(message, substring = true).assertIsDisplayed()
            messages.values.filter { it != message }.forEach {
                compose.onNodeWithText(it, substring = true).assertDoesNotExist()
            }
        }
        compose.onNodeWithContentDescription("Dismiss error").assertTouchSizeIsAtLeast().performClick()

        compose.runOnIdle { assertEquals(listOf<ServerListIntent>(ServerListIntent.DismissError), intents) }
    }

    @Test
    fun `an error is shown even when no server could be listed`() {
        show(ServerListState(loading = false, error = ServerListError.LoadFailed))

        compose.onNodeWithText("The saved servers could not be read.", substring = true).assertIsDisplayed()
    }

    @Test
    fun `no error banner without an error`() {
        show(listed)

        compose.onNodeWithContentDescription("Dismiss error").assertDoesNotExist()
    }
}
