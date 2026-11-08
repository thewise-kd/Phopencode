package io.github.thewisekd.phopencode.feature.sessions.ui.create

import io.github.thewisekd.phopencode.feature.sessions.logic.contract.CreateFolderError
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionsFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishSessionCreateScreenTest : SessionCreateScreenLanguageContract() {
    override val strings = ExpectedCreateStrings(
        title = "Yeni sohbet",
        folder = "Klasör",
        sectionFolder = "Proje klasörü",
        sectionDetails = "Ayrıntılar",
        folderHelp = "Sunucuda / ile başlayan tam bir yol.",
        folderErrors = mapOf(
            CreateFolderError.Missing to "Oturumun çalışacağı klasörü girin.",
            CreateFolderError.NotAbsolute to "Klasör / ile başlayan tam bir yol olmalı.",
        ),
        recent = "Son klasörler",
        sessionTitle = "Başlık (isteğe bağlı)",
        sessionTitleHelp = "Boş bırakırsanız sunucu oturuma ilk mesajınızdan bir ad verir.",
        agent = "Ajan",
        agentDefault = "Sunucu varsayılanı",
        model = "Model",
        modelDefault = "Sunucunun varsayılan modeli",
        modelDefaultNamed = { "Varsayılan: $it" },
        modelChoose = "Model seçin",
        modelCancel = "İptal",
        loading = "Ajanlar ve modeller yükleniyor…",
        skill = "Seçilen beceri ilk mesajınıza eklenir.",
        create = "Sohbeti başlat",
        creating = "Oluşturuluyor…",
        createError = { "Oturum oluşturulamadı. $it" },
        catalogError = { "Ajanlar ve modeller yüklenemedi; sunucunun varsayılanları kullanılır. $it" },
        reasons = mapOf(
            SessionsFailure.NotConnected to "Sunucuya bağlı değilsiniz. Geri dönüp yeniden bağlanın.",
            SessionsFailure.Unreachable to "Sunucuya ulaşılamadı.",
            SessionsFailure.Unauthorized to "Sunucu kimlik bilgilerini reddetti.",
            SessionsFailure.NotFound to "Sunucu bunu bulamadı.",
            SessionsFailure.Conflict to "Bu arada sunucuda bir şey değişti. Tekrar deneyin.",
            SessionsFailure.Rejected to "Sunucu isteği reddetti.",
            SessionsFailure.ServerFailed to "Sunucuda bir hata oluştu.",
            SessionsFailure.Unknown to "Beklenmeyen bir şey oldu.",
        ),
        retry = "Tekrar dene",
        dismiss = "Hatayı kapat",
    )
}
