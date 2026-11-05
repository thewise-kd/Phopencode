package io.github.thewisekd.phopencode.feature.files.ui.ops

import io.github.thewisekd.phopencode.feature.files.logic.contract.FileAction
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOp
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.NameProblem
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val TAKEN = "Bu klasörde bu adı taşıyan bir öğe zaten var."

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishFileOpsTest : FileOpsLanguageContract() {
    override val strings = ExpectedFileOpsStrings(
        actions = mapOf(
            FileAction.Edit to "Düzenle",
            FileAction.Download to "İndir",
            FileAction.Rename to "Yeniden adlandır",
            FileAction.Delete to "Sil",
        ),
        cancel = "İptal",
        renameTitle = "Yeniden adlandır",
        renameLabel = "Yeni ad",
        renameSubmit = "Yeniden adlandır",
        newFolderTitle = "Yeni klasör",
        newFolderLabel = "Klasör adı",
        newFolderSubmit = "Oluştur",
        problems = mapOf(
            NameProblem.Blank to "Bir ad girin.",
            NameProblem.Separator to "Ad / içeremez.",
            NameProblem.Reserved to "\".\" ve \"..\" ad olarak kullanılamaz.",
            NameProblem.TooLong to "Ad çok uzun.",
            NameProblem.ControlCharacter to "Ad, izin verilmeyen bir karakter içeriyor.",
            NameProblem.Taken to TAKEN,
        ),
        deleteFileTitle = "Dosya silinsin mi?",
        deleteFileMessage = "ana kod.kt sunucudan silinecek. Bu işlem geri alınamaz.",
        deleteFolderTitle = "Klasör silinsin mi?",
        deleteFolderMessage = "çizimler klasörü ve içindeki her şey sunucudan silinecek. Bu işlem geri alınamaz.",
        delete = "Sil",
        busy = mapOf(
            FileOp.Rename to "Yeniden adlandırılıyor…",
            FileOp.Delete to "Siliniyor…",
            FileOp.CreateFolder to "Klasör oluşturuluyor…",
        ),
        errors = mapOf(
            FileOp.Rename to { "Öğe yeniden adlandırılamadı. $it" },
            FileOp.Delete to { "Öğe silinemedi. $it" },
            FileOp.CreateFolder to { "Klasör oluşturulamadı. $it" },
        ),
        reasons = mapOf(
            FilesFailure.NotConnected to "Sunucuyla bağlantı yok.",
            FilesFailure.Unreachable to "Sunucuya ulaşılamadı.",
            FilesFailure.Unauthorized to "Sunucu girişi reddetti.",
            FilesFailure.NotFound to "Artık orada değil; taşınmış ya da silinmiş olabilir.",
            FilesFailure.PermissionDenied to "Sunucudaki kullanıcının buna izni yok.",
            FilesFailure.AlreadyExists to TAKEN,
            FilesFailure.NotEmpty to "Klasör boş değil.",
            FilesFailure.PathRejected to "Yol projenin dışında ya da geçersiz.",
            FilesFailure.TooLarge to "Çok büyük.",
            FilesFailure.ToolMissing to "Gereken bir araç sunucuda kurulu değil.",
            FilesFailure.Rejected to "Sunucu isteği reddetti.",
            FilesFailure.ServerFailed to "Sunucu bir hatayla karşılaştı.",
            FilesFailure.Unsupported to "Bu henüz desteklenmiyor.",
            FilesFailure.Unknown to "Beklenmeyen bir hata oluştu.",
        ),
        ok = "Tamam",
    )
}
