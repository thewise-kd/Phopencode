package io.github.thewisekd.phopencode.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.github.thewisekd.phopencode.core.ui.theme.DarkColorScheme
import io.github.thewisekd.phopencode.core.ui.theme.DarkStatusColors
import io.github.thewisekd.phopencode.core.ui.theme.LightColorScheme
import io.github.thewisekd.phopencode.core.ui.theme.LightStatusColors
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeStatusColors
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

private const val LOADING_TAG = "loading"
private const val SUBTITLE = "Some detail"

/** Every built-in text of `strings_ui_common.xml`, as a user of that language reads it. */
data class ExpectedStrings(
    val navigateBack: String,
    val emptyTitle: String,
    val errorGeneric: String,
    val errorDismiss: String,
    val retry: String,
    val loading: String,
    val confirm: String,
    val cancel: String,
    val dangerousAction: String,
    val statusSuccess: String,
    val statusWarning: String,
    val statusDanger: String,
    val statusInfo: String,
)

/**
 * Each component's built-in text and colors, once per language and theme: a subclass
 * fixes the language with a qualifier, and each case here runs in both themes.
 */
abstract class ComponentLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    protected abstract val strings: ExpectedStrings

    private fun assertScaffold(darkTheme: Boolean, scheme: ColorScheme) {
        compose.setThemedContent(darkTheme) {
            PhopencodeScaffold(title = SCREEN_TITLE, subtitle = SUBTITLE, onBack = {}) { Text(MESSAGE) }
        }

        compose.onNodeWithContentDescription(strings.navigateBack).assertIsDisplayed().assertHasClickAction()
        assertEquals(scheme.onSurface, compose.onNodeWithText(SCREEN_TITLE, useUnmergedTree = true).textColor())
        assertEquals(scheme.onSurfaceVariant, compose.onNodeWithText(SUBTITLE, useUnmergedTree = true).textColor())
    }

    @Test
    fun `the scaffold in the light theme`() = assertScaffold(darkTheme = false, LightColorScheme)

    @Test
    fun `the scaffold in the dark theme`() = assertScaffold(darkTheme = true, DarkColorScheme)

    private fun assertEmptyState(darkTheme: Boolean, scheme: ColorScheme) {
        compose.setThemedContent(darkTheme) { EmptyState(message = MESSAGE) }

        val title = compose.onNodeWithText(strings.emptyTitle, useUnmergedTree = true).assertIsDisplayed()
        assertEquals(scheme.onSurface, title.textColor())
        val message = compose.onNodeWithText(MESSAGE, useUnmergedTree = true).assertIsDisplayed()
        assertEquals(scheme.onSurfaceVariant, message.textColor())
    }

    @Test
    fun `the empty state in the light theme`() = assertEmptyState(darkTheme = false, LightColorScheme)

    @Test
    fun `the empty state in the dark theme`() = assertEmptyState(darkTheme = true, DarkColorScheme)

    private fun assertErrorBanner(darkTheme: Boolean, status: PhopencodeStatusColors) {
        compose.setThemedContent(darkTheme) { ErrorBanner(onRetry = {}, onDismiss = {}) }

        val message = compose.onNodeWithText(strings.errorGeneric, useUnmergedTree = true).assertIsDisplayed()
        assertEquals(status.danger.onContainer, message.textColor())
        val retry = compose.onNodeWithText(strings.retry, useUnmergedTree = true).assertIsDisplayed()
        assertEquals(status.danger.onContainer, retry.textColor())
        compose.onNodeWithContentDescription(strings.errorDismiss).assertIsDisplayed()
        compose.onNodeWithText(strings.errorGeneric).assert(hasStateDescription(strings.statusDanger))
    }

    @Test
    fun `the error banner in the light theme`() = assertErrorBanner(darkTheme = false, LightStatusColors)

    @Test
    fun `the error banner in the dark theme`() = assertErrorBanner(darkTheme = true, DarkStatusColors)

    private fun assertLoading(darkTheme: Boolean, scheme: ColorScheme) {
        compose.setThemedContent(darkTheme) { Loading(modifier = Modifier.testTag(LOADING_TAG)) }

        compose.onNodeWithContentDescription(strings.loading).assertIsDisplayed()
        val message = compose.onNodeWithText(strings.loading, useUnmergedTree = true).assertIsDisplayed()
        assertEquals(scheme.onSurfaceVariant, message.textColor())
        compose.onNodeWithTag(LOADING_TAG).assert(isIndeterminateProgress)
    }

    @Test
    fun `loading in the light theme`() = assertLoading(darkTheme = false, LightColorScheme)

    @Test
    fun `loading in the dark theme`() = assertLoading(darkTheme = true, DarkColorScheme)

    private fun assertStatusChips(darkTheme: Boolean, colors: PhopencodeStatusColors) {
        compose.setThemedContent(darkTheme) {
            Column {
                StatusTone.entries.forEach { StatusChip(status = it) }
            }
        }

        listOf(
            strings.statusSuccess to colors.success,
            strings.statusWarning to colors.warning,
            strings.statusDanger to colors.danger,
            strings.statusInfo to colors.info,
        ).forEach { (label, color) ->
            val chip = compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            assertEquals(color.onContainer, chip.textColor())
        }
    }

    @Test
    fun `status chips in the light theme`() = assertStatusChips(darkTheme = false, LightStatusColors)

    @Test
    fun `status chips in the dark theme`() = assertStatusChips(darkTheme = true, DarkStatusColors)

    private fun assertConfirmDialog(darkTheme: Boolean, scheme: ColorScheme) {
        compose.setThemedContent(darkTheme) {
            ConfirmDialog(title = SCREEN_TITLE, message = MESSAGE, onConfirm = {}, onDismiss = {})
        }

        // The confirm button is the dialog's one filled button, cancel a text button.
        val confirm = compose.onNodeWithText(strings.confirm, useUnmergedTree = true).assertIsDisplayed()
        assertEquals(scheme.onPrimary, confirm.textColor())
        val cancel = compose.onNodeWithText(strings.cancel, useUnmergedTree = true).assertIsDisplayed()
        assertEquals(scheme.primary, cancel.textColor())
    }

    @Test
    fun `the confirm dialog in the light theme`() = assertConfirmDialog(darkTheme = false, LightColorScheme)

    @Test
    fun `the confirm dialog in the dark theme`() = assertConfirmDialog(darkTheme = true, DarkColorScheme)

    private fun assertDestructiveDialog(darkTheme: Boolean, scheme: ColorScheme) {
        compose.setThemedContent(darkTheme) {
            ConfirmDialog(title = SCREEN_TITLE, message = MESSAGE, onConfirm = {}, onDismiss = {}, destructive = true)
        }

        compose.onNodeWithContentDescription(strings.dangerousAction).assertIsDisplayed()
        assertEquals(scheme.onError, compose.onNodeWithText(strings.confirm, useUnmergedTree = true).textColor())
    }

    @Test
    fun `the destructive dialog in the light theme`() = assertDestructiveDialog(darkTheme = false, LightColorScheme)

    @Test
    fun `the destructive dialog in the dark theme`() = assertDestructiveDialog(darkTheme = true, DarkColorScheme)
}
