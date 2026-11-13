package io.github.thewisekd.phopencode.feature.preview.logic.viewport

import io.github.thewisekd.phopencode.feature.preview.logic.contract.ViewportMode

/**
 * What a [ViewportMode] sets on the preview's WebView (P13.15). The screen applies it to the
 * WebView's settings before each load; a user agent takes effect on the next load only, which is
 * why switching the mode reloads the page ([ViewportSwitch]).
 *
 * @property userAgent The user agent to send; null is the WebView's own default.
 * @property wideViewport Honour the page's `<meta name="viewport">` and lay it out as wide as a
 *   desktop browser when it has none.
 * @property overviewMode Zoom out so that the wide layout fits the screen's width.
 * @property zoom Pinch to zoom, without on-screen zoom buttons.
 */
data class ViewportProfile(
    val userAgent: String?,
    val wideViewport: Boolean,
    val overviewMode: Boolean,
    val zoom: Boolean,
) {
    override fun toString(): String =
        "ViewportProfile(userAgent=${if (userAgent == null) "default" else "desktop"}, " +
            "wideViewport=$wideViewport, overviewMode=$overviewMode, zoom=$zoom)"

    companion object {
        /** The WebView's own user agent and viewport: what it does with no setting changed. */
        val MOBILE = ViewportProfile(userAgent = null, wideViewport = false, overviewMode = false, zoom = false)

        /**
         * The profile of [mode]. [defaultUserAgent] gives the WebView's own user agent; it is asked
         * for the desktop mode only.
         */
        fun of(mode: ViewportMode, defaultUserAgent: () -> String): ViewportProfile = when (mode) {
            ViewportMode.Mobile -> MOBILE
            ViewportMode.Desktop -> ViewportProfile(
                userAgent = DesktopUserAgent.from(defaultUserAgent()),
                wideViewport = true,
                overviewMode = true,
                zoom = true,
            )
        }
    }
}
