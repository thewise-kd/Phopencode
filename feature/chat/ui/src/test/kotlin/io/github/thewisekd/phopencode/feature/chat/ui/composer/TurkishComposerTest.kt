package io.github.thewisekd.phopencode.feature.chat.ui.composer

import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerAction
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishComposerTest : ComposerLanguageContract() {
    override val strings = ExpectedComposerStrings(
        fieldHint = "Mesaj yaz… komutlar için /, dosyalar için @",
        send = "Gönder",
        stop = "Durdur",
        sending = "Mesaj gönderiliyor…",
        stopping = "Çalışma durduruluyor…",
        steer = "Hemen yönlendir",
        queue = "Sıraya koy",
        failed = mapOf(
            ComposerAction.Send to "Mesaj gönderilemedi.",
            ComposerAction.Stop to "Çalışma durdurulamadı.",
        ),
        reasons = mapOf(
            ChatFailure.NotConnected to "Sunucuyla bağlantı yok. Geri dönüp yeniden bağlanın.",
            ChatFailure.Unreachable to "Sunucuya ulaşılamadı. Bağlantıyı denetleyip yeniden deneyin.",
            ChatFailure.Unauthorized to "Sunucu kimlik bilgilerini reddetti.",
            ChatFailure.NotFound to "Oturum artık yok; başka bir cihazdan silinmiş olabilir.",
            ChatFailure.Conflict to "Oturum meşgul ya da bu arada değiştirildi.",
            ChatFailure.Rejected to "Sunucu isteği reddetti.",
            ChatFailure.ServerFailed to "Sunucuda bir hata oluştu.",
            ChatFailure.Unknown to "Beklenmeyen bir hata oluştu.",
        ),
    )
}
