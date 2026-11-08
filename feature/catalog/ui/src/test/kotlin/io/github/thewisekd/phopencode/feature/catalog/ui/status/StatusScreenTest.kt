package io.github.thewisekd.phopencode.feature.catalog.ui.status

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.thewisekd.phopencode.core.navigation.CatalogRoute
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogList
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.StatusIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.StatusState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

private const val TITLE = "MCP and plugins"
private const val LOADING = "Loading MCP servers and plugins…"
private const val MCP_HEADING = "MCP servers"
private const val PLUGINS_HEADING = "Plugins"
private const val REFRESH = "Refresh"
private const val BACK = "Go back"
private const val RETRY = "Try again"
private const val UPDATING = "Updating…"

private val listed = StatusState(
    mcp = CatalogList(loading = false, items = allMcp),
    plugins = CatalogList(loading = false, items = allPlugins),
)

private fun hasStateDescription(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

/** What the MCP and plugin screen draws for each state, and which intents its controls send. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class StatusScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val intents = mutableListOf<StatusIntent>()

    private fun show(state: StatusState) {
        compose.setThemedContent {
            StatusScreen(route = CatalogRoute.Status, state = state, onIntent = { intents += it })
        }
    }

    @Test
    fun `the root keeps the route's tag and the title is a heading`() {
        show(listed)

        compose.onNodeWithTag("placeholder:${CatalogRoute.Status}").assertIsDisplayed()
        compose.onNodeWithText(TITLE).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun `a first read shows a still loading message that TalkBack hears`() {
        show(StatusState())

        compose.onNodeWithContentDescription(LOADING)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        compose.onAllNodesWithText(MCP_HEADING).assertCountEquals(0)
    }

    @Test
    fun `both sections have a heading`() {
        show(listed)

        compose.onNodeWithText(MCP_HEADING).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.scrollToText(PLUGINS_HEADING)
        compose.onNodeWithText(PLUGINS_HEADING).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun `every MCP server shows its name and a badge that names its status`() {
        show(listed)

        val expected = listOf(
            connectedMcp.name to "Connected",
            pendingMcp.name to "Connecting",
            disabledMcp.name to "Disabled",
            failedMcp.name to "Failed",
            authMcp.name to "Needs authorization",
            oddMcp.name to "Unknown (sleeping)",
        )
        expected.forEach { (name, label) ->
            compose.scrollToText(name)
            compose.onNodeWithText(name).assertIsDisplayed()
            compose.onNodeWithTag(mcpBadgeTag(name)).assert(hasTextLabel(label))
        }
    }

    @Test
    fun `badges carry the meaning in words, not only in colour`() {
        show(listed)

        compose.onNodeWithTag(mcpBadgeTag(connectedMcp.name)).assert(hasStateDescription("Success"))
        compose.scrollToText(failedMcp.name)
        compose.onNodeWithTag(mcpBadgeTag(failedMcp.name)).assert(hasStateDescription("Error"))
        compose.scrollToText(authMcp.name)
        compose.onNodeWithTag(mcpBadgeTag(authMcp.name)).assert(hasStateDescription("Warning"))
    }

    @Test
    fun `a failed MCP server shows the server's error text`() {
        show(listed)

        compose.scrollToText(FAILED_MCP_ERROR, substring = true)
        compose.onNodeWithText("Error: $FAILED_MCP_ERROR").assertIsDisplayed()
    }

    @Test
    fun `a server without an error shows no error text`() {
        show(StatusState(mcp = CatalogList(loading = false, items = listOf(connectedMcp)), plugins = CatalogList(loading = false)))

        compose.onAllNodesWithText("Error:", substring = true).assertCountEquals(0)
    }

    @Test
    fun `every plugin shows its name, a badge and a failed one its error`() {
        show(listed)

        compose.scrollToText(activePlugin.id!!)
        compose.onNodeWithTag(pluginBadgeTag(0)).assert(hasTextLabel("Active"))
        compose.scrollToText(failedPlugin.id!!)
        compose.onNodeWithTag(pluginBadgeTag(1)).assert(hasTextLabel("Failed")).assert(hasStateDescription("Error"))
        compose.scrollToText(FAILED_PLUGIN_ERROR, substring = true)
        compose.onNodeWithText("Error: $FAILED_PLUGIN_ERROR").assertIsDisplayed()
        compose.scrollToText("Unnamed plugin")
        compose.onNodeWithText("Unnamed plugin").assertIsDisplayed()
        compose.onNodeWithTag(pluginBadgeTag(2)).assert(hasTextLabel("Unknown (loading)"))
    }

    @Test
    fun `the screen is view only, with back and refresh as its only controls`() {
        show(listed)

        compose.onAllNodes(hasClickAction()).assertCountEquals(2)
        compose.onNodeWithContentDescription(BACK).assertTouchSizeIsAtLeast()
        compose.onNodeWithContentDescription(REFRESH).assertTouchSizeIsAtLeast()
    }

    @Test
    fun `back closes and refresh reads both lists again`() {
        show(listed)

        compose.onNodeWithContentDescription(BACK).performClick()
        compose.onNodeWithContentDescription(REFRESH).performClick()

        assertEquals(listOf(StatusIntent.Close, StatusIntent.Refresh), intents)
    }

    @Test
    fun `a list that failed shows why with a retry, and the other list stays`() {
        show(
            StatusState(
                mcp = CatalogList(loading = false, error = CatalogFailure.Unreachable),
                plugins = CatalogList(loading = false, items = listOf(activePlugin)),
            ),
        )

        compose.onNodeWithText("Could not read the MCP servers. The server could not be reached.").assertIsDisplayed()
        compose.onNodeWithText(RETRY).performClick()
        compose.scrollToText(activePlugin.id!!)
        compose.onNodeWithText(activePlugin.id!!).assertIsDisplayed()

        assertEquals(listOf<StatusIntent>(StatusIntent.Refresh), intents)
    }

    @Test
    fun `a failed refresh keeps the entries read before`() {
        show(
            StatusState(
                mcp = CatalogList(loading = false, items = listOf(connectedMcp)),
                plugins = CatalogList(loading = false, items = listOf(activePlugin), error = CatalogFailure.Unauthorized),
            ),
        )

        compose.onNodeWithText(connectedMcp.name).assertIsDisplayed()
        compose.scrollToText("Could not read the plugins.", substring = true)
        compose.onNodeWithText("Could not read the plugins. The server refused the credentials.").assertIsDisplayed()
        compose.scrollToText(activePlugin.id!!)
        compose.onNodeWithText(activePlugin.id!!).assertIsDisplayed()
    }

    @Test
    fun `each failure is worded on its own`() {
        val messages = mapOf(
            CatalogFailure.NotConnected to "Not connected to a server.",
            CatalogFailure.Unreachable to "The server could not be reached.",
            CatalogFailure.Unauthorized to "The server refused the credentials.",
            CatalogFailure.NotFound to "The server does not offer this list.",
            CatalogFailure.Rejected to "The server refused the request.",
            CatalogFailure.ServerFailed to "The server failed to answer.",
            CatalogFailure.Unknown to "Something went wrong.",
        )
        assertEquals(CatalogFailure.entries.toSet(), messages.keys)
        var state by mutableStateOf(StatusState())
        compose.setThemedContent { StatusScreen(CatalogRoute.Status, state, onIntent = {}) }
        messages.forEach { (failure, message) ->
            state = StatusState(
                mcp = CatalogList(loading = false, error = failure),
                plugins = CatalogList(loading = false, items = listOf(activePlugin)),
            )
            compose.onNodeWithText("Could not read the MCP servers. $message").assertIsDisplayed()
        }
    }

    @Test
    fun `a section without entries says so`() {
        show(
            StatusState(
                mcp = CatalogList(loading = false),
                plugins = CatalogList(loading = false, items = listOf(activePlugin)),
            ),
        )

        compose.onNodeWithText("No MCP servers are configured on this server.").assertIsDisplayed()
    }

    @Test
    fun `a section still loading says so while the other shows`() {
        show(
            StatusState(
                mcp = CatalogList(loading = true),
                plugins = CatalogList(loading = false, items = listOf(activePlugin)),
            ),
        )

        compose.onNodeWithContentDescription("Loading MCP servers…").assertIsDisplayed()
        compose.scrollToText(activePlugin.id!!)
        compose.onNodeWithText(activePlugin.id!!).assertIsDisplayed()
    }

    @Test
    fun `nothing on the server shows the empty state`() {
        show(StatusState(mcp = CatalogList(loading = false), plugins = CatalogList(loading = false)))

        compose.onNodeWithText("No MCP servers or plugins").assertIsDisplayed()
        compose.onNodeWithText("This server has no MCP servers configured and no plugins loaded.").assertIsDisplayed()
    }

    @Test
    fun `a refresh over shown entries says it is updating`() {
        show(
            StatusState(
                mcp = CatalogList(loading = true, items = listOf(connectedMcp)),
                plugins = CatalogList(loading = false, items = listOf(activePlugin)),
            ),
        )

        compose.onNodeWithText(UPDATING).assertIsDisplayed()
        compose.onNodeWithText(connectedMcp.name).assertIsDisplayed()
    }

    @Test
    fun `no subtitle when nothing is being read`() {
        show(listed)

        compose.onAllNodesWithText(UPDATING).assertCountEquals(0)
    }
}

/** The chip's visible label: its merged text. */
private fun hasTextLabel(label: String) = SemanticsMatcher("text is \"$label\"") { node ->
    node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == label }
}
