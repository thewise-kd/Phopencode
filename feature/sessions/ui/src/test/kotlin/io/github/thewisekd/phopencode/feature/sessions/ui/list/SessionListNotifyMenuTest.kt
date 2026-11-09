package io.github.thewisekd.phopencode.feature.sessions.ui.list

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * The top bar menu's notification items: the notification setup and the pending items, below the
 * catalog's items in the same menu.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class SessionListNotifyMenuTest {
    @get:Rule
    val compose = createComposeRule()

    private val strings = ENGLISH_SESSION_LIST_STRINGS
    private val opened = mutableListOf<NotifyDestination>()
    private val openedCatalog = mutableListOf<CatalogDestination>()
    private val intents = mutableListOf<SessionListIntent>()

    private fun show(directory: String? = null) {
        compose.setSessionList(
            listed.copy(directory = directory),
            onOpenCatalog = { openedCatalog += it },
            onOpenNotify = { opened += it },
        ) { intents += it }
    }

    private fun openMenu() {
        compose.onNodeWithContentDescription(strings.catalog).performClick()
    }

    @Test
    fun `the notification items are hidden until the menu opens`() {
        show()

        strings.notifyItems.values.forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        openMenu()
        strings.notifyItems.values.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun `the menu keeps every catalog item beside the notification items`() {
        show(directory = FOLDER)
        openMenu()

        (strings.catalogItems.values + strings.notifyItems.values).forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }

    @Test
    fun `notifications opens the notification setup`() = assertItemOpens(NotifyDestination.Setup)

    @Test
    fun `pending items opens the pending items`() = assertItemOpens(NotifyDestination.Pending)

    @Test
    fun `each notification item is a 48 dp clickable target`() {
        show()
        openMenu()

        strings.notifyItems.values.forEach {
            compose.onNodeWithText(it).assertHasClickAction().assertTouchSizeIsAtLeast()
        }
    }

    private fun assertItemOpens(destination: NotifyDestination) {
        show()
        openMenu()

        compose.onNodeWithText(strings.notifyItems.getValue(destination)).performClick()

        compose.runOnIdle {
            assertEquals(listOf(destination), opened)
            assertTrue(openedCatalog.isEmpty(), "a notification item opens no catalog screen")
            assertTrue(intents.none { it != SessionListIntent.LoadMore }, "a notification item sends no intent")
        }
        (strings.catalogItems.values + strings.notifyItems.values).forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
    }
}
