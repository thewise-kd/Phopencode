package io.github.thewisekd.phopencode.feature.chat.ui.actions

import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SessionAction
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishActionsTest : ActionsLanguageContract() {
    override val strings = ExpectedActionsStrings(
        menuButton = "Oturum işlemleri",
        menuTitle = "Oturum işlemleri",
        close = "Kapat",
        compact = "Oturumu sıkıştır",
        compactDetail = "Modelin bağlamında yer açmak için şimdiye kadarki konuşmayı özetler.",
        fork = "Oturumu çatalla",
        forkDetail = "Konuşmanın tamamını yeni bir oturuma kopyalar.",
        busy = "Süren işlemin bitmesini bekleyin.",
        compactTitle = "Bu oturum sıkıştırılsın mı?",
        compactMessage = "Sunucu şimdiye kadarki konuşmayı özetler ve model özetten devam eder. Bu biraz sürebilir.",
        compactConfirm = "Sıkıştır",
        forkTitle = "Bu oturum çatallansın mı?",
        forkMessage = "Konuşmanın tamamının kopyasıyla yeni bir oturum başlar ve sohbet ona geçer. Bu oturum olduğu gibi kalır.",
        forkHereTitle = "Buradan çatallansın mı?",
        forkHereMessage = "Bu mesajdan önceki konuşmayla yeni bir oturum başlar ve sohbet ona geçer. Bu oturum olduğu gibi kalır.",
        forkConfirm = "Çatalla",
        cancel = "İptal",
        compacting = "Sıkıştırılıyor…",
        forking = "Çatallanıyor…",
        creating = "Yeni oturum başlatılıyor…",
        error = { action, reason ->
            when (action) {
                SessionAction.Compact -> "Oturum sıkıştırılamadı. $reason"
                is SessionAction.Fork -> "Oturum çatallanamadı. $reason"
                SessionAction.NewSession -> "Yeni oturum başlatılamadı. $reason"
            }
        },
        reasons = mapOf(
            ChatFailure.NotConnected to "Sunucuyla bağlantı yok.",
            ChatFailure.Unreachable to "Sunucuya ulaşılamadı.",
            ChatFailure.Unauthorized to "Sunucu kimlik bilgilerini reddetti.",
            ChatFailure.NotFound to "Oturum ya da mesaj artık yok.",
            ChatFailure.Conflict to "Oturum meşgul ya da bu arada değişti.",
            ChatFailure.Rejected to "Sunucu isteği reddetti.",
            ChatFailure.ServerFailed to "Sunucu bir hatayla karşılaştı.",
            ChatFailure.Unknown to "Beklenmeyen bir hata oluştu.",
        ),
        turnChanges = "Bu turdaki değişiklikler",
        forkHere = "Buradan çatalla",
        closeActions = "İşlemleri kapat",
    )
}
