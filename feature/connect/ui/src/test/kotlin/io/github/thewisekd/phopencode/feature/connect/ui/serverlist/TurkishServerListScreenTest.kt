package io.github.thewisekd.phopencode.feature.connect.ui.serverlist

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerListState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The screen speaks Turkish on a Turkish phone: a sample of its strings, the parity check covers the rest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishServerListScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: ServerListState) {
        compose.setThemedContent {
            ServerListScreen(route = ConnectRoute.ServerList, state = state, onIntent = {})
        }
    }

    @Test
    fun `the empty state is in Turkish`() {
        show(ServerListState(loading = false))

        compose.onNodeWithText("Sunucular").assertIsDisplayed()
        compose.onNodeWithText("Henüz sunucu yok").assertIsDisplayed()
        compose.onNodeWithText("Sunucu ekle").assertIsDisplayed()
    }

    @Test
    fun `the delete confirmation and the edit label are in Turkish`() {
        show(ServerListState(loading = false, servers = listOf(firstServer), pendingDelete = firstServer))

        compose.onNodeWithText("Sunucu silinsin mi?").assertIsDisplayed()
        compose.onNodeWithText("Sil").assertIsDisplayed()
        compose.onNodeWithContentDescription("Düzenle: ${firstServer.name}").assertExists()
    }
}
