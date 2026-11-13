package io.github.thewisekd.phopencode.feature.preview.ui.page

import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishPageTest : PageLanguageContract() {
    override val strings = TURKISH_PAGE_STRINGS
}

internal val TURKISH_PAGE_STRINGS = ExpectedPageStrings(
    title = "Canlı önizleme",
    port = { "Port $it" },
    reload = "Yeniden yükle",
    opening = "Önizleme açılıyor…",
    connectionLost = ExpectedProblem(
        "Bağlantı koptu",
        "Sunucuyla bağlantı koptu. Bağlantı geri gelince yeniden yükleyin.",
    ),
    nothingListens = { port ->
        ExpectedProblem(
            "$port numaralı portu dinleyen yok",
            "Geliştirme sunucunuzu sunucunun 127.0.0.1 adresinde başlatın ya da opencode'dan başlatmasını " +
                "isteyin, sonra yeniden yükleyin.",
        )
    },
    httpError = { code -> ExpectedProblem("Sayfa yüklenemedi", "Geliştirme sunucusu HTTP $code ile yanıt verdi.") },
    networkError = ExpectedProblem("Sayfa yüklenemedi", "Önizleme geliştirme sunucusuna ulaşamadı."),
    openFailures = mapOf(
        PreviewFailure.NotConnected to ExpectedProblem(
            "Bağlı değil",
            "Geri dönüp sunucuya bağlanın, sonra yeniden yükleyin.",
        ),
        PreviewFailure.OpencodePort to ExpectedProblem(
            "Bu, opencode sunucusunun portu",
            "Önizlenemez. Geri dönüp başka bir port seçin.",
        ),
        PreviewFailure.BridgeFailed to ExpectedProblem(
            "Önizleme açılamadı",
            "Önizleme bu telefonda başlatılamadı. Tekrar denemek için yeniden yükleyin.",
        ),
    ),
    startFailed = ExpectedProblem(
        "Önizleme açılamadı",
        "Önizleme açılırken bir sorun oluştu. Tekrar denemek için yeniden yükleyin.",
    ),
    webViewTooOld = ExpectedProblem(
        "Önizleme açılamadı",
        "Bu telefondaki sistem WebView'ı canlı önizleme için çok eski. Android System WebView'ı güncelleyin, " +
            "sonra önizlemeyi yeniden açın.",
    ),
    externalTitle = "Uygulama dışında açılsın mı?",
    externalMessage = { url ->
        "Sayfa, önizlemenin dışındaki bir adresi açmak istiyor. Adres, tarayıcınız gibi başka bir uygulamada açılır:\n\n$url"
    },
    externalOpen = "Aç",
    cancel = "İptal",
    externalFailed = "Bu telefonda o adresi açabilecek bir uygulama yok.",
)
