package io.github.thewisekd.phopencode.feature.connect.ui.progress

import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressAction
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressStep
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishProgressScreenTest : ProgressScreenLanguageContract() {
    override val strings = ExpectedProgressStrings(
        title = "Bağlantı",
        connecting = "Bağlanıyor…",
        connected = "Bağlandı",
        failed = "Bağlantı kurulamadı",
        steps = mapOf(
            ProgressStep.Ssh to "SSH girişi",
            ProgressStep.HostApproval to "Sunucu anahtarı onayı",
            ProgressStep.Server to "opencode sunucusu",
            ProgressStep.Api to "API denetimi",
        ),
        done = "Tamamlandı",
        current = "Sürüyor",
        stepFailed = "Başarısız",
        pending = "Bekliyor",
        actions = mapOf(
            ProgressAction.Retry to "Tekrar dene",
            ProgressAction.EditServer to "Sunucuyu düzenle",
            ProgressAction.ReviewHostKey to "Sunucu anahtarını incele",
            ProgressAction.EnterServerPassword to "Parolayı gir",
        ),
        cancel = "İptal",
        passwordTitle = "opencode sunucu parolası",
        passwordMessage = "Bu uygulamanın başlatmadığı bir opencode sunucusu çalışıyor. " +
            "Sunucunun başlatıldığı parolayı girin.",
        passwordLabel = "Parola",
        passwordSubmit = "Bağlan",
        noSecondServer = "ikinci bir sunucu başlatmaz ve onu durdurmaz",
        errors = mapOf(
            ProgressError.InvalidProfile to "Bu sunucunun ayarları eksik. Sunucuyu düzenleyip tekrar deneyin.",
            ProgressError.SecretMissing to
                "Bu sunucunun kayıtlı giriş bilgisi bulunamadı. Sunucuyu düzenleyip yeniden girin.",
            ProgressError.SecretUnreadable to "Kayıtlı giriş bilgisi okunamadı. Sunucuyu düzenleyip yeniden girin.",
            ProgressError.StorageUnavailable to
                "Telefonun güvenli deposu kullanılamıyor. Telefonun kilidini açıp tekrar deneyin.",
            ProgressError.Unreachable to "Sunucuya ulaşılamadı. Adresi, portu ve ağınızı denetleyin.",
            ProgressError.Timeout to "Sunucu çok geç yanıt verdi. Ağınızı denetleyip tekrar deneyin.",
            ProgressError.Disconnected to "Bağlantı koptu. Tekrar deneyin.",
            ProgressError.AuthenticationFailed to
                "Sunucu girişi reddetti. Kullanıcı adını, parolayı ya da anahtarı denetleyin.",
            ProgressError.HostKeyRejected to "Sunucu anahtarı onaylanmadı, bu yüzden bağlantı durduruldu.",
            ProgressError.HostKeyChanged to "Sunucu anahtarı değişti. Yeniden bağlanmadan önce inceleyin.",
            ProgressError.OpencodeMissing to "Sunucuda opencode bulunamadı. Oraya kurup tekrar deneyin.",
            ProgressError.ServerPasswordUnknown to
                "Bu uygulamanın başlatmadığı bir opencode sunucusu çalışıyor. Bağlanmak için parolasını girin.",
            ProgressError.ServerPasswordRejected to "opencode sunucusu parolayı reddetti. Yeniden girin.",
            ProgressError.ServerVersionTooOld to "Sunucudaki opencode çok eski. Güncelleyip tekrar deneyin.",
            ProgressError.ServerUnresponsive to
                "Bir opencode sunucusu çalışıyor ama yanıt vermiyor. Uygulama ikinci bir sunucu başlatmaz ve onu " +
                "durdurmaz: sunucuda yeniden başlatıp tekrar deneyin.",
            ProgressError.ServerUnusable to
                "Bir opencode sunucusu çalışıyor ama loopback (127.0.0.1) üzerinden ulaşılamıyor. Uygulama ikinci " +
                "bir sunucu başlatmaz ve onu durdurmaz: sunucuda 127.0.0.1 üzerinde dinleyecek şekilde yeniden " +
                "başlatıp tekrar deneyin.",
            ProgressError.ServerNotOnPort to
                "4096 portunda dinleyen bir opencode sunucusu yok. Sunucu ayarlarındaki portu denetleyin ya da boş bırakın.",
            ProgressError.ServerDetectionFailed to
                "Sunucuda opencode'un çalışıp çalışmadığı anlaşılamadı. Tekrar deneyin.",
            ProgressError.ServerStartFailed to
                "opencode sunucuda başlatılamadı. Orada çalıştığını denetleyip tekrar deneyin.",
            ProgressError.ServerLost to "opencode sunucusu bağlanırken durdu. Tekrar deneyin.",
            ProgressError.ApiFailed to "opencode sunucusunun API'si hata döndürdü. Tekrar deneyin.",
            ProgressError.Unexpected to "Beklenmeyen bir sorun oluştu. Tekrar deneyin.",
        ),
    )
}
