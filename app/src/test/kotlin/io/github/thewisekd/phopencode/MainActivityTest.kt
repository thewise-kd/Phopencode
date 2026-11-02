package io.github.thewisekd.phopencode

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * The lowest level the app supports and the highest level Robolectric 4.17 can run:
 * the manifest's target of 37 is newer, so the level cannot be left to its default.
 * Level 36 needs Robolectric's native runtime, which has no arm64 Linux build: on the
 * Pi only level 26 passes, and x86-64 CI runs both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class MainActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the launcher opens an empty screen`() {
        val launcher = assertNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        ActivityScenario.launch<MainActivity>(launcher).use { scenario ->
            scenario.onActivity { assertIs<MainActivity>(it) }
            compose.onNodeWithTag(EMPTY_SCREEN_TAG).assertIsDisplayed().onChildren().assertCountEquals(0)
        }
    }

    @Test
    fun `the merged manifest declares API 26 as the minimum`() {
        assertEquals(26, context.applicationInfo.minSdkVersion)
    }
}
