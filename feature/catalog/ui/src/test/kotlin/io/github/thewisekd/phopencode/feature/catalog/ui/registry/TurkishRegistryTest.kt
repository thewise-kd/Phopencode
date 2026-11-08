package io.github.thewisekd.phopencode.feature.catalog.ui.registry

import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryTab
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishRegistryTest : RegistryLanguageContract() {
    override val strings = TURKISH_REGISTRY_STRINGS
}

internal val TURKISH_REGISTRY_STRINGS = ExpectedRegistryStrings(
    title = "Komutlar, ajanlar, modeller",
    tabs = mapOf(RegistryTab.Commands to "Komutlar", RegistryTab.Agents to "Ajanlar", RegistryTab.Models to "Modeller"),
    reload = "Bu listeyi yenile",
    loading = mapOf(
        RegistryTab.Commands to "Komutlar yükleniyor…",
        RegistryTab.Agents to "Ajanlar yükleniyor…",
        RegistryTab.Models to "Modeller yükleniyor…",
    ),
    reloading = "Yenileniyor…",
    emptyTitles = mapOf(
        RegistryTab.Commands to "Komut yok",
        RegistryTab.Agents to "Ajan yok",
        RegistryTab.Models to "Model yok",
    ),
    emptyMessages = mapOf(
        RegistryTab.Commands to "Bu sunucuda özel komut yok.",
        RegistryTab.Agents to "Bu sunucu hiç ajan listelemiyor.",
        RegistryTab.Models to "Bu sunucudaki hiçbir sağlayıcı model sunmuyor.",
    ),
    noDescription = "Açıklama yok",
    modes = listOf("Birincil", "Alt ajan", "Birincil ve alt ajan"),
    unknownMode = "Mod: sideways",
    hidden = "Gizli",
    default = "Varsayılan",
    contextLine = "Bağlam: 200.000 token",
    loadingCommand = "Komut yükleniyor…",
    loadingAgent = "Ajan yükleniyor…",
    loadingModel = "Model yükleniyor…",
    description = "Açıklama",
    mode = "Mod",
    model = "Model",
    permissions = "İzinler",
    noPermissions = "İzin kuralı yok",
    sessionModel = "Oturumun modelini kullanır",
    effects = listOf("İzin ver", "Reddet", "Sor", "Bilinmiyor: maybe"),
    provider = "Sağlayıcı",
    modelId = "Model kimliği",
    limits = "Token sınırları",
    limitNames = listOf("Bağlam", "Girdi", "Çıktı"),
    limitValues = listOf("200.000 token", "64.000 token"),
    notGiven = "Belirtilmemiş",
    pricing = "Fiyatlandırma",
    pricingUnit = "Milyon token başına USD",
    baseTier = "Standart",
    contextTier = "200.000 bağlam tokenının üstünde",
    priceNames = listOf("Girdi", "Çıktı", "Önbellekten okuma", "Önbelleğe yazma"),
    prices = listOf("$3,00", "$15,00", "$0,075", "$6,00", "$22,50"),
    unknownPrice = "Bilinmiyor",
    noPricing = "Sunucu bu model için fiyat vermiyor.",
    variants = "Varyantlar",
    errors = mapOf(
        CatalogFailure.NotConnected to "Sunucuya bağlı değil. Geri dönüp yeniden bağlanın.",
        CatalogFailure.Unreachable to "Sunucuya ulaşılamadı. Bağlantıyı denetleyip tekrar deneyin.",
        CatalogFailure.Unauthorized to "Sunucu kimlik bilgilerini reddetti.",
        CatalogFailure.NotFound to "Sunucuda bulunamadı. Yapılandırmasından kaldırılmış olabilir.",
        CatalogFailure.Rejected to "Sunucu isteği reddetti.",
        CatalogFailure.ServerFailed to "Sunucuda bir hata oluştu.",
        CatalogFailure.Unknown to "Bir şeyler ters gitti.",
    ),
    retry = "Tekrar dene",
)
