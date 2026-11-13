package io.github.thewisekd.phopencode.feature.preview.logic.viewport

/**
 * The user agent of the desktop viewport (P13.15): a desktop Chrome on Linux, in the reduced form
 * Chrome itself sends (`Chrome/<major>.0.0.0`), so dev servers and frameworks that sniff the user
 * agent serve their desktop page.
 *
 * Only the major version is taken from the WebView's own user agent, and only as digits; the rest
 * is fixed. So the result never holds `Mobile`, `Android`, the WebView marker (`wv`), the device's
 * model or its build, whatever the default says.
 */
object DesktopUserAgent {
    /** The Chrome major version used when the default names none, or an implausible one. */
    const val FALLBACK_MAJOR = 120

    private const val MAX_MAJOR = 9999

    private val CHROME_MAJOR = Regex("""Chrome/(\d{1,4})(?:\.\d+)*(?![\w])""")

    /** The desktop user agent for a WebView whose own user agent is [default]. */
    fun from(default: String): String {
        val major = CHROME_MAJOR.find(default)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 1..MAX_MAJOR }
            ?: FALLBACK_MAJOR
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
    }
}
