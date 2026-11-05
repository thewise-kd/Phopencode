package io.github.thewisekd.phopencode.feature.files.ui.editor

import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorBlock
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishEditorTest : EditorLanguageContract() {
    override val strings = ExpectedEditorStrings(
        loading = "Dosya açılıyor…",
        save = "Kaydet",
        saving = "Kaydediliyor…",
        unsaved = "Kaydedilmemiş değişiklikler",
        blockedTitle = "Bu dosya düzenlenemez",
        blocks = mapOf(
            EditorBlock.Binary to "Metin dosyası değil.",
            EditorBlock.TooLarge to "Telefonda düzenlenemeyecek kadar büyük.",
            EditorBlock.Malformed to "Bazı baytları geçerli UTF-8 değil ve kaydetmek bunları değiştirir.",
        ),
        loadError = { "Dosya açılamadı. $it" },
        saveError = { "Dosya kaydedilemedi. $it Değişiklikleriniz korunuyor." },
        reasons = mapOf(
            FilesFailure.NotConnected to "Sunucuyla bağlantı yok.",
            FilesFailure.Unreachable to "Sunucuya ulaşılamadı.",
            FilesFailure.Unauthorized to "Sunucu girişi reddetti.",
            FilesFailure.NotFound to "Dosya bulunamadı.",
            FilesFailure.PermissionDenied to "Sunucudaki kullanıcının bu dosya için izni yok.",
            FilesFailure.AlreadyExists to "Bu adı taşıyan bir öğe zaten var.",
            FilesFailure.NotEmpty to "Klasör boş değil.",
            FilesFailure.PathRejected to "Yol projenin dışında ya da geçersiz.",
            FilesFailure.TooLarge to "Dosya çok büyük.",
            FilesFailure.ToolMissing to "Gereken bir araç sunucuda kurulu değil.",
            FilesFailure.Rejected to "Sunucu isteği reddetti.",
            FilesFailure.ServerFailed to "Sunucu bir hatayla karşılaştı.",
            FilesFailure.Unsupported to "Bu henüz desteklenmiyor.",
            FilesFailure.Unknown to "Beklenmeyen bir hata oluştu.",
        ),
        conflictTitle = "Dosya sunucuda değişti",
        conflictMessage = "Siz açtıktan ya da son kaydettikten sonra bu dosya değiştirildi. Kendi sürümünüzle " +
            "üzerine yazabilir ya da sunucudaki sürümü yükleyip değişikliklerinizi atabilirsiniz.",
        overwrite = "Üzerine yaz",
        reload = "Yeniden yükle",
        keepEditing = "Düzenlemeye devam et",
        leaveTitle = "Kaydedilmemiş değişiklikler atılsın mı?",
        leaveMessage = "ana kod.kt dosyasındaki değişiklikleriniz kaydedilmedi. Şimdi çıkarsanız atılır.",
        discard = "Değişiklikleri at",
    )
}
