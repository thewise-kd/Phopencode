package io.github.thewisekd.phopencode.feature.connect.ui.keys

import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysError
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishKeysScreenTest : KeysScreenLanguageContract() {
    override val strings = ExpectedKeysStrings(
        title = "SSH anahtarları",
        loading = "Anahtarlar yükleniyor…",
        emptyTitle = "Henüz SSH anahtarı yok",
        emptyMessage = "Yeni bir anahtar oluşturun ya da bir özel anahtar dosyası içe aktarın.",
        import = "İçe aktar",
        generate = "Oluştur",
        type = { "Tür: $it" },
        fingerprint = { "SHA-256 parmak izi: $it" },
        copyDescription = { "$it açık anahtarını kopyala" },
        deleteDescription = { "$it anahtarını sil" },
        deleteTitle = "Bu anahtar silinsin mi?",
        deleteMessage = { "$it bu telefondan kaldırılacak. Açık anahtarına güvenen sunucular değişmez." },
        delete = "Sil",
        generateTitle = "Anahtar oluştur",
        generateName = "Ad",
        generatePassphraseLabel = "Parola (isteğe bağlı)",
        generateConfirm = "Anahtarı oluştur",
        passphraseTitle = "Anahtar parolası",
        passphraseMessage = "Bu anahtar dosyası şifreli. İçe aktarmak için parolasını girin.",
        passphraseSubmit = "Kilidi aç",
        errors = mapOf(
            KeysError.LoadFailed to "Anahtarlar yüklenemedi.",
            KeysError.GenerateFailed to "Anahtar oluşturulamadı. Tekrar deneyin.",
            KeysError.ImportMalformed to "Bu dosya bir özel anahtar değil ya da bozuk.",
            KeysError.ImportUnsupported to "Bu anahtar türü ya da şifrelemesi desteklenmiyor.",
            KeysError.WrongPassphrase to "Parola yanlış. Tekrar deneyin.",
            KeysError.KeyInUse to "Kayıtlı bir sunucu hâlâ bu anahtarla giriş yapıyor. Önce o sunucu için başka bir anahtar seçin.",
            KeysError.SaveFailed to "Anahtar bu telefona kaydedilemedi.",
            KeysError.DeleteFailed to "Anahtar silinemedi. Tekrar deneyin.",
        ),
        fileErrors = mapOf(
            KeyFileError.TooLarge to "Dosya bir özel anahtar olamayacak kadar büyük.",
            KeyFileError.Empty to "Dosya boş.",
            KeyFileError.Unreadable to "Dosya okunamadı.",
        ),
    )
}
