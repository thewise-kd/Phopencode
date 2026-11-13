package io.github.thewisekd.phopencode.feature.preview.logic.viewport

import io.github.thewisekd.phopencode.feature.preview.logic.contract.LoadError
import io.github.thewisekd.phopencode.feature.preview.logic.contract.LoadRequest
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageStatus
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewFailure
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewOrigin
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ViewportMode
import kotlin.test.Test
import kotlin.test.assertEquals

/** What [PageIntent.SetViewport] does to the page. */
class ViewportSwitchTest {
    private val origin = PreviewOrigin(BRIDGE_PORT)

    private val loaded = PageState(
        port = PORT,
        status = PageStatus.Loaded,
        origin = origin,
        load = LoadRequest(3, origin.url + "/"),
        addressText = "/app",
    )

    @Test
    fun `a new mode on an open page is kept and reloads it`() {
        val change = ViewportSwitch.switch(loaded, ViewportMode.Desktop)

        assertEquals(ViewportChange.Apply(loaded.copy(viewport = ViewportMode.Desktop), reload = true), change)
    }

    @Test
    fun `switching back to mobile reloads too`() {
        val desktop = loaded.copy(viewport = ViewportMode.Desktop)

        val change = ViewportSwitch.switch(desktop, ViewportMode.Mobile)

        assertEquals(ViewportChange.Apply(loaded, reload = true), change)
    }

    @Test
    fun `the mode it already has changes nothing`() {
        ViewportMode.entries.forEach { mode ->
            assertEquals(ViewportChange.None, ViewportSwitch.switch(loaded.copy(viewport = mode), mode))
        }
    }

    @Test
    fun `a page that failed on an open bridge reloads in the new mode`() {
        val failures = listOf(
            PageStatus.Opening,
            PageStatus.LoadFailed(LoadError.HttpStatus(500)),
            PageStatus.LoadFailed(LoadError.Network),
            PageStatus.LoadFailed(LoadError.NothingListens),
        )
        failures.forEach { status ->
            val state = loaded.copy(status = status)
            assertEquals(
                ViewportChange.Apply(state.copy(viewport = ViewportMode.Desktop), reload = true),
                ViewportSwitch.switch(state, ViewportMode.Desktop),
                "$status",
            )
        }
    }

    @Test
    fun `without a bridge the mode is kept for the next load, and nothing reconnects by itself`() {
        val withoutBridge = listOf(
            PageState(port = PORT),
            PageState(port = PORT, status = PageStatus.ConnectionLost),
            PageState(port = PORT, status = PageStatus.LoadFailed(LoadError.Failed(PreviewFailure.NotConnected))),
        )
        withoutBridge.forEach { state ->
            assertEquals(
                ViewportChange.Apply(state.copy(viewport = ViewportMode.Desktop), reload = false),
                ViewportSwitch.switch(state, ViewportMode.Desktop),
                "${state.status}",
            )
        }
    }

    @Test
    fun `a closed page ignores it, so the mode stays reset`() {
        val closed = PageState(port = PORT, status = PageStatus.Closed)

        ViewportMode.entries.forEach { mode ->
            assertEquals(ViewportChange.None, ViewportSwitch.switch(closed, mode))
        }
    }

    @Test
    fun `nothing else in the state changes`() {
        val asking = loaded.copy(addressText = "/typed", pendingExternal = null)

        val change = ViewportSwitch.switch(asking, ViewportMode.Desktop) as ViewportChange.Apply

        assertEquals(asking, change.state.copy(viewport = asking.viewport))
    }

    @Test
    fun `the toggle asks for the other mode`() {
        assertEquals(PageIntent.SetViewport(ViewportMode.Desktop), ViewportSwitch.toggle(ViewportMode.Mobile))
        assertEquals(PageIntent.SetViewport(ViewportMode.Mobile), ViewportSwitch.toggle(ViewportMode.Desktop))
    }

    private companion object {
        const val PORT = 5173
        const val BRIDGE_PORT = 41234
    }
}
