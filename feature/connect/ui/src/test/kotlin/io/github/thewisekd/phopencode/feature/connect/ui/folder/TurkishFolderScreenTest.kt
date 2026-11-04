package io.github.thewisekd.phopencode.feature.connect.ui.folder

import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderError
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishFolderScreenTest : FolderScreenLanguageContract() {
    override val strings = ExpectedFolderStrings(
        title = "Proje klasörü seçin",
        up = "Üst klasöre çık",
        select = "Bu klasörü seç",
        folders = "Klasörler",
        folder = "Klasör",
        symlink = "Klasöre bağlantı",
        recent = "Son klasörler",
        removeRecent = { "$it klasörünü son klasörlerden kaldır" },
        loading = "Klasörler yükleniyor…",
        emptyTitle = "Burada klasör yok",
        emptyMessage = "Bu klasörün alt klasörü yok. Yine de bu klasörü seçebilirsiniz.",
        retry = "Tekrar dene",
        dismiss = "Hatayı kapat",
        errors = mapOf(
            FolderError.NotConnected to "Sunucuya bağlı değilsiniz. Geri dönüp yeniden bağlanın.",
            FolderError.PermissionDenied to "İzin yok: bu klasör hesabınızla açılamıyor.",
            FolderError.NotFound to "Bu klasör artık yok.",
            FolderError.Failed to "Klasör listelenemedi.",
            FolderError.StorageFailed to "Son klasörler bu telefonda okunamadı ya da kaydedilemedi.",
        ),
    )
}
