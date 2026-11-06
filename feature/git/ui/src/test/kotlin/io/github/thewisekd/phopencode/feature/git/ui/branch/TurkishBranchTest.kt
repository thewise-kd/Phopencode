package io.github.thewisekd.phopencode.feature.git.ui.branch

import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchNameProblem
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchOp
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishBranchTest : BranchLanguageContract() {
    override val strings = ExpectedBranchStrings(
        title = "Dallar",
        refresh = "Yenile",
        create = "Yeni dal",
        loading = "Dallar okunuyor…",
        busy = mapOf(
            BranchOp.Create to "Dal oluşturuluyor…",
            BranchOp.Switch to "Dal değiştiriliyor…",
            BranchOp.Delete to "Dal siliniyor…",
        ),
        search = "Dallarda ara",
        searchClear = "Aramayı temizle",
        local = "Yerel",
        remote = "Uzak",
        current = "Geçerli dal",
        delete = { "$it dalını sil" },
        tracking = { "İzlenen: $it" },
        ahead = { "$it ileride" },
        behind = { "$it geride" },
        upToDate = "Güncel",
        noUpstream = "İzlenen uzak dal yok",
        emptyTitle = "Dal yok",
        noMatchTitle = "Eşleşen dal yok",
        dirtyTitle = "Commit edilmemiş değişiklikler",
        dirtyConfirm = "Yine de geç",
        cancel = "İptal",
        deleteTitle = "Dal silinsin mi?",
        deleteConfirm = "Sil",
        unmergedTitle = "Dal birleştirilmemiş",
        unmergedConfirm = "Yine de sil",
        createTitle = "Yeni dal",
        createName = "Dal adı",
        createFrom = { "Başlangıç: $it" },
        createFromHead = "Başlangıç: geçerli commit",
        createSwitchAfter = "Oluşturulunca bu dala geç",
        createSubmit = "Oluştur",
        problems = mapOf(
            BranchNameProblem.Blank to "Bir ad girin.",
            BranchNameProblem.Invalid to "Git bu ada izin vermiyor.",
            BranchNameProblem.Taken to "Bu adda bir yerel dal zaten var.",
        ),
        retry = "Tekrar dene",
        errors = mapOf(
            GitFailure.NotConnected to "Sunucuya bağlı değilsiniz. Geri dönüp yeniden bağlanın.",
            GitFailure.Unreachable to "Sunucuya ulaşılamadı.",
            GitFailure.TimedOut to "Git çok uzun sürede yanıt verdi.",
            GitFailure.Unauthorized to "Sunucu girişi reddetti. Geri dönüp yeniden bağlanın.",
            GitFailure.OutputTooLarge to "Dal listesi telefonda gösterilemeyecek kadar büyük.",
            GitFailure.NotRepository to "Bu klasör bir git deposunda değil.",
            GitFailure.GitMissing to "Sunucuda git kurulu değil.",
            GitFailure.Rejected to "Dal adı reddedildi.",
            GitFailure.NotFound to "Dal artık yok.",
            GitFailure.WouldOverwrite to
                "Geçiş commit edilmemiş değişikliklerin üzerine yazacağı için git hiçbir şeyi değiştirmedi. " +
                "Önce bunları commit edin ya da saklayın.",
            GitFailure.Conflict to "Depoda çözülmemiş çakışmalar var.",
        ),
        readFallback = "Dallar listelenemedi.",
        actionFallbacks = mapOf(
            BranchOp.Create to "Dal oluşturulamadı.",
            BranchOp.Switch to "Dala geçilemedi.",
            BranchOp.Delete to "Dal silinemedi.",
        ),
    )
}
