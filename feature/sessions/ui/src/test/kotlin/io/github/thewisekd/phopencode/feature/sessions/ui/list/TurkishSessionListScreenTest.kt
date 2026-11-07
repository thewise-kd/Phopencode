package io.github.thewisekd.phopencode.feature.sessions.ui.list

import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionBadge
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionsFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishSessionListScreenTest : SessionListScreenLanguageContract() {
    override val strings = TURKISH_SESSION_LIST_STRINGS
}

internal val TURKISH_SESSION_LIST_STRINGS = ExpectedSessionListStrings(
    title = "Oturumlar",
    loading = "Oturumlar yükleniyor…",
    refreshing = "Oturumlar yenileniyor…",
    loadingMore = "Daha fazla oturum yükleniyor…",
    emptyTitle = "Henüz oturum yok",
    emptyMessage = "Bu sunucuda opencode ile çalışmak için bir oturum başlatın.",
    noMatchTitle = "Eşleşen oturum yok",
    noMatchMessage = "Aramanızla eşleşen bir oturum başlığı yok.",
    create = "Yeni oturum",
    refresh = "Oturumları yenile",
    openFiles = "Dosyaları aç",
    openGit = "Git'i aç",
    search = "Oturum ara",
    showArchived = "Arşivdekileri göster",
    archived = "Arşivde",
    untitled = "Adsız oturum",
    manage = { "Yönet: $it" },
    retry = "Tekrar dene",
    dismiss = "Hatayı kapat",
    loadMoreError = "Daha fazla oturum yüklenemedi.",
    usage = "1.234.567 token · \$3,50",
    usageAboveZero = "1 token · \$0,00 üzeri",
    badges = mapOf(
        SessionBadge.WaitingForInput to "Yanıtınızı bekliyor",
        SessionBadge.Running to "Çalışıyor",
        SessionBadge.Failed to "Başarısız",
        SessionBadge.Interrupted to "Kesildi",
    ),
    errors = mapOf(
        SessionsFailure.NotConnected to "Sunucuya bağlı değil. Geri dönüp yeniden bağlanın.",
        SessionsFailure.Unreachable to "Sunucuya ulaşılamadı.",
        SessionsFailure.Unauthorized to "Sunucu girişi reddetti. Doğru parolayla yeniden bağlanın.",
        SessionsFailure.NotFound to "Oturumlar sunucuda bulunamadı.",
        SessionsFailure.Conflict to "Oturumlar bu arada değişti. Tekrar deneyin.",
        SessionsFailure.Rejected to "Sunucu oturumları listelemeyi reddetti.",
        SessionsFailure.ServerFailed to "Sunucu oturumları listelerken hata verdi.",
        SessionsFailure.Unknown to "Oturumlar yüklenemedi.",
    ),
)
