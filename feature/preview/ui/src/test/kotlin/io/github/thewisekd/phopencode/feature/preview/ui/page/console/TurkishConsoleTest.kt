package io.github.thewisekd.phopencode.feature.preview.ui.page.console

import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleLevel
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishConsoleTest : ConsoleLanguageContract() {
    override val strings = TURKISH_CONSOLE_STRINGS
}

internal val TURKISH_CONSOLE_STRINGS = ExpectedConsoleStrings(
    title = "Konsol",
    show = "Konsolu göster",
    hide = "Konsolu gizle",
    withErrors = { action, count -> "$action, $count hata" },
    clear = "Temizle",
    all = "Tümü",
    levels = mapOf(
        ConsoleLevel.Debug to "Hata ayıklama",
        ConsoleLevel.Log to "Günlük",
        ConsoleLevel.Info to "Bilgi",
        ConsoleLevel.Warning to "Uyarı",
        ConsoleLevel.Error to "Hata",
    ),
    entry = { level, text, location -> if (location == null) "$level: $text" else "$level: $text. $location" },
    sourceLine = { source, line -> "$source, satır $line" },
    line = { "Satır $it" },
    empty = "Henüz ileti yok.",
    emptyFiltered = "Bu düzeyde ileti yok.",
)
