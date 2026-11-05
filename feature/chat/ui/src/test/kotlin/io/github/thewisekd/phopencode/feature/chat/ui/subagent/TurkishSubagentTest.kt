package io.github.thewisekd.phopencode.feature.chat.ui.subagent

import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentStatus
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val COULD_NOT = "Alt ajanlar yüklenemedi."

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishSubagentTest : SubagentLanguageContract() {
    override val strings = ExpectedSubagentStrings(
        badgeRunning = "2 çalışıyor",
        badgeTotal = "3 alt ajan",
        badgeOne = "1 alt ajan",
        badgeOpen = "Alt ajanları göster",
        live = "Canlı",
        back = "Üst oturuma dön",
        headerList = "1 çalışıyor",
        headerLoading = "Yükleniyor…",
        untitled = "Adsız alt ajan",
        title = "Alt ajanlar",
        summary = "3 alt ajan · 2 çalışıyor",
        rowOpen = "Oturumunu aç",
        rows = mapOf(
            EXPLORE to listOf(
                EXPLORE_TITLE,
                "Çalışıyor",
                "explore · 2 dk 5 sn · 14.190 token",
                "1.202 araç çağrısı · 1 başarısız · Şu an: bash",
            ),
            NESTED to listOf(NESTED_TITLE, "Çalışıyor", "general · 45 sn"),
            REVIEW to listOf(REVIEW_TITLE, "Tamamlandı", "general · 1 sa 3 dk · 1 token", "1 araç çağrısı"),
        ),
        statuses = mapOf(
            SubagentStatus.Running to "Çalışıyor",
            SubagentStatus.Succeeded to "Tamamlandı",
            SubagentStatus.Failed to "Başarısız",
            SubagentStatus.Interrupted to "Durduruldu",
            SubagentStatus.Idle to "Boşta",
        ),
        durations = SAMPLE_DURATIONS.zip(
            listOf("0 sn", "45 sn", "2 dk", "2 dk 5 sn", "1 sa", "1 sa 3 dk", "1 sa", "1.000 sa"),
        ).toMap(),
        loading = "Alt ajanlar yükleniyor…",
        emptyTitle = "Henüz alt ajan yok",
        errors = mapOf(
            ChatFailure.NotConnected to "$COULD_NOT Sunucuyla bağlantı yok.",
            ChatFailure.Unreachable to "$COULD_NOT Sunucuya ulaşılamadı.",
            ChatFailure.Unauthorized to "$COULD_NOT Sunucu kimlik bilgilerini reddetti.",
            ChatFailure.NotFound to "$COULD_NOT Oturum bulunamadı.",
            ChatFailure.Conflict to "$COULD_NOT Sunucu meşguldü.",
            ChatFailure.Rejected to "$COULD_NOT Sunucu isteği reddetti.",
            ChatFailure.ServerFailed to "$COULD_NOT Sunucu bir hatayla karşılaştı.",
            ChatFailure.Unknown to "$COULD_NOT Beklenmeyen bir hata oluştu.",
        ),
        retry = "Tekrar dene",
    )
}
