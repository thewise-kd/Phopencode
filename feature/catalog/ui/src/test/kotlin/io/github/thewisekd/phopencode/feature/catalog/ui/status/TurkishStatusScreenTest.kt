package io.github.thewisekd.phopencode.feature.catalog.ui.status

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.github.thewisekd.phopencode.core.navigation.CatalogRoute
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogList
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.StatusState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The screen speaks Turkish on a Turkish phone: a sample of its strings, the parity check covers the rest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishStatusScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: StatusState) {
        compose.setThemedContent {
            StatusScreen(route = CatalogRoute.Status, state = state, onIntent = {})
        }
    }

    @Test
    fun `the list, badges and error text are in Turkish`() {
        show(
            StatusState(
                mcp = CatalogList(loading = false, items = listOf(connectedMcp, failedMcp)),
                plugins = CatalogList(loading = false, items = listOf(activePlugin)),
            ),
        )

        compose.onNodeWithText("MCP ve eklentiler").assertIsDisplayed()
        compose.onNodeWithText("MCP sunucuları").assertIsDisplayed()
        compose.onNodeWithContentDescription("Yenile").assertIsDisplayed()
        compose.onNodeWithTag(mcpBadgeTag(connectedMcp.name))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Başarılı"))
        compose.onNodeWithText("Bağlı").assertIsDisplayed()
        compose.onNodeWithText("Başarısız").assertIsDisplayed()
        compose.scrollToText(FAILED_MCP_ERROR, substring = true)
        compose.onNodeWithText("Hata: $FAILED_MCP_ERROR").assertIsDisplayed()
        compose.scrollToText("Eklentiler")
        compose.onNodeWithText("Eklentiler").assertIsDisplayed()
        compose.scrollToText("Etkin")
        compose.onNodeWithText("Etkin").assertIsDisplayed()
    }

    @Test
    fun `a failed list and the empty state are in Turkish`() {
        show(
            StatusState(
                mcp = CatalogList(loading = false, error = CatalogFailure.Unreachable),
                plugins = CatalogList(loading = false),
            ),
        )

        compose.onNodeWithText("MCP sunucuları okunamadı. Sunucuya ulaşılamadı.").assertIsDisplayed()
        compose.onNodeWithText("Tekrar dene").assertIsDisplayed()
        compose.scrollToText("Bu sunucuda yüklü eklenti yok.")
        compose.onNodeWithText("Bu sunucuda yüklü eklenti yok.").assertIsDisplayed()
    }

    @Test
    fun `the loading message is in Turkish`() {
        show(StatusState())

        compose.onNodeWithContentDescription("MCP sunucuları ve eklentiler yükleniyor…").assertIsDisplayed()
    }
}
