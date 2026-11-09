package io.github.thewisekd.phopencode.feature.notify.ui.pending

import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishPendingScreenTest : PendingScreenLanguageContract() {
    override val strings = TURKISH_PENDING_STRINGS
}

internal val TURKISH_PENDING_STRINGS = ExpectedPendingStrings(
    title = "Sizi bekleyenler",
    refresh = "Listeyi yenile",
    loading = "Bekleyenler yükleniyor…",
    refreshing = "Bekleyenler yenileniyor…",
    empty = "Yanıt bekleyen bir şey yok",
    question = "Soru",
    form = "Form",
    permission = "İzin isteği",
    untitled = "Adsız oturum",
    subagent = "Alt ajan",
    open = "Oturumu aç",
    retry = "Tekrar dene",
    connect = "Bağlan",
    errors = mapOf(
        NotifyFailure.NotConnected to "Bir sunucuya bağlı değil. Bekleyenleri görmek için bağlanın.",
        NotifyFailure.Unreachable to "Sunucuya ulaşılamadı.",
        NotifyFailure.Unauthorized to "Sunucu girişi reddetti. Doğru parolayla yeniden bağlanın.",
        NotifyFailure.NotFound to "Sunucu istenen şeyi bulamadı.",
        NotifyFailure.Rejected to "Sunucu bekleyenleri listelemeyi reddetti.",
        NotifyFailure.ServerFailed to "Sunucu bekleyenleri listelerken hata verdi.",
        NotifyFailure.Unknown to "Bekleyenler yüklenemedi.",
    ),
)
