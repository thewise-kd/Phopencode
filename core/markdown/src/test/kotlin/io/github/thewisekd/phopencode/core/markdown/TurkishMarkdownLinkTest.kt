package io.github.thewisekd.phopencode.core.markdown

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishMarkdownLinkTest : MarkdownLinkContract() {
    override val strings = LinkStrings(
        confirmTitle = "Bağlantı açılsın mı?",
        confirmMessage = "Bu bağlantı uygulamanın dışında açılır:",
        open = "Aç",
        cancel = "İptal",
        blockedTitle = "Bağlantı açılmadı",
        blockedMessage = "Yalnızca http ve https bağlantıları açılabilir. Bu bağlantının hedefi:",
        failedTitle = "Bağlantı açılmadı",
        failedMessage = "Bu cihazda bu bağlantıyı açabilecek bir uygulama yok.",
        close = "Kapat",
    )
}
