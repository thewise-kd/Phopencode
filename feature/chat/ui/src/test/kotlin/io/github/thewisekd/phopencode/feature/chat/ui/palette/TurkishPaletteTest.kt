package io.github.thewisekd.phopencode.feature.chat.ui.palette

import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.BuiltInCommand
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.BuiltInNotice
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val REASONS = mapOf(
    ChatFailure.NotConnected to "Sunucuyla bağlantı yok.",
    ChatFailure.Unreachable to "Sunucuya ulaşılamadı.",
    ChatFailure.Unauthorized to "Sunucu kimlik bilgilerini reddetti.",
    ChatFailure.NotFound to "Oturum bulunamadı.",
    ChatFailure.Conflict to "Sunucu meşguldü.",
    ChatFailure.Rejected to "Sunucu isteği reddetti.",
    ChatFailure.ServerFailed to "Sunucu bir hatayla karşılaştı.",
    ChatFailure.Unknown to "Beklenmeyen bir hata oluştu.",
)

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishPaletteTest : PaletteLanguageContract() {
    override val strings = ExpectedPaletteStrings(
        titleSlash = "Komutlar ve beceriler",
        titleAt = "Ajanlar ve dosyalar",
        close = "Kapat",
        rows = mapOf(
            INIT to listOf("/init", "Create AGENTS.md", "Komut"),
            REVIEW to listOf("/review", REVIEW_DESCRIPTION, "Komut"),
            PDF to listOf("pdf-özet", "Beceri"),
            OPENCODE to listOf("OpenCode", "/opencode", "Çalışma alanını yönet", "Beceri"),
            EXPLORE to listOf("Explore", "Searches the code", "Ajan"),
            SOURCE to listOf("src/çekirdek/Main.kt", "Dosya"),
            FOLDER to listOf("src/çekirdek", "Klasör"),
        ),
        builtIns = mapOf(
            BuiltInCommand.New to listOf("/new", "Bu klasörde yeni oturum başlat", "Diğer adları: /clear", "Yerleşik"),
            BuiltInCommand.Compact to
                listOf("/compact", "Bağlamı boşaltmak için oturumu özetle", "Diğer adları: /summarize", "Yerleşik"),
            BuiltInCommand.Fork to listOf("/fork", "Oturumu yeni bir oturuma kopyala", "Yerleşik"),
            BuiltInCommand.Undo to listOf("/undo", "Son turu geri al", "Yerleşik"),
            BuiltInCommand.Redo to listOf("/redo", "Bekleyen geri almayı iptal et", "Yerleşik"),
            BuiltInCommand.Models to listOf("/models", "Modeli değiştir", "Yerleşik"),
            BuiltInCommand.Agents to listOf("/agents", "Ajanı değiştir", "Yerleşik"),
            BuiltInCommand.Sessions to
                listOf("/sessions", "Oturum listesine dön", "Diğer adları: /resume, /continue", "Yerleşik"),
        ),
        notices = mapOf(
            BuiltInNotice.Unavailable to "Bu komut şu anda kullanılamıyor.",
            BuiltInNotice.NothingToUndo to "Geri alınacak tur yok.",
            BuiltInNotice.NothingToRedo to "İptal edilecek bekleyen geri alma yok.",
        ),
        noticeDismiss = "Kapat",
        run = "Çalıştır",
        attach = "Ekle",
        loading = "Yükleniyor…",
        empty = "Eşleşen sonuç yok",
        running = "Komut çalıştırılıyor…",
        loadErrors = REASONS.mapValues { "Liste yüklenemedi. ${it.value}" },
        commandErrors = REASONS.mapValues { "Komut çalıştırılamadı. ${it.value}" },
        retry = "Tekrar dene",
        dismiss = "Hatayı kapat",
        chips = mapOf(
            FILE_ATTACHMENT to listOf("Dosya", "src/çekirdek/Main.kt"),
            AGENT_ATTACHMENT to listOf("Ajan", "explore"),
            SKILL_ATTACHMENT to listOf("Beceri", "pdf-özet"),
        ),
        remove = mapOf(
            FILE_ATTACHMENT to "Dosyayı kaldır: src/çekirdek/Main.kt",
            AGENT_ATTACHMENT to "Ajanı kaldır: explore",
            SKILL_ATTACHMENT to "Beceriyi kaldır: pdf-özet",
        ),
    )
}
