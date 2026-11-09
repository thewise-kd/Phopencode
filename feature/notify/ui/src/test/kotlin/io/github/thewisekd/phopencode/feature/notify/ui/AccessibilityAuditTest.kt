package io.github.thewisekd.phopencode.feature.notify.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.navigation.NotifyRoute
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyFailure
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingState
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupError
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupState
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupStep
import io.github.thewisekd.phopencode.feature.notify.ui.pending.ENGLISH_PENDING_STRINGS
import io.github.thewisekd.phopencode.feature.notify.ui.pending.PendingScreen
import io.github.thewisekd.phopencode.feature.notify.ui.pending.listed
import io.github.thewisekd.phopencode.feature.notify.ui.setup.SetupScreen
import io.github.thewisekd.phopencode.feature.notify.ui.setup.loadedState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/** Made-up texts for the audit's own checks. */
private const val STATUS = "Reading the list"
private const val ERROR = "The list could not be read"

/**
 * Every notify screen, in each of its states, at the largest font: every control is named and big
 * enough to tap, no text is cut off, and the empty and error states say so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class AccessibilityAuditTest {
    @get:Rule
    val compose = createComposeRule()

    private fun pending(state: PendingState) {
        compose.setThemedAt { PendingScreen(NotifyRoute.Pending, state, {}) }
    }

    private fun setup(state: SetupState) {
        compose.setThemedAt { Surface { SetupScreen(NotifyRoute.Setup, state, {}) } }
    }

    @Test
    fun `the audit catches an unnamed icon button, crowded small targets and cut off text`() {
        compose.setThemedAt {
            Column(verticalArrangement = Arrangement.spacedBy(48.dp)) {
                IconButton(onClick = {}) { Icon(PhopencodeIcons.Close, contentDescription = null) }
                Row {
                    Box(Modifier.size(32.dp).clickable {}.semantics { contentDescription = "first" })
                    Box(Modifier.size(32.dp).clickable {}.semantics { contentDescription = "second" })
                }
                Box(Modifier.height(10.dp)) { Text("A sentence far too tall for its box") }
            }
        }
        val error = assertFailsWith<AssertionError> { compose.assertAccessible() }.message.orEmpty()
        assertContains(error, "no text or content description")
        assertContains(error, "under 48 dp, it runs into \"second\"")
        assertContains(error, "cut off")
    }

    @Test
    fun `the audit lets an icon button in its own 48 dp slot pass`() {
        compose.setThemedAt {
            Row {
                IconButton(onClick = {}) { Icon(PhopencodeIcons.Close, contentDescription = "close") }
                IconButton(onClick = {}) { Icon(PhopencodeIcons.Close, contentDescription = "close too") }
            }
        }
        compose.assertAccessible()
    }

    @Test
    fun `an error outside any live region fails, though another live region is on screen`() {
        compose.setThemedAt {
            Column {
                Text(STATUS, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                // A live region that does not merge its children: changes to them are not read out.
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) { Text(ERROR) }
            }
        }
        val error = assertFailsWith<AssertionError> { compose.assertErrorAnnounced(ERROR) }.message.orEmpty()
        assertContains(error, "not in a live region")
    }

    @Test
    fun `an error that is not on screen fails`() {
        compose.setThemedAt { Text(STATUS, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        val error = assertFailsWith<AssertionError> { compose.assertErrorAnnounced(ERROR) }.message.orEmpty()
        assertContains(error, "not on screen")
    }

    @Test
    fun `an error in a live region of its own or of the row that merges it passes`() {
        compose.setThemedAt {
            Column {
                Text(ERROR, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Row(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive }) {
                    Icon(PhopencodeIcons.Close, contentDescription = null)
                    Text("$ERROR again")
                }
            }
        }
        compose.assertErrorAnnounced(ERROR)
    }

    @Test
    fun `the pending list with items`() {
        pending(listed)
        compose.assertAccessible()
    }

    @Test
    fun `the pending list while loading`() {
        pending(PendingState())
        compose.assertAccessible()
    }

    @Test
    fun `the empty pending list says nothing is waiting`() {
        pending(PendingState(loading = false))
        compose.onNodeWithText(ENGLISH_PENDING_STRINGS.empty).assertIsDisplayed()
        compose.assertAccessible()
    }

    @Test
    fun `the pending list's error`() {
        pending(PendingState(loading = false, error = NotifyFailure.Unreachable))
        compose.onNodeWithText(ENGLISH_PENDING_STRINGS.errors.getValue(NotifyFailure.Unreachable), substring = true)
            .assertIsDisplayed()
        compose.assertAccessible()
        compose.assertErrorAnnounced(R.string.notify_pending_error_unreachable)
    }

    @Test
    fun `the setup while loading`() {
        setup(SetupState())
        compose.assertAccessible()
    }

    @Test
    fun `the loaded setup`() {
        setup(loadedState)
        compose.assertAccessible()
    }

    @Test
    fun `the setup's error`() {
        setup(loadedState.copy(error = SetupError(SetupStep.Install, NotifyFailure.PythonMissing)))
        compose.assertAccessible()
        compose.assertErrorAnnounced(R.string.notify_setup_error_python_missing)
    }
}
