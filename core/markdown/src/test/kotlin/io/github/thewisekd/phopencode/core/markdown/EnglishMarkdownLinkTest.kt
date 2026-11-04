package io.github.thewisekd.phopencode.core.markdown

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishMarkdownLinkTest : MarkdownLinkContract() {
    override val strings = LinkStrings(
        confirmTitle = "Open link?",
        confirmMessage = "This link opens outside the app:",
        open = "Open",
        cancel = "Cancel",
        blockedTitle = "Link not opened",
        blockedMessage = "Only http and https links can be opened. This link points to:",
        failedTitle = "Link not opened",
        failedMessage = "No app on this device can open this link.",
        close = "Close",
    )
}
