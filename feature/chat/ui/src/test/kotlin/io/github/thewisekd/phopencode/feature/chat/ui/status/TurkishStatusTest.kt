package io.github.thewisekd.phopencode.feature.chat.ui.status

import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishStatusTest : StatusLanguageContract() {
    override val strings = ExpectedStatusStrings(
        loading = "Oturum yükleniyor…",
        running = "Çalışıyor…",
        retrying = "Yeniden deneniyor",
        retryingAttempt = { "Yeniden deneniyor, deneme $it" },
        nextTry = { "Sonraki deneme: $it" },
        failed = { "Son çalışma başarısız oldu: $it" },
        failedGeneric = "Son çalışma başarısız oldu.",
        disconnected = "Sunucuyla bağlantı koptu. Gösterilenler güncel olmayabilir.",
        reconnect = "Yeniden bağlan",
        close = "Kapat",
        notConnectedTitle = "Bağlı değil",
        notConnectedMessage = "Sunucuyla bağlantı yok, bu yüzden oturum yüklenemiyor. " +
            "Yeniden bağlanmak için sunucu listesine dönün.",
        notFoundTitle = "Oturum bulunamadı",
        notFoundMessage = "Bağlı sunucuda bu oturum yok. Silinmiş olabilir.",
        otherServerTitle = "Bu oturum başka bir sunucuya ait",
        otherServerMessage = "Bağlantı artık bu sohbetin açıldığı sunucudan farklı bir sunucuya bağlı. " +
            "Sohbeti kapatın ve oturumu kendi sunucusundan açın.",
        unavailableTitle = "Oturum kullanılamıyor",
        unavailableReasons = mapOf(
            ChatFailure.NotConnected to "Sunucuyla bağlantı yok. Yeniden bağlanmak için sunucu listesine dönün.",
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
