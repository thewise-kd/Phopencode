package io.github.thewisekd.phopencode.feature.files.ui.tree

import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.GitBadge
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val TR_NOT_LISTED = "Klasör listelenemedi."

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishTreeScreenTest : TreeScreenLanguageContract() {
    override val strings = ExpectedTreeStrings(
        title = "Dosyalar",
        up = "Üst klasöre çık",
        search = "Dosyalarda ara",
        transfers = "Aktarımlar",
        more = "Klasör seçenekleri",
        actionsFor = { "$it için işlemler" },
        folder = "Klasör",
        file = "Dosya",
        symlink = "Bağlantı",
        other = "Diğer",
        ignored = "Yok sayılıyor",
        badges = mapOf(
            GitBadge.Added to "Eklendi",
            GitBadge.Modified to "Değiştirildi",
            GitBadge.Deleted to "Silindi",
            GitBadge.Renamed to "Yeniden adlandırıldı",
            GitBadge.Untracked to "İzlenmiyor",
            GitBadge.ContainsChanges to "Değişiklik içeriyor",
        ),
        loading = "Dosyalar yükleniyor…",
        refreshing = "Yenileniyor…",
        emptyTitle = "Bu klasör boş",
        emptyHidden = "Gizli dosyalar gösterilmiyor.",
        showHiddenAction = "Gizlileri göster",
        optionsTitle = "Klasör seçenekleri",
        newFolder = "Yeni klasör",
        refresh = "Yenile",
        showHidden = "Gizli dosyaları göster",
        sortHeading = "Sıralama",
        sortName = "Ad",
        sortModified = "Son değişiklik",
        sortSize = "Boyut",
        close = "Kapat",
        retry = "Tekrar dene",
        errors = mapOf(
            FilesFailure.NotConnected to "Sunucuya bağlı değilsiniz. Geri dönüp yeniden bağlanın.",
            FilesFailure.Unreachable to "Sunucuya ulaşılamadı.",
            FilesFailure.Unauthorized to "Sunucu girişi reddetti. Geri dönüp yeniden bağlanın.",
            FilesFailure.NotFound to "Bu klasör artık yok.",
            FilesFailure.PermissionDenied to "İzin yok: bu klasör hesabınızla açılamıyor.",
            FilesFailure.PathRejected to "Bu klasör proje klasörünün dışında.",
            FilesFailure.ServerFailed to "Sunucu bu klasörü listeleyemedi.",
            FilesFailure.Unsupported to "Bu bağlantıda dosyalara göz atılamıyor.",
            FilesFailure.Unknown to TR_NOT_LISTED,
            FilesFailure.AlreadyExists to TR_NOT_LISTED,
            FilesFailure.NotEmpty to TR_NOT_LISTED,
            FilesFailure.TooLarge to TR_NOT_LISTED,
            FilesFailure.ToolMissing to TR_NOT_LISTED,
            FilesFailure.Rejected to TR_NOT_LISTED,
        ),
    )
}
