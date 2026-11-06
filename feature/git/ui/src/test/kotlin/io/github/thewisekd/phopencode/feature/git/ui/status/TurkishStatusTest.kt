package io.github.thewisekd.phopencode.feature.git.ui.status

import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishStatusTest : StatusLanguageContract() {
    override val strings = ExpectedStatusStrings(
        title = "Git",
        refresh = "Yenile",
        loading = "Durum okunuyor…",
        refreshing = "Yenileniyor…",
        detached = { "Ayrık HEAD: $it" },
        noCommits = "Henüz commit yok",
        tracking = { "İzlenen: $it" },
        ahead = { "$it ileride" },
        behind = { "$it geride" },
        upToDate = "Güncel",
        noUpstream = "İzlenen uzak dal yok",
        upstreamGone = { "İzlenen uzak dal $it artık yok" },
        countsUnknown = "İleride ve geride sayılmadı",
        commit = "Commit",
        log = "Geçmiş",
        branches = "Dallar",
        remote = "Uzak depo",
        groups = mapOf(
            StatusGroup.Conflicted to "Çakışmalar",
            StatusGroup.Staged to "Hazırlanan",
            StatusGroup.Unstaged to "Hazırlanmamış",
            StatusGroup.Untracked to "İzlenmeyen",
        ),
        stageAll = "Tümünü hazırla",
        unstageAll = "Tümünü hazırlıktan çıkar",
        stage = { "$it dosyasını hazırla" },
        unstage = { "$it dosyasını hazırlıktan çıkar" },
        resolve = { "$it dosyasını çözüldü olarak işaretle" },
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
        renamedFrom = { "eski adı: $it" },
        cleanTitle = "Commit edilecek bir şey yok",
        cleanMessage = "Çalışma ağacı temiz.",
        retry = "Tekrar dene",
        readErrors = mapOf(
            GitFailure.NotConnected to "Sunucuya bağlı değilsiniz. Geri dönüp yeniden bağlanın.",
            GitFailure.Unreachable to "Sunucuya ulaşılamadı.",
            GitFailure.TimedOut to "Git çok uzun sürede yanıt verdi.",
            GitFailure.Unauthorized to "Sunucu girişi reddetti. Geri dönüp yeniden bağlanın.",
            GitFailure.OutputTooLarge to "Durum telefonda gösterilemeyecek kadar büyük.",
            GitFailure.NotRepository to "Bu klasör bir git deposunda değil.",
            GitFailure.GitMissing to "Sunucuda git kurulu değil.",
            GitFailure.Rejected to "Dosya adı reddedildi.",
            GitFailure.NotFound to "Dosya ya da klasör artık yok.",
            GitFailure.Conflict to "Depoda çözülmemiş çakışmalar var.",
        ),
        readFallback = "Durum okunamadı.",
        actionFallback = "Dosya hazırlanamadı ya da hazırlıktan çıkarılamadı.",
    )
}
