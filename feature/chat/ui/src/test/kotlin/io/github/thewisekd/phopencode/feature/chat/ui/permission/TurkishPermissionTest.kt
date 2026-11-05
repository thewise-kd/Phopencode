package io.github.thewisekd.phopencode.feature.chat.ui.permission

import io.github.thewisekd.phopencode.core.model.PermissionDecision
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val REASONS = mapOf(
    ChatFailure.NotConnected to "Sunucuyla bağlantı yok.",
    ChatFailure.Unreachable to "Sunucuya ulaşılamadı. Yeniden deneyin.",
    ChatFailure.Unauthorized to "Sunucu kimlik bilgilerini reddetti.",
    ChatFailure.NotFound to "İstek artık beklemiyor.",
    ChatFailure.Conflict to "Oturum meşgul ya da bu arada değişti.",
    ChatFailure.Rejected to "Sunucu yanıtı kabul etmedi.",
    ChatFailure.ServerFailed to "Sunucuda bir hata oluştu.",
    ChatFailure.Unknown to "Beklenmeyen bir hata oluştu.",
)

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishPermissionTest : PermissionLanguageContract() {
    override val strings = ExpectedPermissionStrings(
        title = "İzin isteği",
        askerSelf = "Bu oturum soruyor",
        askerSubagent = { "Bir alt ajan soruyor (oturum $it)" },
        action = { "İşlem: $it" },
        meanings = mapOf(
            "bash" to "Kabuk komutu çalıştırmak istiyor",
            "edit" to "Dosyaları değiştirmek istiyor",
            "read" to "Dosyaları okumak istiyor",
            "webfetch" to "Bir web sayfasını indirmek istiyor",
            "external_directory" to "Proje klasörünün dışına erişmek istiyor",
        ),
        noResources = "Sunucu isteğin neyi kapsadığını belirtmedi.",
        allowOnce = "Bir kez izin ver",
        allowAlways = "Her zaman izin ver",
        reject = "Reddet",
        confirmTitle = "Her zaman izin verilsin mi?",
        confirmSave = { "Sunucu bu kuralı kaydedecek ve aşağıdakiler için “$it” işleminde bir daha izin sormayacak:" },
        confirmNoSave = { "Sunucu bunu kaydedecek ve bu tür “$it” isteklerinde bir daha izin sormayacak." },
        wildcard = { "“*” her şeyle eşleşir: bundan sonra her “$it” isteği sorulmadan yürütülecek." },
        confirmAlways = "Evet, her zaman izin ver",
        cancel = "Vazgeç",
        sending = mapOf(
            PermissionDecision.Once to "Bir kez izin veriliyor…",
            PermissionDecision.Always to "Kalıcı izin veriliyor…",
            PermissionDecision.Reject to "Reddediliyor…",
        ),
        error = { decision, reason ->
            when (decision) {
                PermissionDecision.Once -> "Bir kez izin verilemedi. $reason"
                PermissionDecision.Always -> "Kalıcı izin verilemedi. $reason"
                PermissionDecision.Reject -> "Reddedilemedi. $reason"
            }
        },
        reasons = REASONS,
        listError = { "Alt ajanların izin istekleri listelenemedi. $it" },
        listReasons = REASONS + mapOf(
            ChatFailure.NotFound to "Oturum artık yok.",
            ChatFailure.Rejected to "Sunucu isteği kabul etmedi.",
        ),
    )
}
