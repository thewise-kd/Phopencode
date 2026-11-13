package io.github.thewisekd.phopencode.feature.sessions.ui.list

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListIntent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The top bar menu's "Live preview" item (P13.16): last in the menu, it sends
 * [SessionListIntent.OpenPreview], and only while connected.
 *
 * Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class SessionListPreviewMenuTest {
    @get:Rule
    val compose = createComposeRule()

    private val strings = ENGLISH_SESSION_LIST_STRINGS
    private val openedCatalog = mutableListOf<CatalogDestination>()
    private val openedNotify = mutableListOf<NotifyDestination>()
    private val intents = mutableListOf<SessionListIntent>()

    private fun show(connected: Boolean, directory: String? = null) {
        compose.setSessionList(
            listed.copy(connected = connected, directory = directory),
            onOpenCatalog = { openedCatalog += it },
            onOpenNotify = { openedNotify += it },
        ) { intents += it }
    }

    private fun openMenu() {
        compose.onNodeWithContentDescription(strings.catalog).performClick()
    }

    @Test
    fun `the item is hidden until the menu opens`() {
        show(connected = true)

        compose.onNodeWithText(strings.preview).assertDoesNotExist()
        openMenu()
        compose.onNodeWithText(strings.preview).assertIsDisplayed()
    }

    @Test
    fun `while connected the item opens the live preview and closes the menu`() {
        show(connected = true, directory = FOLDER)
        openMenu()

        compose.onNodeWithTag(SESSION_PREVIEW_ITEM_TAG).assertIsEnabled().performClick()

        compose.runOnIdle {
            assertEquals(listOf<SessionListIntent>(SessionListIntent.OpenPreview), intents.filter { it != SessionListIntent.LoadMore })
            assertTrue(openedCatalog.isEmpty() && openedNotify.isEmpty(), "the preview item opens no other screen")
        }
        compose.onNodeWithText(strings.preview).assertDoesNotExist()
    }

    @Test
    fun `while not connected the item is disabled and sends nothing`() {
        show(connected = false)
        openMenu()

        compose.onNodeWithTag(SESSION_PREVIEW_ITEM_TAG).assertIsNotEnabled().performClick()

        compose.runOnIdle {
            assertTrue(intents.none { it == SessionListIntent.OpenPreview }, "a disabled item sends no intent")
        }
    }

    @Test
    fun `the item is a 48 dp clickable target`() {
        show(connected = true)
        openMenu()

        compose.onNodeWithTag(SESSION_PREVIEW_ITEM_TAG).assertHasClickAction().assertTouchSizeIsAtLeast()
    }
}
