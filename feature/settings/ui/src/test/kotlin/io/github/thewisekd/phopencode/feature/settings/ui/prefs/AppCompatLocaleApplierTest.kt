package io.github.thewisekd.phopencode.feature.settings.ui.prefs

import android.app.Application
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import io.github.thewisekd.phopencode.core.storage_api.AppLanguage
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The applier sets the app's own locale, and an empty one for the system language.
 *
 * From API 33 it goes to the framework's LocaleManager itself, which changes the language of a plain
 * ComponentActivity at once: AppCompat alone would find no AppCompat activity there and do nothing.
 * Below 33 the choice is only stored by AppCompat; the app's plain ComponentActivity does not pick it
 * up until an app-level change, an accepted limitation of P10.9.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class AppCompatLocaleApplierTest {
    private val application: Application = RuntimeEnvironment.getApplication()

    private val applier = AppCompatLocaleApplier(context = { application })

    @After
    fun resetLocales() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        if (Build.VERSION.SDK_INT >= 33) {
            application.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
        }
    }

    /** The app's own locales as the platform keeps them on this API level. */
    private fun appLocales(): String = if (Build.VERSION.SDK_INT >= 33) {
        application.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
    } else {
        AppCompatDelegate.getApplicationLocales().toLanguageTags()
    }

    @Test
    fun `Turkish sets tr`() {
        applier.apply(AppLanguage.TURKISH)

        assertEquals("tr", appLocales())
    }

    @Test
    fun `English sets en`() {
        applier.apply(AppLanguage.ENGLISH)

        assertEquals("en", appLocales())
    }

    @Test
    fun `system clears the app's own locale`() {
        applier.apply(AppLanguage.TURKISH)
        applier.apply(AppLanguage.SYSTEM)

        assertEquals("", appLocales())
    }

    @Test
    fun `a change replaces the previous one`() {
        applier.apply(AppLanguage.TURKISH)
        applier.apply(AppLanguage.ENGLISH)

        assertEquals("en", appLocales())
    }

    /**
     * The documented limitation below API 33: the choice is only stored through AppCompatDelegate
     * (the plain ComponentActivity does not pick it up), and storing it never crashes, with or
     * without a context.
     */
    @Test
    fun `below 33 the choice is stored through AppCompatDelegate without crashing`() {
        assumeTrue(Build.VERSION.SDK_INT < 33)
        val stored = { AppCompatDelegate.getApplicationLocales().toLanguageTags() }

        applier.apply(AppLanguage.TURKISH)
        assertEquals("tr", stored())
        applier.apply(AppLanguage.ENGLISH)
        assertEquals("en", stored())
        applier.apply(AppLanguage.SYSTEM)
        assertEquals("", stored())
        AppCompatLocaleApplier(context = { null }).apply(AppLanguage.TURKISH)
        assertEquals("tr", stored())
    }

    @Test
    fun `without a context it still applies what AppCompat can, and does not crash`() {
        AppCompatLocaleApplier(context = { null }).apply(AppLanguage.TURKISH)

        val expected = if (Build.VERSION.SDK_INT >= 33) "" else "tr"
        assertEquals(expected, appLocales())
    }

    @Test
    fun `the app's applier is made without arguments`() {
        AppCompatLocaleApplier().apply(AppLanguage.SYSTEM)

        assertEquals("", appLocales())
    }
}
