package io.github.thewisekd.phopencode.feature.sessions.ui.list

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
import io.github.thewisekd.phopencode.core.model.Cost
import io.github.thewisekd.phopencode.core.model.TokenUsage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Locale
import kotlin.test.assertEquals

/**
 * The row's tokens and cost follow the screen's language when it changes while shown, read from the
 * composition's configuration rather than the process default, which is set to neither language.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class SessionUsageLocaleTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var saved: Locale

    @Before
    fun otherDefault() {
        saved = Locale.getDefault()
        // Groups with a narrow space: a read of the default would show in every number.
        Locale.setDefault(Locale.FRANCE)
    }

    @After
    fun restoreDefault() {
        Locale.setDefault(saved)
    }

    @Test
    fun `the tokens and cost change format when the language changes`() {
        val usage = TokenUsage(
            input = BigInteger.valueOf(14_000),
            output = BigInteger.valueOf(190),
            cost = Cost.of(BigDecimal("1234.5")),
        )
        var locale by mutableStateOf(Locale.ENGLISH)
        var shown = ""
        compose.setContent { InLocale(locale) { shown = usageText(usage) } }

        compose.waitForIdle()
        assertEquals("14,190 tokens · $1,234.50", shown)

        compose.runOnIdle { locale = Locale.forLanguageTag("tr") }
        compose.waitForIdle()
        assertEquals("14.190 token · $1.234,50", shown)

        compose.runOnIdle { locale = Locale.ENGLISH }
        compose.waitForIdle()
        assertEquals("14,190 tokens · $1,234.50", shown)
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
