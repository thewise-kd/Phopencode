package io.github.thewisekd.phopencode.feature.files.ui.transfer

import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishTransferTest : TransferLanguageContract() {
    override val strings = ExpectedTransferStrings(
        title = "Aktarımlar",
        upload = "Yükle",
        clearFinished = "Bitenleri temizle",
        folder = "Yükleme klasörü: belgeler",
        emptyTitle = "Aktarım yok",
        emptyMessage = "Bu telefondan klasöre dosya yükleyebilir ya da dosya listesinde bir dosyanın menüsünden " +
            "indirebilirsiniz.",
        queued = "Bekliyor",
        uploading = "Yükleniyor",
        uploadingPercent = "Yükleniyor, %45",
        downloading = "İndiriliyor",
        awaiting = "Yanıtınız bekleniyor",
        uploaded = "Yüklendi",
        downloaded = "İndirildi",
        cancelled = "İptal edildi",
        failed = { "Başarısız. $it" },
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
        cancel = "İptal",
        overwriteTitle = "notlar.md değiştirilsin mi?",
        overwriteMessage = "Bu klasörde notlar.md adlı bir dosya zaten var. Değiştirirseniz bu işlem geri alınamaz.",
        replace = "Değiştir",
        skip = "Atla",
        pickerMissing = "Bu telefonda dosya seçebilecek bir uygulama yok.",
        channel = "Dosya aktarımları",
        notificationUploading = "ana kod.kt yükleniyor",
        notificationDownloading = "veri.bin indiriliyor",
        notificationWaiting = "rapor.pdf aktarım için bekliyor",
        notificationAwaitingTitle = "notlar.md zaten var",
        notificationAwaitingText = "Değiştirmek ya da atlamak için uygulamayı açın.",
        notificationOneMore = "1 dosya daha bekliyor",
        notificationThreeMore = "3 dosya daha bekliyor",
        notificationCancel = "Tümünü iptal et",
    )
}
