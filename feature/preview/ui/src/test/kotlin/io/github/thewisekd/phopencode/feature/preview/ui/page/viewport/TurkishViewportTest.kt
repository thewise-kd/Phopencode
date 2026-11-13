package io.github.thewisekd.phopencode.feature.preview.ui.page.viewport

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishViewportTest : ViewportToggleContract() {
    override val strings = ExpectedViewportStrings(
        toggle = "Masaüstü görünümü",
        desktop = "Masaüstü: geniş sayfa, yakınlaştırma açık",
        mobile = "Mobil: telefon düzeni",
    )
}
