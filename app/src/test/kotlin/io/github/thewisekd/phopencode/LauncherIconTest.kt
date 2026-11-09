package io.github.thewisekd.phopencode

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import kotlin.test.assertEquals

/**
 * P12.2: the launcher icon is adaptive on every level the app supports (minSdk 26), with a
 * monochrome layer for themed icons, and its layers are vector drawables. The levels of
 * `MainActivityTest`; the Pi passes 26 only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class LauncherIconTest {
    @get:Rule
    val koin = StopKoinRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The root element's name and, for each child element, its name and `android:drawable`. */
    private fun read(id: Int): Pair<String, Map<String, Int>> = context.resources.getXml(id).use { parser ->
        var root: String? = null
        val children = linkedMapOf<String, Int>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (root == null) {
                root = parser.name
            } else {
                children[parser.name] = parser.getAttributeResourceValue(ANDROID_NAMESPACE, "drawable", 0)
            }
        }
        checkNotNull(root) { "empty XML resource" } to children
    }

    private fun assertAdaptive(id: Int) {
        val (root, layers) = read(id)
        assertEquals("adaptive-icon", root)
        assertEquals(
            mapOf(
                "background" to R.color.ic_launcher_background,
                "foreground" to R.drawable.ic_launcher_foreground,
                "monochrome" to R.drawable.ic_launcher_monochrome,
            ),
            layers,
        )
    }

    @Test
    fun `the launcher icon is adaptive with a monochrome layer`() {
        assertAdaptive(R.mipmap.ic_launcher)
    }

    @Test
    fun `the round launcher icon is adaptive with a monochrome layer`() {
        assertAdaptive(R.mipmap.ic_launcher_round)
    }

    @Test
    fun `the foreground and monochrome layers are vectors`() {
        assertEquals("vector", read(R.drawable.ic_launcher_foreground).first)
        assertEquals("vector", read(R.drawable.ic_launcher_monochrome).first)
    }

    @Test
    fun `the icon background is core ui's Teal20`() {
        assertEquals(0xFF003737.toInt(), context.getColor(R.color.ic_launcher_background))
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
