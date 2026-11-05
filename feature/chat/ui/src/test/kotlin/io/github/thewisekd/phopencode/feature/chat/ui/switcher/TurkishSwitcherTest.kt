package io.github.thewisekd.phopencode.feature.chat.ui.switcher

import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherAction
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishSwitcherTest : SwitcherLanguageContract() {
    override val strings = ExpectedSwitcherStrings(
        agentLabel = { "Ajan: $it" },
        modelLabel = { "Model: $it" },
        variantLabel = { "Varyant: $it" },
        defaultAgent = "Varsayılan ajan",
        defaultModel = "Varsayılan model",
        change = "Ajanı ve modeli değiştir",
        title = "Ajan ve model seçin",
        close = "Kapat",
        agents = "Ajan",
        models = "Model",
        defaultVariant = "Varsayılan",
        loading = "Ajanlar ve modeller yükleniyor…",
        switching = "Değiştiriliyor…",
        noAgents = "Sunucu seçilebilecek bir ajan listelemedi.",
        noModels = "Sunucu seçilebilecek bir model listelemedi.",
        error = { action, reason ->
            when (action) {
                SwitcherAction.Load -> "Ajanlar ve modeller yüklenemedi. $reason"
                SwitcherAction.SwitchAgent -> "Ajan değiştirilemedi. $reason"
                SwitcherAction.SwitchModel -> "Model değiştirilemedi. $reason"
            }
        },
        reasons = mapOf(
            ChatFailure.NotConnected to "Sunucuyla bağlantı yok.",
            ChatFailure.Unreachable to "Sunucuya ulaşılamadı. Yeniden deneyin.",
            ChatFailure.Unauthorized to "Sunucu kimlik bilgilerini reddetti.",
            ChatFailure.NotFound to "Oturum, ajan ya da model bulunamadı.",
            ChatFailure.Conflict to "Oturum meşgul ya da bu arada değişti.",
            ChatFailure.Rejected to "Sunucu isteği kabul etmedi.",
            ChatFailure.ServerFailed to "Sunucuda bir hata oluştu.",
            ChatFailure.Unknown to "Beklenmeyen bir hata oluştu.",
        ),
    )
}
