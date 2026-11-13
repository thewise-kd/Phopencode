package io.github.thewisekd.phopencode.feature.preview.logic.viewport

import io.github.thewisekd.phopencode.feature.preview.logic.contract.ViewportMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What each mode sets on the WebView. */
class ViewportProfileTest {
    private val default = "Mozilla/5.0 (Linux; Android 14; Pixel 7; wv) AppleWebKit/537.36 Chrome/121.0.1.2 Mobile Safari/537.36"

    @Test
    fun `mobile is the WebView's own user agent and viewport`() {
        var asked = false
        val profile = ViewportProfile.of(ViewportMode.Mobile) {
            asked = true
            default
        }

        assertEquals(ViewportProfile.MOBILE, profile)
        assertNull(profile.userAgent, "null asks the WebView for its default user agent")
        assertFalse(profile.wideViewport)
        assertFalse(profile.overviewMode)
        assertFalse(profile.zoom)
        assertFalse(asked, "mobile needs no default user agent")
    }

    @Test
    fun `desktop is a desktop user agent, a wide viewport, overview mode and zoom`() {
        val profile = ViewportProfile.of(ViewportMode.Desktop) { default }

        assertEquals(DesktopUserAgent.from(default), profile.userAgent)
        assertTrue(profile.wideViewport)
        assertTrue(profile.overviewMode)
        assertTrue(profile.zoom)
    }

    @Test
    fun `every mode has a profile, and the two differ`() {
        val profiles = ViewportMode.entries.map { ViewportProfile.of(it) { default } }

        assertEquals(ViewportMode.entries.size, profiles.toSet().size)
    }

    @Test
    fun `it prints the user agent's kind only`() {
        val desktop = ViewportProfile.of(ViewportMode.Desktop) { default }

        assertFalse(desktop.toString().contains("Chrome"))
        assertTrue(desktop.toString().contains("desktop"))
        assertTrue(ViewportProfile.MOBILE.toString().contains("default"))
    }
}
