package io.github.thewisekd.phopencode.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.thewisekd.phopencode.core.ui.theme.LightColorScheme
import io.github.thewisekd.phopencode.core.ui.theme.LightStatusColors
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeSpacing
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val GO_BACK = "Go back"
private const val CONFIRM = "Confirm"
private const val CANCEL = "Cancel"
private const val RETRY = "Try again"
private const val DISMISS = "Dismiss error"
private const val DANGEROUS = "Dangerous action"
private const val CONTENT_TAG = "content"
private const val BANNER_TAG = "banner"

private val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

/** What the components do, in the default language: the look of each is in `ComponentLanguageContract`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class ComponentBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val touchTarget = PhopencodeSpacing().touchTarget

    // Scaffold

    @Test
    fun `the scaffold title is a heading`() {
        compose.setThemedContent { PhopencodeScaffold(title = SCREEN_TITLE) {} }

        compose.onNodeWithText(SCREEN_TITLE).assertIsDisplayed().assert(isHeading)
    }

    @Test
    fun `the scaffold back button is a button of tappable size and calls back`() {
        var backs = 0
        compose.setThemedContent { PhopencodeScaffold(title = SCREEN_TITLE, onBack = { backs++ }) {} }

        compose.onNodeWithContentDescription(GO_BACK)
            .assert(isButton)
            .assertTouchHeightIsEqualTo(touchTarget)
            .performClick()

        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test
    fun `a root screen has no back button`() {
        compose.setThemedContent { PhopencodeScaffold(title = SCREEN_TITLE) {} }

        compose.onNodeWithContentDescription(GO_BACK).assertDoesNotExist()
    }

    @Test
    fun `the scaffold content starts below the top bar`() {
        compose.setThemedContent {
            PhopencodeScaffold(title = SCREEN_TITLE) { padding ->
                Text(MESSAGE, Modifier.padding(padding).testTag(CONTENT_TAG))
            }
        }

        val title = compose.onNodeWithText(SCREEN_TITLE).getUnclippedBoundsInRoot()
        val content = compose.onNodeWithTag(CONTENT_TAG).getUnclippedBoundsInRoot()
        assertTrue(content.top >= title.bottom, "content at ${content.top}, title ends at ${title.bottom}")
    }

    @Test
    fun `the scaffold shows its actions, bottom bar and floating button`() {
        compose.setThemedContent {
            PhopencodeScaffold(
                title = SCREEN_TITLE,
                actions = { Text("action") },
                bottomBar = { Text("bottom") },
                floatingActionButton = { Text("fab") },
                snackbarHost = { Text("snackbar") },
            ) {}
        }

        listOf("action", "bottom", "fab", "snackbar").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }

    // EmptyState

    @Test
    fun `the empty state title is a heading and hides what was not given`() {
        compose.setThemedContent { EmptyState(title = SCREEN_TITLE) }

        compose.onNodeWithText(SCREEN_TITLE).assertIsDisplayed().assert(isHeading)
        compose.onNode(isButton).assertDoesNotExist()
    }

    @Test
    fun `the empty state action is a button of tappable size and calls back`() {
        var clicks = 0
        compose.setThemedContent {
            EmptyState(action = EmptyStateAction(label = BUTTON_LABEL, onClick = { clicks++ }))
        }

        compose.onNodeWithText(BUTTON_LABEL)
            .assert(isButton)
            .assertTouchHeightIsEqualTo(touchTarget)
            .performClick()

        compose.runOnIdle { assertEquals(1, clicks) }
    }

    // ErrorBanner

    @Test
    fun `the banner says it is an error in a live region, not by color alone`() {
        compose.setThemedContent { ErrorBanner(message = MESSAGE, modifier = Modifier.testTag(BANNER_TAG)) }

        compose.onNodeWithTag(BANNER_TAG)
            .assert(hasStateDescription("Error"))
            .assert(isLiveRegion)
        compose.onNodeWithText(MESSAGE).assertIsDisplayed()
    }

    @Test
    fun `the banner hides the buttons nobody handles`() {
        compose.setThemedContent { ErrorBanner(message = MESSAGE) }

        compose.onNodeWithText(RETRY).assertDoesNotExist()
        compose.onNodeWithContentDescription(DISMISS).assertDoesNotExist()
    }

    @Test
    fun `the banner buttons are tappable and call back`() {
        var retries = 0
        var dismissals = 0
        compose.setThemedContent {
            ErrorBanner(message = MESSAGE, onRetry = { retries++ }, onDismiss = { dismissals++ })
        }

        compose.onNodeWithText(RETRY).assert(isButton).assertTouchHeightIsEqualTo(touchTarget).performClick()
        compose.runOnIdle { assertEquals(1 to 0, retries to dismissals) }

        compose.onNodeWithContentDescription(DISMISS).assert(isButton).assertTouchHeightIsEqualTo(touchTarget).performClick()
        compose.runOnIdle { assertEquals(1 to 1, retries to dismissals) }
    }

    // Loading

    @Test
    fun `loading is one spoken node with a caller's message`() {
        compose.setThemedContent { Loading(message = MESSAGE) }

        compose.onNodeWithContentDescription(MESSAGE)
            .assert(isIndeterminateProgress)
            .assert(isLiveRegion)
    }

    // StatusChip

    @Test
    fun `each status has its own icon, so color is never the only sign`() {
        val icons = StatusTone.entries.map { it.icon }

        assertEquals(icons.size, icons.toSet().size)
    }

    @Test
    fun `each status takes the theme's color of the same meaning`() {
        val colors = LightStatusColors

        assertEquals(colors.success, StatusTone.Success.colors(colors))
        assertEquals(colors.warning, StatusTone.Warning.colors(colors))
        assertEquals(colors.danger, StatusTone.Danger.colors(colors))
        assertEquals(colors.info, StatusTone.Info.colors(colors))
    }

    @Test
    fun `a chip with its own label still tells the status`() {
        compose.setThemedContent {
            StatusChip(status = StatusTone.Success, label = "Connected")
        }

        compose.onNodeWithText("Connected").assertIsDisplayed().assert(hasStateDescription("Success"))
    }

    @Test
    fun `a chip that shows the status name does not say it twice`() {
        compose.setThemedContent {
            Column { StatusChip(status = StatusTone.Warning) }
        }

        compose.onNodeWithText("Warning")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    // ConfirmDialog

    @Test
    fun `confirming calls only confirm`() {
        var confirmed = 0
        var dismissed = 0
        compose.setThemedContent {
            ConfirmDialog(title = SCREEN_TITLE, message = MESSAGE, onConfirm = { confirmed++ }, onDismiss = { dismissed++ })
        }

        compose.onNodeWithText(SCREEN_TITLE).assertIsDisplayed().assert(isHeading)
        compose.onNodeWithText(MESSAGE).assertIsDisplayed()
        compose.onNodeWithText(CONFIRM).assert(isButton).assertTouchHeightIsEqualTo(touchTarget).performClick()

        compose.runOnIdle { assertEquals(1 to 0, confirmed to dismissed) }
    }

    @Test
    fun `cancelling calls only dismiss`() {
        var confirmed = 0
        var dismissed = 0
        compose.setThemedContent {
            ConfirmDialog(title = SCREEN_TITLE, message = MESSAGE, onConfirm = { confirmed++ }, onDismiss = { dismissed++ })
        }

        compose.onNodeWithText(CANCEL).assert(isButton).assertTouchHeightIsEqualTo(touchTarget).performClick()

        compose.runOnIdle { assertEquals(0 to 1, confirmed to dismissed) }
    }

    @Test
    fun `the system back gesture dismisses and does not confirm`() {
        var confirmed = 0
        var dismissed = 0
        compose.setThemedContent {
            ConfirmDialog(title = SCREEN_TITLE, message = MESSAGE, onConfirm = { confirmed++ }, onDismiss = { dismissed++ })
        }
        compose.waitForIdle()

        compose.runOnUiThread { ShadowDialog.getLatestDialog().onBackPressed() }

        compose.runOnIdle { assertEquals(0 to 1, confirmed to dismissed) }
    }

    @Test
    fun `the labels of a dialog can name the action`() {
        var confirmed = 0
        compose.setThemedContent {
            ConfirmDialog(
                title = SCREEN_TITLE,
                message = MESSAGE,
                onConfirm = { confirmed++ },
                onDismiss = {},
                confirmLabel = "Delete",
                dismissLabel = "Keep",
            )
        }

        compose.onNodeWithText("Keep").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()

        compose.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test
    fun `a destructive dialog differs from a plain one by more than color`() {
        var confirmed = 0
        compose.setThemedContent {
            ConfirmDialog(
                title = SCREEN_TITLE,
                message = MESSAGE,
                onConfirm = { confirmed++ },
                onDismiss = {},
                destructive = true,
            )
        }

        compose.onNodeWithContentDescription(DANGEROUS).assertIsDisplayed()
        assertEquals(LightColorScheme.onError, compose.onNodeWithText(CONFIRM, useUnmergedTree = true).textColor())
        compose.onNodeWithText(CONFIRM).assert(isButton).assertHasClickAction().performClick()

        compose.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test
    fun `a plain dialog has no danger sign`() {
        compose.setThemedContent {
            ConfirmDialog(title = SCREEN_TITLE, message = MESSAGE, onConfirm = {}, onDismiss = {})
        }

        compose.onNodeWithContentDescription(DANGEROUS).assertDoesNotExist()
    }
}
