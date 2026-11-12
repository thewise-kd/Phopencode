package io.github.thewisekd.phopencode.feature.preview.logic.contract

/**
 * How the WebView presents the page (P13.15). Holds while the preview is open, across rotation, and
 * resets to [Mobile] when it closes; switching reloads the page.
 */
enum class ViewportMode {
    /** The WebView's own user agent and viewport. */
    Mobile,

    /** A user agent without `Mobile` and `Android`, a wide viewport, overview mode and zoom. */
    Desktop,
}
