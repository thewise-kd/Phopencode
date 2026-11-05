package io.github.thewisekd.phopencode.feature.chat.ui.usage

import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val COULD_NOT = "Bağlam sınırı yüklenemedi."

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishUsageTest : UsageLanguageContract() {
    override val strings = ExpectedUsageStrings(
        bar = "14.190 token · $0,12 · Bağlam %7",
        barTokensOnly = "14.190 token",
        barOneToken = "1 token",
        barLoading = "Token sayılıyor…",
        barEmpty = "Henüz token kullanılmadı",
        barOpen = "Token ayrıntılarını göster",
        title = "Token kullanımı",
        rows = mapOf(
            UsageTags.MODEL to ("Model" to "anthropic/claude-sonnet-4"),
            UsageTags.INPUT to ("Girdi" to "1.234"),
            UsageTags.OUTPUT to ("Çıktı" to "567"),
            UsageTags.REASONING to ("Düşünme" to "89"),
            UsageTags.CACHE_READ to ("Önbellek okuma" to "12.000"),
            UsageTags.CACHE_WRITE to ("Önbellek yazma" to "300"),
            UsageTags.TOTAL to ("Toplam" to "14.190"),
            UsageTags.COST to ("Maliyet" to "$0,12"),
            UsageTags.CONTEXT_USED to ("Kullanılan bağlam" to "13.534 · %7"),
            UsageTags.CONTEXT_LIMIT to ("Bağlam sınırı" to "200.000"),
        ),
        costs = mapOf(
            ("1234.5" to false) to "$1.234,50",
            ("0" to false) to "$0,00",
            ("0.004" to false) to "$0,01 altı",
            ("1.5" to true) to "en az $1,50",
            ("0.004" to true) to "$0,00 üzeri",
        ),
        costUnknown = "Bildirilmedi",
        loading = "Token sayılıyor…",
        emptyTitle = "Henüz token kullanılmadı",
        errors = mapOf(
            ChatFailure.NotConnected to "$COULD_NOT Sunucuyla bağlantı yok.",
            ChatFailure.Unreachable to "$COULD_NOT Sunucuya ulaşılamadı.",
            ChatFailure.Unauthorized to "$COULD_NOT Sunucu kimlik bilgilerini reddetti.",
            ChatFailure.NotFound to "$COULD_NOT Model listesi bulunamadı.",
            ChatFailure.Conflict to "$COULD_NOT Sunucu meşguldü.",
            ChatFailure.Rejected to "$COULD_NOT Sunucu isteği reddetti.",
            ChatFailure.ServerFailed to "$COULD_NOT Sunucu bir hatayla karşılaştı.",
            ChatFailure.Unknown to "$COULD_NOT Beklenmeyen bir hata oluştu.",
        ),
        retry = "Tekrar dene",
    )
}
