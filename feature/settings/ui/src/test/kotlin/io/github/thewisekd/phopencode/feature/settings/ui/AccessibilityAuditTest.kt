package io.github.thewisekd.phopencode.feature.settings.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.thewisekd.phopencode.core.navigation.SettingsRoute
import io.github.thewisekd.phopencode.feature.settings.logic.contract.DataDeletion
import io.github.thewisekd.phopencode.feature.settings.logic.contract.FileSizeProblem
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesError
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesState
import io.github.thewisekd.phopencode.feature.settings.ui.prefs.PreferencesScreen
import io.github.thewisekd.phopencode.feature.settings.ui.prefs.loadedState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The preferences, loaded, failed, loading and asking, at the largest font: every control is named and
 * big enough to tap, and no text is cut off. The licenses screen is still a stub, drawn by P12.3.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class AccessibilityAuditTest {
    @get:Rule
    val compose = createComposeRule()

    private fun preferences(state: PreferencesState) {
        compose.setThemedAt { Surface { PreferencesScreen(SettingsRoute.Preferences, state, {}) } }
        compose.assertAccessible()
    }

    @Test
    fun `the preferences`() = preferences(loadedState)

    @Test
    fun `the preferences while loading`() = preferences(PreferencesState())

    @Test
    fun `the preferences with a wrong size and a failed save`() {
        preferences(
            loadedState.copy(fileSizeProblem = FileSizeProblem.NotANumber, error = PreferencesError.SaveFailed),
        )
        compose.assertErrorAnnounced(R.string.settings_prefs_error_save)
    }

    @Test
    fun `the preferences asking before deleting the data`() =
        preferences(loadedState.copy(deletion = DataDeletion.Confirming))
}
