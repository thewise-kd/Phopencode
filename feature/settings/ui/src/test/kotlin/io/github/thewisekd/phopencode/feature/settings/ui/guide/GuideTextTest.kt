package io.github.thewisekd.phopencode.feature.settings.ui.guide

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import io.github.thewisekd.phopencode.feature.settings.ui.R
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The guide's text, in both languages, is the same set of strings and holds nothing private: no
 * server address but the loopback one it teaches, no key, no ntfy topic or link.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class GuideTextTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Every `settings_guide_` string, by name. */
    private val ids: Map<String, Int> = R.string::class.java.fields
        .filter { it.name.startsWith("settings_guide_") }
        .associate { it.name to it.getInt(null) }

    private fun texts(language: String): Map<String, String> {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        val localized = context.createConfigurationContext(configuration)
        return ids.mapValues { (_, id) -> localized.getString(id) }
    }

    @Test
    fun `every page has a title and a text`() {
        for (key in listOf("welcome", "server", "add_server", "sessions", "files_git", "notifications", "preview", "done")) {
            assertTrue("settings_guide_${key}_title" in ids, key)
            assertTrue("settings_guide_${key}_body" in ids, key)
        }
    }

    @Test
    fun `the Turkish text is translated`() {
        val english = texts("en")
        val turkish = texts("tr")

        assertEquals(english.keys, turkish.keys)
        val untranslated = english.filter { (name, text) -> turkish[name] == text && text.any(Char::isLetter) }
        assertEquals(emptyMap(), untranslated)
    }

    @Test
    fun `no address, key or topic is in the text`() {
        val ipv4 = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""")
        for (language in listOf("en", "tr")) {
            for ((name, text) in texts(language)) {
                val where = "$language $name: $text"
                assertFalse("://" in text, where)
                assertFalse("@" in text, where)
                assertFalse("ntfy.sh/" in text, where)
                assertFalse("ssh-" in text.lowercase(), where)
                assertFalse("BEGIN" in text, where)
                assertFalse(".com" in text || ".net" in text || ".org" in text, where)
                assertTrue(ipv4.findAll(text).all { it.value == "127.0.0.1" }, where)
            }
        }
    }
}
