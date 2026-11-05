package io.github.thewisekd.phopencode.feature.files.ui.viewer

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals

/**
 * A file's size follows the screen's language when it changes while shown, read from the
 * composition's configuration rather than the process default, which is set to neither language.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class ViewerLocaleTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var saved: Locale

    @Before
    fun otherDefault() {
        saved = Locale.getDefault()
        // A decimal comma, as Turkish has: a read of the default would pass for Turkish in English.
        Locale.setDefault(Locale.GERMANY)
    }

    @After
    fun restoreDefault() {
        Locale.setDefault(saved)
    }

    @Test
    fun `the size changes format when the language changes`() {
        var locale by mutableStateOf(Locale.ENGLISH)
        var shown = ""
        compose.setContent { InLocale(locale) { shown = formatSize(1_536) } }

        compose.waitForIdle()
        assertEquals("1.5 KB", shown)

        compose.runOnIdle { locale = Locale.forLanguageTag("tr") }
        compose.waitForIdle()
        assertEquals("1,5 KB", shown)

        compose.runOnIdle { locale = Locale.ENGLISH }
        compose.waitForIdle()
        assertEquals("1.5 KB", shown)
    }
}

/** [content] as the app draws it after a language change to [locale]: configuration and resources both. */
@Composable
private fun InLocale(locale: Locale, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val outer = LocalConfiguration.current
    val config = remember(outer, locale) {
        Configuration(outer).apply { setLocales(LocaleList(locale)) }
    }
    val context = remember(config) { base.createConfigurationContext(config) }
    CompositionLocalProvider(
        LocalConfiguration provides config,
        LocalContext provides context,
        LocalResources provides context.resources,
        content = content,
    )
}
