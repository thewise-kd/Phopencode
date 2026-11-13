package io.github.thewisekd.phopencode.feature.preview.ui.page.viewport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.thewisekd.phopencode.feature.preview.logic.contract.LoadRequest
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageStatus
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ViewportMode
import io.github.thewisekd.phopencode.feature.preview.ui.page.PreviewWebHolder
import io.github.thewisekd.phopencode.feature.preview.ui.page.PreviewWebView
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The WebView holder sets the page's viewport mode before each load, so that a switch, which the
 * page logic answers with a new load request, loads the page in the new mode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class ViewportLoadTest {
    /** What the settings were when a load started. */
    private data class AtLoad(val url: String, val userAgent: String?, val wideViewport: Boolean, val overview: Boolean)

    private val loads = mutableListOf<AtLoad>()

    private inner class RecordingView(context: Context) : PreviewWebView(context) {
        override fun loadUrl(url: String) {
            loads += AtLoad(url, settings.userAgentString, settings.useWideViewPort, settings.loadWithOverviewMode)
        }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val holder = PreviewWebHolder { viewContext, _ -> RecordingView(viewContext) }

    private fun page(id: Long, viewport: ViewportMode) = PageState(
        port = 5173,
        status = PageStatus.Opening,
        load = LoadRequest(id, "http://127.0.0.1:41234/page$id"),
        viewport = viewport,
    )

    @Test
    fun `each load starts with the page's mode set`() {
        holder.sync(context, page(1, ViewportMode.Mobile))
        holder.sync(context, page(2, ViewportMode.Desktop))
        holder.sync(context, page(3, ViewportMode.Mobile))

        assertEquals(3, loads.size)
        val (mobile, desktop, back) = loads

        assertFalse(mobile.wideViewport)
        assertFalse(mobile.overview)

        assertTrue(desktop.wideViewport)
        assertTrue(desktop.overview)
        val agent = desktop.userAgent.orEmpty()
        assertTrue(agent.contains("X11; Linux x86_64"), "the desktop user agent was not set before the load")
        assertFalse(agent.contains("Mobile"))
        assertFalse(agent.contains("Android"))

        assertFalse(back.wideViewport)
        assertFalse(back.overview)
        assertFalse(back.userAgent.orEmpty().contains("X11"), "the desktop user agent stayed")
    }

    @Test
    fun `a mode without a new load request loads nothing`() {
        holder.sync(context, page(1, ViewportMode.Mobile))
        holder.sync(context, page(1, ViewportMode.Desktop))

        assertEquals(1, loads.size)
    }
}
