package io.github.thewisekd.phopencode.feature.files.ui.viewer

import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val COULD_NOT = "Dosya açılamadı."

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishViewerTest : ViewerLanguageContract() {
    override val strings = ExpectedViewerStrings(
        loading = "Dosya açılıyor…",
        edit = "Düzenle",
        rendered = "Biçimli",
        raw = "Ham",
        malformed = "Bazı baytlar geçerli UTF-8 değil ve � olarak görünüyor. Bu dosya düzenlenemez.",
        binaryTitle = "Metin dosyası değil",
        binaryMessage = "Bu dosya (2 KB) ne metin ne de uygulamanın gösterebileceği bir görsel.",
        tooLargeTitle = "Dosya çok büyük",
        tooLargeWarning = "Bu dosya 3 MB; 1 MB görüntüleme sınırını aşıyor. Açılması yavaş olabilir.",
        tooLargeBlocked = "Bu dosya 100 MB; 10 MB sınırını aşıyor. Telefonda açılamaz.",
        openAnyway = "Yine de aç",
        errors = mapOf(
            FilesFailure.NotConnected to "$COULD_NOT Sunucuyla bağlantı yok.",
            FilesFailure.Unreachable to "$COULD_NOT Sunucuya ulaşılamadı.",
            FilesFailure.Unauthorized to "$COULD_NOT Sunucu girişi reddetti.",
            FilesFailure.NotFound to "$COULD_NOT Dosya bulunamadı.",
            FilesFailure.PermissionDenied to "$COULD_NOT Sunucudaki kullanıcının bu dosyayı okuma izni yok.",
            FilesFailure.AlreadyExists to "$COULD_NOT Bu adı taşıyan bir öğe zaten var.",
            FilesFailure.NotEmpty to "$COULD_NOT Klasör boş değil.",
            FilesFailure.PathRejected to "$COULD_NOT Yol projenin dışında ya da geçersiz.",
            FilesFailure.TooLarge to "$COULD_NOT Dosya çok büyük.",
            FilesFailure.ToolMissing to "$COULD_NOT Gereken bir araç sunucuda kurulu değil.",
            FilesFailure.Rejected to "$COULD_NOT Sunucu isteği reddetti.",
            FilesFailure.ServerFailed to "$COULD_NOT Sunucu bir hatayla karşılaştı.",
            FilesFailure.Unsupported to "$COULD_NOT Bu henüz desteklenmiyor.",
            FilesFailure.Unknown to "$COULD_NOT Beklenmeyen bir hata oluştu.",
        ),
        retry = "Tekrar dene",
        imageDescription = "logo.png önizlemesi",
        sizes = mapOf(
            512L to "512 B",
            1_536L to "1,5 KB",
            5_242_880L to "5 MB",
            1_288_490_189L to "1,2 GB",
        ),
    )
}
