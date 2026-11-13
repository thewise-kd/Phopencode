package io.github.thewisekd.phopencode.feature.preview.logic.viewport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The desktop user agent: a desktop Chrome on Linux, never `Mobile` or `Android`, no device details. */
class DesktopUserAgentTest {
    private val webViewDefault =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/UQ1A.240205.004; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/121.0.6167.178 Mobile Safari/537.36"

    private fun assertDesktop(agent: String) {
        assertFalse(agent.contains("mobile", ignoreCase = true), "Mobile in the desktop user agent")
        assertFalse(agent.contains("android", ignoreCase = true), "Android in the desktop user agent")
        assertFalse(agent.contains("; wv"), "the WebView marker in the desktop user agent")
    }

    @Test
    fun `it keeps the WebView's Chrome major version and drops the device`() {
        val agent = DesktopUserAgent.from(webViewDefault)

        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
            agent,
        )
        assertDesktop(agent)
        assertFalse(agent.contains("Pixel"))
        assertFalse(agent.contains("Build/"))
    }

    @Test
    fun `without a Chrome version it falls back to a fixed one`() {
        val agent = DesktopUserAgent.from("Mozilla/5.0 (Linux; Android 9; Mobile) Something/1.0")

        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/${DesktopUserAgent.FALLBACK_MAJOR}.0.0.0 Safari/537.36",
            agent,
        )
        assertDesktop(agent)
    }

    @Test
    fun `nothing from the default but the digits of the major version reaches the result`() {
        val hostile = listOf(
            "",
            "Chrome/",
            "Chrome/abc Mobile",
            "Chrome/99999999999999999999.1 Android",
            "Chrome/120Mobile.0 Android",
            "Chrome/0.1.2.3",
        )
        hostile.forEach { default ->
            val agent = DesktopUserAgent.from(default)
            assertDesktop(agent)
            val major = Regex("""Chrome/(\d+)\.0\.0\.0 Safari/537\.36$""").find(agent)?.groupValues?.get(1)
            assertEquals(true, major != null && major.length <= 4 && major.toInt() > 0, "major of $default: $agent")
        }
    }

    @Test
    fun `a plausible major version is kept, an implausible one falls back`() {
        assertEquals(true, DesktopUserAgent.from("Chrome/130.0.1.2").contains("Chrome/130.0.0.0"))
        assertEquals(true, DesktopUserAgent.from("Chrome/0.0.1.2").contains("Chrome/${DesktopUserAgent.FALLBACK_MAJOR}.0.0.0"))
        assertEquals(
            true,
            DesktopUserAgent.from("Chrome/99999999999999999999.1").contains("Chrome/${DesktopUserAgent.FALLBACK_MAJOR}.0.0.0"),
        )
    }
}
