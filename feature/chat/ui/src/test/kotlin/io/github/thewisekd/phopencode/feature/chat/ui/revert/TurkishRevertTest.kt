package io.github.thewisekd.phopencode.feature.chat.ui.revert

import io.github.thewisekd.phopencode.core.model.VcsFileStatus
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertAction
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val COULD_NOT = "Değişiklikler yüklenemedi."
private const val UNKNOWN = "Beklenmeyen bir hata oluştu."

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishRevertTest : RevertLanguageContract() {
    override val strings = ExpectedRevertStrings(
        title = "Bu turdaki değişiklikler",
        summary = "3 dosya değişti · +1.235 −1",
        summaryOne = "1 dosya değişti · +1 −0",
        statuses = mapOf(
            VcsFileStatus.Added to "Eklendi",
            VcsFileStatus.Modified to "Değiştirildi",
            VcsFileStatus.Deleted to "Silindi",
            VcsFileStatus.Renamed to "Yeniden adlandırıldı",
            VcsFileStatus.Untracked to "İzlenmiyor",
            VcsFileStatus.Unknown("copied") to "copied",
        ),
        additions = "+1.234",
        deletions = "−1",
        showDiff = "Farkı göster",
        hideDiff = "Farkı gizle",
        expanded = "Açık",
        collapsed = "Kapalı",
        noPatch = "Gösterilecek fark yok. İkili bir dosya olabilir.",
        moreLines = "200 satır daha",
        moreLine = "1 satır daha",
        moreCharacters = "1.500 karakter daha",
        loading = "Değişiklikler yükleniyor…",
        emptyTitle = "Dosya değişikliği yok",
        emptyMessage = "Bu tur hiçbir dosyayı değiştirmedi.",
        intro = "Geri almak bu mesajı ve sonrasındaki her şeyi kaldırır, dosyalarda yaptıkları değişiklikleri de " +
            "geri alır. Kesinleşmeden önce onayınız istenir.",
        revert = "Geri al",
        confirmTitle = "Bu tur geri alınsın mı?",
        confirmMessage = "Bu mesaj ve sonrasındaki her şey kaldırılacak, 2 dosyadaki değişiklikler geri alınacak.",
        confirmMessageOne = "Bu mesaj ve sonrasındaki her şey kaldırılacak, 1 dosyadaki değişiklikler geri alınacak.",
        confirmMessageNone = "Bu mesaj ve sonrasındaki her şey kaldırılacak. Hiçbir dosya değişmeyecek.",
        cancel = "İptal",
        otherTurn = "Başka bir mesajın geri alınması onay bekliyor. Bu mesajı geri almak için önce onu iptal edin.",
        pending = mapOf(
            RevertAction.Stage to "Geri alma hazırlanıyor…",
            RevertAction.Commit to "Geri alınıyor…",
            RevertAction.Cancel to "Geri alma iptal ediliyor…",
        ),
        stepErrors = mapOf(
            RevertAction.Stage to "Geri alma hazırlanamadı. $UNKNOWN",
            RevertAction.Commit to "Tur geri alınamadı. $UNKNOWN",
            RevertAction.Cancel to "Geri alma iptal edilemedi. $UNKNOWN",
        ),
        loadErrors = mapOf(
            ChatFailure.NotConnected to "$COULD_NOT Sunucuyla bağlantı yok.",
            ChatFailure.Unreachable to "$COULD_NOT Sunucuya ulaşılamadı.",
            ChatFailure.Unauthorized to "$COULD_NOT Sunucu kimlik bilgilerini reddetti.",
            ChatFailure.NotFound to "$COULD_NOT Mesaj ya da oturumu artık yok.",
            ChatFailure.Conflict to "$COULD_NOT Oturum meşgul ya da bu arada değişti.",
            ChatFailure.Rejected to "$COULD_NOT Sunucu isteği reddetti.",
            ChatFailure.ServerFailed to "$COULD_NOT Sunucu bir hatayla karşılaştı.",
            ChatFailure.Unknown to "$COULD_NOT $UNKNOWN",
        ),
        retry = "Tekrar dene",
    )
}
