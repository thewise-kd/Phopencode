package io.github.thewisekd.phopencode

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.VectorDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * P12.2: the launch theme's splash screen, done with theme attributes only.
 *
 * - API 31+: the system splash screen shows the launcher foreground on the icon background color,
 *   over core:ui's background; the window background behind Compose is that plain color.
 * - Below 31: the window background is the same picture, drawn as a layer list.
 *
 * The Pi runs Robolectric 26 only, so the API 31+ tests (sdk 36) run on CI.
 */
@RunWith(RobolectricTestRunner::class)
class SplashScreenTest {
    @get:Rule
    val koin = StopKoinRule()

    private val context: Context by lazy {
        ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_Phopencode)
    }

    private fun windowBackground(): Drawable {
        val values = context.obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
        try {
            return checkNotNull(values.getDrawable(0)) { "no window background" }
        } finally {
            values.recycle()
        }
    }

    private fun colorBackground(): Int {
        val values = context.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
        try {
            return values.getColor(0, 0)
        } finally {
            values.recycle()
        }
    }

    private fun assertWindowIcon(background: Int) {
        assertEquals(background, colorBackground())
        val layers = assertIs<LayerDrawable>(windowBackground())
        assertEquals(3, layers.numberOfLayers)
        assertEquals(background, assertIs<ColorDrawable>(layers.getDrawable(0)).color)
        val badge = assertIs<GradientDrawable>(layers.getDrawable(1))
        assertEquals(context.getColor(R.color.ic_launcher_background), badge.color?.defaultColor)
        assertIs<VectorDrawable>(layers.getDrawable(2))
        assertEquals(Gravity.CENTER, layers.getLayerGravity(1))
        assertEquals(Gravity.CENTER, layers.getLayerGravity(2))
        // The icon is 240 dp over a 160 dp badge, the sizes of the API 31+ splash screen.
        val density = context.resources.displayMetrics.density
        assertEquals((160 * density).toInt(), layers.getLayerWidth(1))
        assertEquals((240 * density).toInt(), layers.getLayerWidth(2))
    }

    private fun assertSplash(background: Int) {
        assertEquals(background, colorBackground())
        assertEquals(background, assertIs<ColorDrawable>(windowBackground()).color)
        val attrs = intArrayOf(
            android.R.attr.windowSplashScreenBackground,
            android.R.attr.windowSplashScreenAnimatedIcon,
            android.R.attr.windowSplashScreenIconBackgroundColor,
        )
        val values = context.obtainStyledAttributes(attrs)
        try {
            assertEquals(background, values.getColor(0, 0))
            assertEquals(R.drawable.ic_launcher_foreground, values.getResourceId(1, 0))
            assertEquals(context.getColor(R.color.ic_launcher_background), values.getColor(2, 0))
        } finally {
            values.recycle()
        }
    }

    @Test
    @Config(sdk = [26], qualifiers = "notnight")
    fun `below API 31 the light window background shows the icon`() {
        assertWindowIcon(LIGHT_BACKGROUND)
    }

    @Test
    @Config(sdk = [26], qualifiers = "night")
    fun `below API 31 the dark window background shows the icon`() {
        assertWindowIcon(DARK_BACKGROUND)
    }

    @Test
    @Config(sdk = [36], qualifiers = "notnight")
    fun `from API 31 the light splash screen shows the icon`() {
        assertSplash(LIGHT_BACKGROUND)
    }

    @Test
    @Config(sdk = [36], qualifiers = "night")
    fun `from API 31 the dark splash screen shows the icon`() {
        assertSplash(DARK_BACKGROUND)
    }

    private companion object {
        /** core:ui's light background, Neutral98. */
        const val LIGHT_BACKGROUND = 0xFFF4FAF9.toInt()

        /** core:ui's dark background, Ink6. */
        const val DARK_BACKGROUND = 0xFF0B1517.toInt()
    }
}
