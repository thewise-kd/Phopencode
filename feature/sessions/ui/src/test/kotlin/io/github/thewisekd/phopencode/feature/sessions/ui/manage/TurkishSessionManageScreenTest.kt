package io.github.thewisekd.phopencode.feature.sessions.ui.manage

import io.github.thewisekd.phopencode.feature.sessions.logic.contract.ManageAction
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionsFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishSessionManageScreenTest : SessionManageLanguageContract() {
    override val strings = ExpectedManageStrings(
        title = "Oturumu yönet",
        loading = "Oturum yükleniyor…",
        titleLabel = "Oturum başlığı",
        save = "Başlığı kaydet",
        folder = { "Klasör: $it" },
        archivedNote = "Bu oturum arşivde.",
        archive = "Arşivle",
        unarchive = "Arşivden çıkar",
        delete = "Oturumu sil…",
        confirmTitle = "Bu oturum silinsin mi?",
        irreversible = "Silme işlemi geri alınamaz.",
        childrenDeleted = "Alt oturumları da silinir.",
        childrenMaybeDeleted = "Varsa alt oturumları da silinebilir.",
        confirmDelete = "Sil",
        cancel = "İptal",
        busy = mapOf(
            ManageAction.Rename to "Oturumun adı değiştiriliyor…",
            ManageAction.Archive to "Oturum arşivleniyor…",
            ManageAction.Unarchive to "Oturum arşivden çıkarılıyor…",
            ManageAction.Delete to "Oturum siliniyor…",
        ),
        loadFailed = "Oturum yüklenemedi.",
        actionFailed = mapOf(
            ManageAction.Rename to "Oturumun adı değiştirilemedi.",
            ManageAction.Archive to "Oturum arşivlenemedi.",
            ManageAction.Unarchive to "Oturum arşivden çıkarılamadı.",
            ManageAction.Delete to "Oturum silinemedi.",
        ),
        reasons = mapOf(
            SessionsFailure.NotConnected to "Sunucuyla bağlantı yok. Geri dönüp yeniden bağlanın.",
            SessionsFailure.Unreachable to "Sunucuya ulaşılamadı. Bağlantıyı denetleyip yeniden deneyin.",
            SessionsFailure.Unauthorized to "Sunucu kimlik bilgilerini reddetti.",
            SessionsFailure.NotFound to "Oturum artık yok; başka bir cihazdan silinmiş olabilir.",
            SessionsFailure.Conflict to "Oturum meşgul ya da bu arada değiştirildi.",
            SessionsFailure.Rejected to "Sunucu isteği reddetti.",
            SessionsFailure.ServerFailed to "Sunucuda bir hata oluştu.",
            SessionsFailure.Unknown to "Beklenmeyen bir hata oluştu.",
        ),
    )
}
