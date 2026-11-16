package io.github.thewisekd.phopencode.feature.preview.ui.notice

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishNoticeTest : NoticeLanguageContract() {
    override val strings = ExpectedNoticeStrings(
        title = "Önizlemeden önce",
        points = listOf(
            "Geliştirme sunucunuzu kendiniz başlatın; sunucunun ağına açılmaması için 0.0.0.0 yerine " +
                "127.0.0.1 ya da localhost üzerinde çalıştırın.",
            "Yalnızca güvendiğiniz projeleri açın. Sayfanın JavaScript kodu telefonunuzda çalışır ama " +
                "uygulamanın anahtarlarından ve parolalarından ayrı tutulur.",
            "Önizleme sayfasında gerçek hesaplarınıza giriş yapmayın; gerçek parola, token ya da kişisel " +
                "bilgi yazmayın.",
            "Önizlemenin dışına çıkan bir bağlantıyı onaylamadan önce adresin tamamına göz atın.",
            "Uygulama geliştirme sunucunuzu asla başlatmaz ya da durdurmaz; önizlemeden çıktığınızda " +
                "önizlemenin verileri silinir.",
        ),
        accept = "Okudum, anladım",
        notNow = "Şimdi değil",
        saveFailed = "Yanıtınız kaydedilemedi. Lütfen yeniden deneyin.",
        alarming = listOf("tehlike", "uyarı", "risk", "dikkat"),
    )
}
