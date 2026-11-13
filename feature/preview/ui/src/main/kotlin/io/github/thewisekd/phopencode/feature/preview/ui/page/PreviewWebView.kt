package io.github.thewisekd.phopencode.feature.preview.ui.page

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.webkit.DownloadListener
import android.webkit.WebView

/** How far, in dp, the page is pulled down past its top before letting go reloads it. */
private const val PULL_THRESHOLD_DP = 96f

/**
 * The preview's WebView. Made only by [PreviewWebViews.create], which isolates it; this class adds
 * pull to refresh, which Compose cannot see through a WebView: the WebView does not take part in
 * nested scrolling. While a finger is down, the overscroll the WebView reports past the page's top
 * counts as a pull; letting go past [PULL_THRESHOLD_DP] calls [onPullRelease]. A page that scrolls
 * an inner box uses up the drag itself, so it reloads only once that box is at its top too.
 *
 * Open for the instrumented tests, which record what clearing the data calls.
 */
@SuppressLint("ViewConstructor")
internal open class PreviewWebView(context: Context) : WebView(context) {
    /** How far the page is pulled, 0..1; 0 when the pull ends. */
    var onPull: (Float) -> Unit = {}

    /** The finger left past the threshold. */
    var onPullRelease: () -> Unit = {}

    /** The listener [PreviewWebViews.create] set; kept so that the tests can call it. */
    var downloads: DownloadListener? = null
        private set

    private val threshold = PULL_THRESHOLD_DP * resources.displayMetrics.density
    private var touching = false
    private var pulled = 0f

    override fun setDownloadListener(listener: DownloadListener?) {
        downloads = listener
        super.setDownloadListener(listener)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                setPulled(0f)
            }
            MotionEvent.ACTION_UP -> {
                val release = pulled >= threshold
                endPull()
                if (release) onPullRelease()
            }
            MotionEvent.ACTION_CANCEL -> endPull()
        }
        return super.onTouchEvent(event)
    }

    override fun overScrollBy(
        deltaX: Int,
        deltaY: Int,
        scrollX: Int,
        scrollY: Int,
        scrollRangeX: Int,
        scrollRangeY: Int,
        maxOverScrollX: Int,
        maxOverScrollY: Int,
        isTouchEvent: Boolean,
    ): Boolean {
        // The WebView reports overscroll after the renderer's answer, so isTouchEvent may be false
        // while the finger is still down: the gesture is tracked here instead.
        if (touching && scrollY <= 0) {
            if (deltaY < 0) {
                setPulled(pulled - deltaY)
            } else if (deltaY > 0 && pulled > 0f) {
                setPulled((pulled - deltaY).coerceAtLeast(0f))
            }
        }
        return super.overScrollBy(
            deltaX, deltaY, scrollX, scrollY, scrollRangeX, scrollRangeY, maxOverScrollX, maxOverScrollY, isTouchEvent,
        )
    }

    private fun endPull() {
        touching = false
        setPulled(0f)
    }

    private fun setPulled(distance: Float) {
        pulled = distance
        onPull((distance / threshold).coerceIn(0f, 1f))
    }
}
