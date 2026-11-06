package io.github.thewisekd.phopencode.feature.git.ui.diff

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffContent
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffState
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishDiffTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the header and the lines are in Turkish`() {
        compose.setDiff(loaded(fileDiff(kind = GitChangeKind.Renamed, oldPath = OLD_FILE)))

        compose.scrollListTo(DiffTags.KIND).assertTextEquals("Yeniden adlandırıldı")
        compose.onNodeWithTag(DiffTags.CONTEXT).assertTextEquals("Çalışma ağacı")
        compose.onNodeWithTag(DiffTags.OLD_PATH).assertTextEquals("Eski yol: $OLD_FILE")
        compose.scrollListTo(DiffTags.COUNTS).assertContentDescriptionEquals("3 ekleme, 2 silme")
        compose.scrollListTo(DiffTags.hunk(0)).assertContentDescriptionEquals("Değişiklik: satır 1, önceden satır 1")
        compose.lineRow(0, 2).assertContentDescriptionEquals("Eklendi, satır 2: val a = 2")
        compose.lineRow(0, 1).assertContentDescriptionEquals("Silindi, satır 2: val a = 1")
        compose.lineRow(0, 4).assertContentDescriptionEquals("Değişmedi, satır 4: boş")
        compose.lineRow(1, 1).assertContentDescriptionEquals("Dosyanın sonunda satır sonu yok")
        compose.onNodeWithTag(DiffTags.REFRESH).assertTextEquals("Yenile")
        compose.onNodeWithContentDescription("Geri dön").assertExists()
    }

    @Test
    fun `a staged and a commit diff name their context in Turkish`() {
        val harness = compose.setDiff(loaded(fileDiff(), STAGED_ROUTE), STAGED_ROUTE)
        compose.scrollListTo(DiffTags.CONTEXT).assertTextEquals("Hazırlanan")

        harness.state.value = loaded(fileDiff(), COMMIT_ROUTE)
        compose.scrollListTo(DiffTags.CONTEXT).assertTextEquals("Commit 0123456")
    }

    @Test
    fun `the states are in Turkish with Turkish numbers`() {
        val harness = compose.setDiff(DiffState(target = targetOf(WORKING_ROUTE)))
        compose.onNodeWithTag(DiffTags.LOADING).assertTextEquals("Fark okunuyor…")

        harness.state.value = loaded(fileDiff(DiffContent.TooLarge(limitBytes = 1_572_864)))
        compose.onNodeWithText("Fark çok büyük").assertExists()
        compose.onNodeWithText("Bu fark 1,5 MB sınırını aşıyor; hiçbir kısmı gösterilmiyor.").assertExists()

        harness.state.value = loaded(fileDiff(DiffContent.Binary, additions = 0, deletions = 0))
        compose.onNodeWithText("İkili dosya").assertExists()

        harness.state.value = loaded(fileDiff(DiffContent.Unchanged, additions = 0, deletions = 0))
        compose.onNodeWithText("Hiçbir satır değişmedi").assertExists()

        harness.state.value = DiffState(target = targetOf(WORKING_ROUTE), loading = false, error = GitFailure.NotRepository)
        compose.onNodeWithText("Fark okunamadı. Klasör bir git deposu değil.").assertExists()
        compose.onNodeWithText("Tekrar dene").assertExists()

        harness.state.value = DiffState(target = targetOf(WORKING_ROUTE), loading = false, error = GitFailure.GitError(1))
        compose.onNodeWithText("Fark okunamadı. git 1 çıkış koduyla başarısız oldu.").assertExists()
    }
}
