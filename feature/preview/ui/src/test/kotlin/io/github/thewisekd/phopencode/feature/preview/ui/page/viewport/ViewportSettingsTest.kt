package io.github.thewisekd.phopencode.feature.preview.ui.page.viewport

import android.content.Context
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ViewportMode
import io.github.thewisekd.phopencode.feature.preview.logic.viewport.DesktopUserAgent
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What each mode sets on the WebView's settings. Robolectric's settings only record the values; a
 * device's WebView taking them is checked end to end in P13.20.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class ViewportSettingsTest {
    private val default =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7; wv) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.1.2 Mobile Safari/537.36"

    private val view = WebView(ApplicationProvider.getApplicationContext<Context>())

    private fun assertMobile() = with(view.settings) {
        assertNull(userAgentString?.takeIf { it == DesktopUserAgent.from(default) }, "the desktop user agent stayed")
        assertFalse(useWideViewPort)
        assertFalse(loadWithOverviewMode)
        assertFalse(builtInZoomControls)
    }

    @Test
    fun `desktop sets the desktop user agent, a wide viewport, overview mode and pinch zoom`() {
        ViewportSettings.apply(view.settings, ViewportMode.Desktop) { default }

        with(view.settings) {
            assertEquals(DesktopUserAgent.from(default), userAgentString)
            assertFalse(userAgentString.contains("Mobile"))
            assertFalse(userAgentString.contains("Android"))
            assertTrue(useWideViewPort)
            assertTrue(loadWithOverviewMode)
            assertTrue(supportZoom())
            assertTrue(builtInZoomControls)
            assertFalse(displayZoomControls, "no on-screen zoom buttons over the page")
        }
    }

    @Test
    fun `mobile puts every setting back to the WebView's own`() {
        ViewportSettings.apply(view.settings, ViewportMode.Desktop) { default }

        ViewportSettings.apply(view.settings, ViewportMode.Mobile) { error("mobile needs no default user agent") }

        assertMobile()
    }

    @Test
    fun `a new WebView in mobile mode keeps its defaults`() {
        ViewportSettings.apply(view.settings, ViewportMode.Mobile) { default }

        assertMobile()
    }
}
