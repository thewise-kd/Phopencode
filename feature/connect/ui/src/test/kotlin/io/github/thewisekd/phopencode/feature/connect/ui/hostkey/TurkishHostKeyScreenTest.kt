package io.github.thewisekd.phopencode.feature.connect.ui.hostkey

import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyError
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishHostKeyScreenTest : HostKeyScreenLanguageContract() {
    override val strings = ExpectedHostKeyStrings(
        title = "Sunucu anahtarı",
        loading = "Sunucunun anahtarı bekleniyor…",
        none = "Denetlenecek bir sunucu anahtarı yok.",
        deciding = "Kararınız kaydediliyor…",
        type = { "Anahtar türü: $it" },
        fingerprintLabel = "SHA-256 parmak izi",
        firstTitle = "Bu sunucunun anahtarını denetleyin",
        firstMessage = "Bu sunucuya ilk kez bağlanıyorsunuz. Aşağıdaki parmak izini sunucu yöneticisinin " +
            "verdiğiyle, örneğin sunucuda ssh-keygen -l çıktısıyla karşılaştırın. Onaylarsanız bu anahtar " +
            "kaydedilir: sunucu ileride başka bir anahtar gösterirse bağlantı durdurulur.",
        approve = "Onayla ve bağlan",
        reject = "Reddet",
        changedTitle = "Uyarı: bu sunucunun anahtarı değişti",
        changedMessage = "Sunucu, onayladığınızdan farklı bir anahtar gösterdi. Biri bağlantının arasına girmiş " +
            "olabilir (ortadaki adam saldırısı). Sunucu yeniden kurulduysa ya da anahtarları değiştirildiyse de bu " +
            "olabilir. Bağlantı durduruldu. Yeni anahtarı yalnızca sunucu yöneticisi değişikliği doğrularsa " +
            "kabul edin.",
        previousLabel = "Önceden onaylanan parmak izi (SHA-256)",
        newLabel = "Yeni parmak izi (SHA-256)",
        changedReject = "Reddet ve bağlanma",
        changedAccept = "Yeni anahtara güven…",
        confirmTitle = "Onaylı anahtar değiştirilsin mi?",
        confirmMessage = "Bunu yalnızca yeni parmak izini sunucu yöneticisiyle doğruladıysanız yapın. Araya giren " +
            "bir saldırgan, parolalar dahil bu sunucuya gönderdiğiniz her şeyi okuyabilir.",
        confirmAccept = "Anahtarı değiştir",
        errors = mapOf(
            HostKeyError.NoRequest to
                "Bağlantı artık bu anahtar için bir karar beklemiyor. Denetlemek için yeniden bağlanın.",
            HostKeyError.ServerMissing to "Bu sunucunun profili artık yok, bu yüzden anahtarı denetlenemiyor.",
            HostKeyError.StorageFailed to
                "Onaylı sunucu anahtarları okunamadı ya da kaydedilemedi. Hiçbir şey değişmedi.",
            HostKeyError.Superseded to
                "Siz karar verirken onaylı anahtar değişti. Hiçbir şey değiştirilmedi; anahtarı denetlemek için " +
                "yeniden bağlanın.",
        ),
    )
}
