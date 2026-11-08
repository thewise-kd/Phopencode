package io.github.thewisekd.phopencode.feature.catalog.ui.skills

import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishSkillsScreenTest : SkillsLanguageContract() {
    override val strings = TURKISH_SKILLS_STRINGS
}

internal val TURKISH_SKILLS_STRINGS = ExpectedSkillsStrings(
    title = "Beceriler",
    back = "Geri dön",
    search = "Becerilerde ara",
    reload = "Becerileri yeniden yükle",
    loading = "Beceriler yükleniyor…",
    refreshing = "Beceriler yenileniyor…",
    emptyTitle = "Henüz beceri yok",
    emptyMessage = "opencode'un sunucuda bulduğu beceriler burada görünür.",
    noMatchTitle = "Eşleşen beceri yok",
    noMatchMessage = "Aramanızla eşleşen bir beceri yok.",
    open = "Beceriyi aç",
    auto = "Otomatik kullanılır",
    retry = "Tekrar dene",
    errors = mapOf(
        CatalogFailure.NotConnected to "Bir sunucuya bağlı değilsiniz. Geri dönüp yeniden bağlanın.",
        CatalogFailure.Unreachable to "Sunucuya ulaşılamadı. Bağlantıyı denetleyip yeniden deneyin.",
        CatalogFailure.Unauthorized to "Sunucu kimlik bilgilerini reddetti.",
        CatalogFailure.NotFound to "Sunucu becerilerini bulamadı.",
        CatalogFailure.Rejected to "Sunucu isteği reddetti.",
        CatalogFailure.ServerFailed to "Sunucuda bir hata oluştu. Daha sonra yeniden deneyin.",
        CatalogFailure.Unknown to "Beceriler okunurken bir sorun oluştu.",
    ),
    detailLoading = "Beceri yükleniyor…",
    detailNotFound = "Bu beceri artık sunucuda yok.",
    source = "Kaynak",
    use = "Yeni oturumda kullan",
    noContent = "Bu becerinin metni yok.",
)
