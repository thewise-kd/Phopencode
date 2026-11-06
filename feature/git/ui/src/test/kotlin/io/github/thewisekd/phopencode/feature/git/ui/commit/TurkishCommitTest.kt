package io.github.thewisekd.phopencode.feature.git.ui.commit

import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitOp
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishCommitTest : CommitLanguageContract() {
    override val strings = ExpectedCommitStrings(
        title = "Commit",
        refresh = "Yenile",
        loading = "Değişiklikler okunuyor…",
        messageLabel = "Commit mesajı",
        messageBlank = "Önce bir commit mesajı yazın.",
        commit = "Commit et",
        busy = mapOf(
            CommitOp.Stage to "Hazırlanıyor…",
            CommitOp.Unstage to "Hazırlıktan çıkarılıyor…",
            CommitOp.Discard to "Değişiklikler atılıyor…",
            CommitOp.Commit to "Commit ediliyor…",
        ),
        groupStaged = "Hazırlanan",
        groupUnstaged = "Hazırlanmamış",
        stageAll = "Tümünü hazırla",
        unstageAll = "Tümünü hazırlıktan çıkar",
        nothingStagedHint = "Henüz hazırlanan dosya yok. Commit edilecek dosyaları hazırlayın.",
        stage = { "$it dosyasını hazırla" },
        unstage = { "$it dosyasını hazırlıktan çıkar" },
        discard = { "$it dosyasındaki değişiklikleri at" },
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
        emptyTitle = "Commit edilecek bir şey yok",
        emptyMessage = "Çalışma ağacı temiz.",
        discardTitle = "Değişiklikler atılsın mı?",
        discardMessage = { "$it dosyasındaki hazırlanmamış değişiklikler kalıcı olarak kaybolacak. Bu işlem geri alınamaz." },
        discardConfirm = "At",
        cancel = "İptal",
        hookRejected = "Bir hook commit'i reddetti. Hiçbir şey commit edilmedi.",
        hookNoOutput = "Hook hiçbir şey yazdırmadı.",
        nothingStaged = "Hazırlanan dosya yok. Commit etmeden önce bir dosya hazırlayın.",
        errors = mapOf(
            GitFailure.NotConnected to "Sunucuya bağlı değilsiniz. Geri dönüp yeniden bağlanın.",
            GitFailure.Unreachable to "Sunucuya ulaşılamadı.",
            GitFailure.TimedOut to "Git çok uzun sürede yanıt verdi.",
            GitFailure.Unauthorized to "Sunucu girişi reddetti. Geri dönüp yeniden bağlanın.",
            GitFailure.OutputTooLarge to "Yanıt telefonda gösterilemeyecek kadar büyük.",
            GitFailure.NotRepository to "Bu klasör bir git deposunda değil.",
            GitFailure.GitMissing to "Sunucuda git kurulu değil.",
            GitFailure.Rejected to "Dosya adı ya da mesaj reddedildi.",
            GitFailure.NotFound to "Dosya ya da klasör artık yok.",
            GitFailure.Conflict to "Depoda çözülmemiş çakışmalar var.",
        ),
        fallbacks = mapOf(
            CommitOp.Stage to "Dosya hazırlanamadı.",
            CommitOp.Unstage to "Dosya hazırlıktan çıkarılamadı.",
            CommitOp.Discard to "Değişiklikler atılamadı.",
            CommitOp.Commit to "Commit yapılamadı.",
        ),
    )
}
