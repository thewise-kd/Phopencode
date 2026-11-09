package io.github.thewisekd.phopencode.feature.chat.ui.tool

import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolCardStatus
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishToolTest : ToolLanguageContract() {
    override val strings = ExpectedToolStrings(
        status = mapOf(
            ToolCardStatus.Preparing to "Bekliyor",
            ToolCardStatus.Running to "Çalışıyor",
            ToolCardStatus.Completed to "Bitti",
            ToolCardStatus.Failed to "Başarısız",
            ToolCardStatus.Unknown to "Bilinmeyen durum",
        ),
        shell = { "Komut: $it" },
        edit = { "Düzenleme: $it" },
        read = { "Okuma: $it" },
        search = { "Arama: $it" },
        searchIn = { pattern, path -> "Arama: $pattern ($path içinde)" },
        generic = { "Araç: $it" },
        input = "Girdi",
        output = "Çıktı",
        expanded = "Açık",
        collapsed = "Kapalı",
        showInput = "Girdiyi göster",
        hideInput = "Girdiyi gizle",
        showOutput = "Çıktıyı göster",
        hideOutput = "Çıktıyı gizle",
        showDetails = "Ayrıntıları göster",
        hideDetails = "Ayrıntıları gizle",
        moreLines = { "$it satır daha" },
        moreCharacters = { "$it karakter daha" },
        error = { "Hata: $it" },
    )
}
