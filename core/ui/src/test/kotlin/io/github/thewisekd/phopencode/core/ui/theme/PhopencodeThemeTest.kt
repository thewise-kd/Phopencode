package io.github.thewisekd.phopencode.core.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame

private const val SCREEN_TAG = "theme-test-screen"
private const val SCREEN_TEXT = "Sample"

/** The levels of `MainActivityTest`: level 36 has no arm64 Linux runtime, so the Pi passes 26 only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class PhopencodeThemeTest {
    @get:Rule
    val compose = createComposeRule()

    private var scheme: ColorScheme? = null

    @Composable
    private fun CaptureScheme() {
        scheme = MaterialTheme.colorScheme
    }

    private fun assertScheme(expected: ColorScheme) {
        compose.runOnIdle { assertSame(expected, assertNotNull(scheme)) }
    }

    private fun assertScreenOpens(darkTheme: Boolean, expected: ColorScheme, status: PhopencodeStatusColors) {
        var content: Color? = null
        var warning: StatusColor? = null
        compose.setContent {
            PhopencodeTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize().testTag(SCREEN_TAG), color = MaterialTheme.colorScheme.background) {
                    content = LocalContentColor.current
                    warning = PhopencodeTheme.status.warning
                    Column(modifier = Modifier.padding(PhopencodeTheme.spacing.lg)) {
                        Text(text = SCREEN_TEXT, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        compose.onNodeWithTag(SCREEN_TAG).assertIsDisplayed()
        compose.onNodeWithText(SCREEN_TEXT).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(expected.onBackground, content)
            assertEquals(status.warning, warning)
        }
    }

    @Test
    fun `a screen opens in the light theme`() {
        assertScreenOpens(darkTheme = false, LightColorScheme, LightStatusColors)
    }

    @Test
    fun `a screen opens in the dark theme`() {
        assertScreenOpens(darkTheme = true, DarkColorScheme, DarkStatusColors)
    }

    @Test
    @Config(qualifiers = "notnight")
    fun `the default follows a light system setting`() {
        compose.setContent { PhopencodeTheme { CaptureScheme() } }
        assertScheme(LightColorScheme)
    }

    @Test
    @Config(qualifiers = "night")
    fun `the default follows a dark system setting`() {
        compose.setContent { PhopencodeTheme { CaptureScheme() } }
        assertScheme(DarkColorScheme)
    }

    @Test
    @Config(qualifiers = "night")
    fun `an explicit light theme overrides a dark system setting`() {
        compose.setContent { PhopencodeTheme(darkTheme = false) { CaptureScheme() } }
        assertScheme(LightColorScheme)
    }

    @Test
    @Config(qualifiers = "notnight")
    fun `an explicit dark theme overrides a light system setting`() {
        compose.setContent { PhopencodeTheme(darkTheme = true) { CaptureScheme() } }
        assertScheme(DarkColorScheme)
    }

    @Test
    fun `changing the theme setting recolors a running screen`() {
        var dark by mutableStateOf(false)
        var status: PhopencodeStatusColors? = null
        compose.setContent {
            PhopencodeTheme(darkTheme = dark) {
                CaptureScheme()
                status = PhopencodeTheme.status
            }
        }
        assertScheme(LightColorScheme)
        compose.runOnIdle { assertSame(LightStatusColors, status) }

        dark = true

        assertScheme(DarkColorScheme)
        compose.runOnIdle { assertSame(DarkStatusColors, status) }
    }

    @Test
    fun `the theme provides the typography and code tokens`() {
        var typography: Typography? = null
        var code: CodeTypography? = null
        compose.setContent {
            PhopencodeTheme {
                typography = MaterialTheme.typography
                code = PhopencodeTheme.codeTypography
            }
        }
        compose.runOnIdle {
            assertSame(PhopencodeTypography, typography)
            assertSame(PhopencodeCodeTypography, code)
        }
    }

    @Test
    fun `spacing is readable from a composable nested below the theme`() {
        var spacing: PhopencodeSpacing? = null
        compose.setContent {
            PhopencodeTheme {
                Surface {
                    Column { spacing = PhopencodeTheme.spacing }
                }
            }
        }
        compose.runOnIdle { assertEquals(PhopencodeSpacing(), spacing) }
    }

    @Test
    fun `status colors outside the theme fail instead of showing the wrong scheme`() {
        assertFailsWith<IllegalStateException> {
            compose.setContent { PhopencodeTheme.status }
        }
    }
}
