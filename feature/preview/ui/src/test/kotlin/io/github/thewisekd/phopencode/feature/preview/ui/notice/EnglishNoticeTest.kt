package io.github.thewisekd.phopencode.feature.preview.ui.notice

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishNoticeTest : NoticeLanguageContract() {
    override val strings = ExpectedNoticeStrings(
        title = "Before you preview",
        points = listOf(
            "Start your dev server yourself on 127.0.0.1 or localhost, not 0.0.0.0, so it is not exposed " +
                "on the server's network.",
            "Open only projects you trust. The page's JavaScript runs on your phone, kept apart from the " +
                "app's keys and passwords.",
            "Do not sign in to real accounts or type real passwords, tokens or personal data into the " +
                "preview page.",
            "Check the full address before you confirm a link that leaves the preview.",
            "The app never starts or stops your dev server, and the preview's data is cleared when you leave it.",
        ),
        accept = "I have read and understood",
        notNow = "Not now",
        saveFailed = "Your answer could not be saved. Please try again.",
        alarming = listOf("danger", "warning", "warn", "risk", "attention", "careful"),
    )
}
