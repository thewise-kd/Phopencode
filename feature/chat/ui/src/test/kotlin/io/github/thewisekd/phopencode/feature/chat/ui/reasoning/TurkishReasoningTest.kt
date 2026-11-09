package io.github.thewisekd.phopencode.feature.chat.ui.reasoning

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishReasoningTest : ReasoningLanguageContract() {
    override val strings = ExpectedReasoningStrings(
        thinking = "Düşünüyor…",
        thought = "Düşündü",
        live = "Canlı",
        thoughtFor = { "$it düşündü" },
        seconds = { "$it sn" },
        minutes = { "$it dk" },
        minutesSeconds = { m, s -> "$m dk $s sn" },
        expanded = "Açık",
        collapsed = "Kapalı",
        show = "Düşünceyi göster",
        hide = "Düşünceyi gizle",
    )
}
