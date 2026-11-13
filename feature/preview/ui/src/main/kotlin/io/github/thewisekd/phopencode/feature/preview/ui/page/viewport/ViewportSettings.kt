package io.github.thewisekd.phopencode.feature.preview.ui.page.viewport

import android.content.Context
import android.webkit.WebSettings
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ViewportMode
import io.github.thewisekd.phopencode.feature.preview.logic.viewport.ViewportProfile

/**
 * Sets a [ViewportMode] on the preview's WebView (P13.15). Only the user agent and the viewport
 * settings change; nothing of the WebView's isolation (P13.13) is touched.
 *
 * Call it before each load, on the main thread: a new user agent takes effect on the next load, and
 * the page logic reloads the page when the mode changes. The WebView, with its settings, is kept
 * across rotation, and a new one starts in [ViewportMode.Mobile] with its own defaults.
 */
internal object ViewportSettings {
    /** Sets [mode] on [settings]; [defaultUserAgent] is asked only for the desktop mode. */
    fun apply(settings: WebSettings, mode: ViewportMode, defaultUserAgent: () -> String) =
        apply(settings, ViewportProfile.of(mode, defaultUserAgent))

    /** Sets [mode] on [settings], deriving the desktop user agent from the WebView's own. */
    fun apply(settings: WebSettings, mode: ViewportMode, context: Context) =
        apply(settings, mode) { WebSettings.getDefaultUserAgent(context.applicationContext) }

    private fun apply(settings: WebSettings, profile: ViewportProfile) = with(settings) {
        // Null asks the WebView for its own default.
        userAgentString = profile.userAgent
        useWideViewPort = profile.wideViewport
        loadWithOverviewMode = profile.overviewMode
        setSupportZoom(true)
        builtInZoomControls = profile.zoom
        displayZoomControls = false
    }
}
