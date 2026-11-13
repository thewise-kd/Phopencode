package io.github.thewisekd.phopencode.feature.preview.logic.viewport

import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageStatus
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ViewportMode

/** What [PageIntent.SetViewport] does to the page. */
sealed interface ViewportChange {
    /** Nothing: the page has that mode already, or it is closed. */
    data object None : ViewportChange

    /**
     * The page takes [state], whose only change is its viewport. With [reload], the page logic then
     * loads the page again, as it does for [PageIntent.Reload] on the open bridge, so the new user
     * agent and viewport take effect.
     */
    data class Apply(val state: PageState, val reload: Boolean) : ViewportChange
}

/**
 * The viewport toggle's rules (P13.15), as pure steps the page logic runs under its lock:
 *
 * - The mode lives in [PageState.viewport], so it holds across rotation for as long as the page
 *   logic does, that is while the preview is open; closing resets it to [ViewportMode.Mobile]
 *   (P13.11), and a closed page takes no other mode.
 * - A new mode on an open bridge ([PageState.origin] set) reloads the page.
 * - Without a bridge (still opening, connection lost, failed to open) the mode is only kept, and
 *   the next load uses it: switching never opens a tunnel by itself.
 */
object ViewportSwitch {
    /** What asking for [mode] does to [state]. */
    fun switch(state: PageState, mode: ViewportMode): ViewportChange {
        if (state.status == PageStatus.Closed || state.viewport == mode) return ViewportChange.None
        return ViewportChange.Apply(state.copy(viewport = mode), reload = state.origin != null)
    }

    /** The toggle's intent: the mode other than [current]. */
    fun toggle(current: ViewportMode): PageIntent.SetViewport = PageIntent.SetViewport(
        when (current) {
            ViewportMode.Mobile -> ViewportMode.Desktop
            ViewportMode.Desktop -> ViewportMode.Mobile
        },
    )
}
