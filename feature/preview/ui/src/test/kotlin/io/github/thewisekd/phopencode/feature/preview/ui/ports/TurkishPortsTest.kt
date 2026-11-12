package io.github.thewisekd.phopencode.feature.preview.ui.ports

import io.github.thewisekd.phopencode.feature.preview.logic.contract.ManualPortProblem
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishPortsTest : PortsLanguageContract() {
    override val strings = TURKISH_PORTS_STRINGS
}

internal val TURKISH_PORTS_STRINGS = ExpectedPortsStrings(
    title = "Canlı önizleme",
    refresh = "Portları yenile",
    loading = "Dinleyen portlar aranıyor…",
    refreshing = "Portlar yenileniyor…",
    listHeading = "Sunucuda dinleyenler",
    port = { "Port $it" },
    opencode = "opencode sunucusu. Önizlenemez.",
    manualHeading = "Port girin",
    manualLabel = "Port",
    manualHint = "Sunucunun 127.0.0.1 adresinde 1 ile 65535 arası bir port",
    manualOpen = "Aç",
    manualProblems = mapOf(
        ManualPortProblem.Empty to "Bir port numarası girin.",
        ManualPortProblem.NotANumber to "Yalnızca rakam kullanın.",
        ManualPortProblem.OutOfRange to "1 ile 65535 arası bir port kullanın.",
        ManualPortProblem.OpencodePort to "Bu, opencode sunucusunun portu. Önizlenemez.",
    ),
    emptyTitle = "Geliştirme sunucusu bulunamadı",
    emptyMessage = "Sunucunun 127.0.0.1 adresinde henüz dinleyen yok. Geliştirme sunucunuzu orada başlatın " +
        "ya da opencode'dan başlatmasını isteyin, sonra yenileyin.",
    emptyRefresh = "Yenile",
    unsupportedTitle = "Portlar listelenemiyor",
    unsupportedMessage = "Bu sunucu portlarını listelemenin bir yolunu sunmuyor. Geliştirme sunucunuzun portunu aşağıya girin.",
    retry = "Tekrar dene",
    dismiss = "Hatayı kapat",
    errors = mapOf(
        PreviewFailure.NotConnected to "Sunucuya bağlı değil. Geri dönüp yeniden bağlanın.",
        PreviewFailure.ConnectionLost to "Sunucuyla bağlantı koptu. Bağlantı geri gelince tekrar deneyin.",
        PreviewFailure.TimedOut to "Portları listelemek çok uzun sürdü.",
        PreviewFailure.OutputTooLarge to "Sunucu, uygulamanın okuduğundan fazlasını listeledi.",
        PreviewFailure.CommandFailed to "Sunucu portlarını listeleyemedi.",
    ),
    otherError = "Portlar listelenemedi.",
)
