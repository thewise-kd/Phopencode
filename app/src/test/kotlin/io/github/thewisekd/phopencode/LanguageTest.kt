package io.github.thewisekd.phopencode

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.thewisekd.phopencode.core.ui.R as UiR
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import kotlin.test.assertEquals

/**
 * The app has no language of its own until the settings screen offers one: its screens read
 * resources in the system language. A shared string stands in for every screen, because the
 * start screen changes in later tasks. The levels of `MainActivityTest`; the Pi passes 26 only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class LanguageTest {
    @get:Rule
    val koin = StopKoinRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun assertScreensShow(text: String) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(text, activity.getString(UiR.string.ui_common_empty_title))
            }
        }
    }

    @Test
    @Config(qualifiers = "en")
    fun `an English system shows English`() {
        assertScreensShow("Nothing here yet")
    }

    @Test
    @Config(qualifiers = "tr")
    fun `a Turkish system shows Turkish`() {
        assertScreensShow("Burada henüz bir şey yok")
    }

    @Test
    @Config(qualifiers = "fr")
    fun `another system language falls back to English`() {
        assertScreensShow("Nothing here yet")
    }

    @Test
    fun `the locale config offers English and Turkish`() {
        val locales = mutableListOf<String>()
        context.resources.getXml(R.xml.locales_config).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                    locales += parser.getAttributeValue(ANDROID_NAMESPACE, "name")
                }
            }
        }

        assertEquals(listOf("en", "tr"), locales)
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
