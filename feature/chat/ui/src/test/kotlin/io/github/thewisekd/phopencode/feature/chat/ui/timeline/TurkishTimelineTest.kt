package io.github.thewisekd.phopencode.feature.chat.ui.timeline

import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TurnOutcome
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishTimelineTest : TimelineLanguageContract() {
    override val strings = ExpectedTimelineStrings(
        loading = "Mesajlar yükleniyor…",
        emptyTitle = "Henüz mesaj yok",
        emptyMessage = "Başlamak için aşağıya bir mesaj yazın.",
        loadingOlder = "Eski mesajlar yükleniyor…",
        olderFailed = "Eski mesajlar yüklenemedi.",
        retry = "Yeniden dene",
        jumpToLatest = "En son mesaja git",
        unseenOne = "1 yeni mesaj",
        unseenFive = "5 yeni mesaj",
        you = "Siz",
        assistant = "Asistan",
        writing = "Yazıyor…",
        pending = mapOf(
            PromptDelivery.Steer to "Çalışan işi yönlendirmeyi bekliyor",
            PromptDelivery.Queue to "Çalışma bitene kadar sırada",
        ),
        files = "Dosyalar: a.kt, b.kt",
        agents = "Ajanlar: plan",
        skills = "Beceriler: review",
        answerFailed = "Yanıt başarısız oldu: Rate limited",
        shellLabel = "Kabuk komutu",
        shellRunning = "Çalışıyor…",
        compactionRunning = "Konuşma sıkıştırılıyor…",
        compactionDone = "Konuşma sıkıştırıldı.",
        compactionFailed = "Konuşma sıkıştırılamadı.",
        agentSwitched = "Ajan: build",
        agentSwitchedFrom = "Ajan değişti: plan → build",
        modelSwitched = "Model: openai/gpt",
        modelSwitchedFrom = "Model değişti: anthropic/claude → openai/gpt",
        locationSwitched = "Klasör: /home/dev/app",
        skillInvoked = "Kullanılan beceri: review",
        turns = mapOf(
            TurnOutcome.Succeeded to "Çalışma tamamlandı.",
            TurnOutcome.Failed to "Çalışma başarısız oldu.",
            TurnOutcome.Interrupted to "Çalışma durduruldu.",
            TurnOutcome.Unknown to "Çalışma sona erdi.",
        ),
        turnFailedDetail = "Çalışma başarısız oldu: Rate limited",
        unknown = "Bu mesaj türü henüz desteklenmiyor (poll).",
    )
}
