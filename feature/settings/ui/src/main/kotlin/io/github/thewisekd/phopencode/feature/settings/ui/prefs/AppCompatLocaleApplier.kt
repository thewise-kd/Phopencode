package io.github.thewisekd.phopencode.feature.settings.ui.prefs

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import io.github.thewisekd.phopencode.core.storage_api.AppLanguage
import io.github.thewisekd.phopencode.feature.settings.logic.contract.LocaleApplier
import org.koin.mp.KoinPlatform

/**
 * Applies the chosen language as the app's own locale: `tr`, `en`, or none for
 * [AppLanguage.SYSTEM], so the phone's language applies. `SettingsModule` makes one for the app; the
 * presenter calls it on the main thread once the choice is stored.
 *
 * Two routes, because the app's one activity is a plain `ComponentActivity`:
 * - [AppCompatDelegate.setApplicationLocales] is always called. Below API 33 it keeps the choice and
 *   applies it to AppCompat activities only; from API 33 it hands it to the framework, but only
 *   through a live AppCompat activity's context, so with none it does nothing.
 * - From API 33 the framework's [LocaleManager] is therefore also set directly, with [context]. The
 *   framework then changes the configuration of every activity of the app, and an activity that does
 *   not handle `locale` itself is recreated in the new language at once.
 *
 * What the user sees:
 * - API 33 and later: the framework's [LocaleManager] applies the choice instantly.
 * - Below API 33: the choice is stored by AppCompat ([AppCompatDelegate.getApplicationLocales]
 *   returns it), but the app's plain `ComponentActivity` does not pick it up, so the screen stays in
 *   the old language until an app-level change (an AppCompat activity, or a locale-wrapped base
 *   context). This is a known limitation, accepted for P10.9; nothing here works around it.
 *
 * @param context Any context of the app; by default the Android context the app gave Koin at startup.
 *   Null when there is none yet, which leaves only the AppCompat route.
 */
class AppCompatLocaleApplier(
    private val context: () -> Context? = { KoinPlatform.getKoinOrNull()?.getOrNull<Context>() },
) : LocaleApplier {
    override fun apply(language: AppLanguage) {
        val locales = language.toLocales()
        // Below API 33 this only stores the choice: the plain ComponentActivity does not pick it up
        // until an app-level change (accepted limitation of P10.9). API 33+ is applied just below.
        AppCompatDelegate.setApplicationLocales(locales)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context()?.let { setFrameworkLocales(it, locales) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun setFrameworkLocales(context: Context, locales: LocaleListCompat) {
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        val wanted = LocaleList.forLanguageTags(locales.toLanguageTags())
        if (manager.applicationLocales != wanted) manager.applicationLocales = wanted
    }
}

/** The app's own locales for [this]: empty for the system language. */
internal fun AppLanguage.toLocales(): LocaleListCompat = when (this) {
    AppLanguage.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
    AppLanguage.TURKISH -> LocaleListCompat.forLanguageTags("tr")
    AppLanguage.ENGLISH -> LocaleListCompat.forLanguageTags("en")
}
