package io.github.thewisekd.phopencode.feature.connect.ui.serverform

import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormField
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormFieldError.Invalid
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormFieldError.Required
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi passes 26 only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishServerFormScreenTest : ServerFormLanguageContract() {
    override val strings = ExpectedServerFormStrings(
        titleNew = "Sunucu ekle",
        titleEdit = "Sunucuyu düzenle",
        name = "Ad",
        host = "Sunucu adresi",
        port = "SSH bağlantı noktası",
        username = "Kullanıcı adı",
        authPasswordLabel = "Parolayla giriş yap",
        authKeyLabel = "SSH anahtarıyla giriş yap",
        passwordLabel = "Parola",
        passwordKeepHint = "Kayıtlı parolayı korumak için boş bırakın",
        opencodePasswordLabel = "opencode serve parolası",
        opencodePasswordClearLabel = "Kayıtlı opencode serve parolasını kaldır",
        keysEmpty = "Henüz SSH anahtarı yok. Anahtarla giriş için bir tane ekleyin.",
        manageKeys = "Anahtarları yönet",
        loading = "Sunucu yükleniyor…",
        save = "Kaydet",
        saving = "Kaydediliyor…",
        cancel = "İptal",
        fieldErrors = mapOf(
            (ServerFormField.Name to Required) to "Bir ad girin",
            (ServerFormField.Name to Invalid) to "Bu ad kullanılamaz",
            (ServerFormField.Host to Required) to "Bir sunucu adı ya da adresi girin",
            (ServerFormField.Host to Invalid) to "Geçerli bir sunucu adı ya da adresi değil",
            (ServerFormField.Port to Required) to "Bir bağlantı noktası girin",
            (ServerFormField.Port to Invalid) to "1 ile 65535 arasında bir bağlantı noktası girin",
            (ServerFormField.Username to Required) to "Bir kullanıcı adı girin",
            (ServerFormField.Username to Invalid) to "Geçerli bir SSH kullanıcı adı değil",
            (ServerFormField.Password to Required) to "Parolayı girin",
            (ServerFormField.Password to Invalid) to "Bu parola kullanılamaz",
            (ServerFormField.Key to Required) to "Bir anahtar seçin",
            (ServerFormField.Key to Invalid) to "Bu anahtar artık kullanılamaz",
        ),
        formErrors = mapOf(
            ServerFormError.LoadFailed to "Sunucu ya da anahtarlar okunamadı",
            ServerFormError.NotFound to "Bu sunucu artık yok",
            ServerFormError.SaveFailed to "Sunucu kaydedilemedi. Hiçbir şey değişmedi.",
        ),
    )
}
