package io.github.thewisekd.phopencode.feature.chat.ui

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
import io.github.thewisekd.phopencode.feature.chat.ui.revert.countsText
import io.github.thewisekd.phopencode.feature.chat.ui.revert.revertLocale
import io.github.thewisekd.phopencode.feature.chat.ui.subagent.durationText
import io.github.thewisekd.phopencode.feature.chat.ui.subagent.subagentLocale
import io.github.thewisekd.phopencode.feature.chat.ui.usage.costText
import io.github.thewisekd.phopencode.feature.chat.ui.usage.tokensText
import io.github.thewisekd.phopencode.feature.chat.ui.usage.usageLocale
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
import kotlin.time.Duration.Companion.hours
import kotlin.test.assertEquals

private val TURKISH: Locale = Locale.forLanguageTag("tr")

/**
 * The revert page, the subagent screen and the token use follow the screen's language when it
 * changes while shown, read from the composition's configuration rather than the process default,
 * which is set to neither language.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class ChatLocaleTest {
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

    /** Shows [text] in English, then Turkish, then English again, and checks each against [english] and [turkish]. */
    private fun assertFollows(english: String, turkish: String, text: @Composable () -> String) {
        var locale by mutableStateOf(Locale.ENGLISH)
        var shown = ""
        compose.setContent { InLocale(locale) { shown = text() } }

        compose.waitForIdle()
        assertEquals(english, shown)

        compose.runOnIdle { locale = TURKISH }
        compose.waitForIdle()
        assertEquals(turkish, shown)

        compose.runOnIdle { locale = Locale.ENGLISH }
        compose.waitForIdle()
        assertEquals(english, shown)
    }

    @Test
    fun `the revert counts change format when the language changes`() {
        assertFollows(english = "+12,345 −6,789", turkish = "+12.345 −6.789") {
            countsText(additions = 12_345, deletions = 6_789, locale = revertLocale()).orEmpty()
        }
    }

    @Test
    fun `a subagent's duration changes format when the language changes`() {
        assertFollows(english = "1,234 h", turkish = "1.234 sa") {
            durationText(1_234.hours, subagentLocale())
        }
    }

    @Test
    fun `the token use changes format when the language changes`() {
        val usage = TokenUsage(
            input = BigInteger.valueOf(14_000),
            output = BigInteger.valueOf(190),
            cost = Cost.of(BigDecimal("1234.5")),
        )
        assertFollows(english = "14,190 tokens | $1,234.50", turkish = "14.190 token | $1.234,50") {
            val locale = usageLocale()
            tokensText(usage, locale) + " | " + costText(usage, locale)
        }
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
