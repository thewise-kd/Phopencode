package io.github.thewisekd.phopencode.feature.git.ui.remote

import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteOp
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishRemoteTest : RemoteLanguageContract() {
    override val strings = ExpectedRemoteStrings(
        title = "Uzak depo",
        refresh = "Yenile",
        loading = "Uzak depolar okunuyor…",
        detached = { "Ayrık HEAD: $it" },
        tracking = { "İzlenen: $it" },
        ahead = { "$it ileride" },
        behind = { "$it geride" },
        upToDate = "Güncel",
        noUpstream = "İzlenen uzak dal yok; push bir tane ayarlar",
        upstreamGone = { "İzlenen uzak dal $it uzak depoda artık yok" },
        countsUnknown = "İleride ve geride sayılmadı",
        remote = { "Uzak depo: $it" },
        chooseRemote = "Uzak depo seçin",
        noRemotesTitle = "Uzak depo yok",
        fetch = "Fetch",
        pull = "Pull",
        pullHint = "Yalnızca ileri sarma: asla merge ya da rebase yapmaz.",
        push = "Push",
        running = mapOf(
            RemoteOp.Fetch to "Fetch yapılıyor…",
            RemoteOp.Pull to "Pull yapılıyor…",
            RemoteOp.Push to "Push yapılıyor…",
        ),
        cancel = "İptal",
        confirmTitle = { "$it uzak deposuna push yapılsın mı?" },
        confirmMessage = { count, branch, remote -> "$branch dalındaki $count commit $remote deposuna gönderilsin mi?" },
        confirmMessageUnknown = { branch, remote -> "$branch dalı $remote deposuna gönderilsin mi?" },
        confirmSetUpstream = { branch, remote ->
            "$branch dalının henüz izlediği uzak dal yok; push, $remote/$branch dalını izlenen dal yapar."
        },
        confirmNoForce = "Push asla zorlanmaz: uzak depoda daha yeni commit'ler varsa reddedilir.",
        succeeded = mapOf(
            RemoteOp.Fetch to "Fetch tamamlandı.",
            RemoteOp.Pull to "Pull tamamlandı; dal ileri sarıldı.",
            RemoteOp.Push to "Push tamamlandı.",
        ),
        upToDateResult = mapOf(
            RemoteOp.Fetch to "Fetch tamamlandı; uzak depoda yeni bir şey yok.",
            RemoteOp.Pull to "Zaten güncel; çekilecek bir şey yok.",
            RemoteOp.Push to "Gönderilecek bir şey yok; uzak depoda bu commit'ler zaten var.",
        ),
        cancelled = mapOf(
            RemoteOp.Fetch to "Fetch iptal edildi.",
            RemoteOp.Pull to "Pull iptal edildi. Dalı kontrol etmek için yenileyin.",
            RemoteOp.Push to "Push iptal edildi. Uzak depoya ulaşmış olabilir; kontrol etmek için yenileyin.",
        ),
        failed = mapOf(
            RemoteOp.Fetch to "Fetch başarısız oldu.",
            RemoteOp.Pull to "Pull başarısız oldu.",
            RemoteOp.Push to "Push başarısız oldu.",
        ),
        dismiss = "Kapat",
        explanations = mapOf(
            GitFailure.CredentialRequired to "Uzak depo kullanıcı adı, parola ya da parola cümlesi istedi; uygulama " +
                "bu sorulara yanıt veremez. Kimlik bilgilerini sunucuda bir credential helper ile saklayın ya da " +
                "orada bir SSH anahtarı kurun.",
            GitFailure.NonFastForward to "Uzak depoda bu dalda olmayan commit'ler var: başka biri push yapmış. Önce " +
                "pull yapın, sonra yeniden push yapın.",
            GitFailure.Diverged to "Hem bu dalda hem uzak depoda yeni commit'ler var, bu yüzden ileri sarmalı pull " +
                "yapılamaz. Sunucuda ya da bir bilgisayarda merge veya rebase yapın, sonra yeniden deneyin.",
            GitFailure.WouldOverwrite to "Pull yerel değişikliklerin üzerine yazacaktı; hiçbir şey değişmedi. Önce " +
                "onları commit edin ya da atın, sonra yeniden pull yapın.",
            GitFailure.Conflict to "Depoda çözülmemiş merge çakışmaları var. Önce onları çözüp commit edin, sonra " +
                "yeniden deneyin.",
            GitFailure.NotConnected to "Sunucuya bağlı değilsiniz. Geri dönüp yeniden bağlanın.",
            GitFailure.Unreachable to "Sunucuya ulaşılamadı. Yeniden deneyin.",
            GitFailure.TimedOut to "Git çok uzun sürede yanıt verdi. Yeniden deneyin.",
            GitFailure.Unauthorized to "Sunucu girişi reddetti. Geri dönüp yeniden bağlanın.",
            GitFailure.OutputTooLarge to "Git telefonda gösterilebilecekten fazla çıktı verdi.",
            GitFailure.NotRepository to "Bu klasör bir git deposunda değil.",
            GitFailure.GitMissing to "Sunucuda git kurulu değil.",
            GitFailure.Rejected to "Uzak depo ya da dal adı reddedildi.",
            GitFailure.NotFound to "Uzak depo ya da dal artık yok. Yenileyip yeniden deneyin.",
        ),
        readFallback = "Uzak depolar okunamadı.",
        actionFallback = "Git bir hata bildirdi. Depoyu sunucuda kontrol edin.",
        retry = "Tekrar dene",
    )
}
