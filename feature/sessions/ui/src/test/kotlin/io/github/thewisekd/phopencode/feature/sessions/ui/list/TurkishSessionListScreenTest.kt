package io.github.thewisekd.phopencode.feature.sessions.ui.list

import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionBadge
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionsFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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
    emptyMessage = "Her sohbet, bu sunucudaki bir klasörde çalışan bir opencode oturumudur.",
    noMatchTitle = "Eşleşen oturum yok",
    noMatchMessage = "Aramanızla eşleşen bir oturum başlığı yok.",
    create = "Yeni sohbet",
    refresh = "Oturumları yenile",
    openFiles = "Dosyaları aç",
    openGit = "Git'i aç",
    catalog = "Diğer seçenekler",
    catalogItems = mapOf(
        CatalogDestination.Skills to "Beceriler",
        CatalogDestination.Registry to "Komutlar, ajanlar ve modeller",
        CatalogDestination.Status to "MCP ve eklenti durumu",
    ),
    notifyItems = mapOf(
        NotifyDestination.Setup to "Bildirimler",
        NotifyDestination.Pending to "Bekleyen öğeler",
    ),
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
    ages = mapOf(
        20.seconds to "az önce",
        1.minutes to "1 dk önce",
        5.minutes to "5 dk önce",
        3.hours to "3 sa önce",
        1.days to "1 gün önce",
        4.days to "4 gün önce",
        30.days to "13 Şub",
        400.days to "8 Şub 2025",
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
