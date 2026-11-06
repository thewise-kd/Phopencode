package io.github.thewisekd.phopencode.feature.git.ui.log

import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishLogTest : LogLanguageContract() {
    override val strings = ExpectedLogStrings(
        locale = Locale.forLanguageTag("tr"),
        january = "Oca",
        title = "Geçmiş",
        refresh = "Yenile",
        loading = "Geçmiş okunuyor…",
        loadingMore = "Daha fazla commit yükleniyor…",
        end = "Geçmişin başı",
        emptyTitle = "Henüz commit yok",
        emptyMessage = "Bu dalda commit yok.",
        filter = { "Dal: $it" },
        filterHead = "Geçerli dal",
        merge = "Birleştirme",
        retry = "Tekrar dene",
        readErrors = mapOf(
            GitFailure.NotConnected to "Sunucuya bağlı değilsiniz. Geri dönüp yeniden bağlanın.",
            GitFailure.Unreachable to "Sunucuya ulaşılamadı.",
            GitFailure.TimedOut to "Git çok uzun sürede yanıt verdi.",
            GitFailure.Unauthorized to "Sunucu girişi reddetti. Geri dönüp yeniden bağlanın.",
            GitFailure.OutputTooLarge to "Git'in yanıtı telefonda gösterilemeyecek kadar büyük.",
            GitFailure.NotRepository to "Bu klasör bir git deposunda değil.",
            GitFailure.GitMissing to "Sunucuda git kurulu değil.",
            GitFailure.Rejected to "Dal adı ya da commit hash'i reddedildi.",
            GitFailure.NotFound to "Commit ya da dal artık yok.",
        ),
        readFallback = "Geçmiş okunamadı.",
        moreFallback = "Daha fazla commit okunamadı.",
        detailFallback = "Commit okunamadı.",
        detailTitle = { "Commit $it" },
        detailLoading = "Commit okunuyor…",
        author = "Yazar",
        committer = "Commit eden",
        parent = "Üst commit",
        parents = "Üst commitler",
        root = "Kök commit, üst commiti yok",
        files = { "$it dosya değişti" },
        noFiles = "Bu commit hiçbir dosyayı değiştirmedi.",
        binary = "İkili dosya",
        counts = { added, removed -> "$added satır eklendi, $removed satır silindi" },
        renamedFrom = { "eski adı: $it" },
        kinds = mapOf(
            GitChangeKind.Added to "Eklendi",
            GitChangeKind.Modified to "Değiştirildi",
            GitChangeKind.Deleted to "Silindi",
            GitChangeKind.Renamed to "Yeniden adlandırıldı",
            GitChangeKind.Copied to "Kopyalandı",
            GitChangeKind.TypeChanged to "Türü değişti",
            GitChangeKind.Untracked to "Yeni dosya",
            GitChangeKind.Unmerged to "Çakışma",
        ),
    )
}
