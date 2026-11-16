package io.github.thewisekd.phopencode.feature.settings.ui.guide

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.github.thewisekd.phopencode.feature.settings.logic.contract.GuidePage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The guide speaks Turkish on a Turkish phone, naming the screens as their Turkish strings do; the
 * parity check covers every key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishGuideScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val pages: Map<GuidePage, Pair<String, List<String>>> = mapOf(
        GuidePage.Welcome to ("Phopencode'a hoş geldiniz" to listOf("opencode", "kendi sunucunuzdaki", "bu telefondan")),
        GuidePage.OnYourServer to (
            "Sunucunuzda" to listOf("opencode", "opencode serve", "127.0.0.1", "parola", "SSH", "bağlantı noktası")
            ),
        GuidePage.AddServer to (
            "Sunucu ekleyin" to listOf("Sunucu ekle", "Sunucu adresi", "kullanıcı adı", "SSH anahtarı", "parmak izi")
            ),
        GuidePage.SessionsAndChat to (
            "Oturumlar ve sohbet" to listOf("proje klasörü", "Yeni sohbet", "soru", "izin")
            ),
        GuidePage.FilesAndGit to ("Dosyalar ve Git" to listOf("Dosyaları aç", "Git'i aç", "Oturum işlemleri")),
        GuidePage.Notifications to ("Bildirimler (isteğe bağlı)" to listOf("ntfy", "Bildirimler", "sunucu")),
        GuidePage.LivePreview to (
            "Canlı önizleme" to listOf("kendiniz", "127.0.0.1", "Canlı önizleme", "Oturum işlemleri")
            ),
        GuidePage.Done to ("Hazırsınız" to listOf("Ayarlar", "Kullanım kılavuzu")),
    )

    @Test
    fun `every page has its title and its facts in Turkish`() {
        compose.assertPages(pages)
    }

    @Test
    fun `the screen and its buttons are in Turkish`() {
        compose.showGuide(GuidePage.FilesAndGit)

        compose.onNodeWithText("Kullanım kılavuzu").assertIsDisplayed()
        compose.onNodeWithTag(GUIDE_BACK_TAG).assertTextEquals("Geri")
        compose.onNodeWithTag(GUIDE_NEXT_TAG).assertTextEquals("İleri")
        compose.onNodeWithTag(GUIDE_SKIP_TAG).assertTextEquals("Atla")
    }

    @Test
    fun `the last page starts`() {
        compose.showGuide(GuidePage.Done)

        compose.onNodeWithTag(GUIDE_START_TAG).assertTextEquals("Başla")
    }

    @Test
    fun `the position is read out`() {
        compose.showGuide(GuidePage.AddServer)

        compose.onNodeWithContentDescription("Sayfa 3, toplam 8").assertIsDisplayed()
    }
}
