package io.github.thewisekd.phopencode.feature.files.ui.transfer

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.core.app.ActivityOptionsCompat
import io.github.thewisekd.phopencode.core.navigation.FilesRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.LocalDocument
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferDirection
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferId
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferJob
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferState
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal const val ROOT = "/srv/çalışma alanı"
internal const val FOLDER = "$ROOT/belgeler"

internal val ROUTE = FilesRoute.Transfer(directory = ROOT, path = FOLDER)

internal val EMPTY = TransferState(directory = ROOT, folder = FOLDER)

internal fun job(
    id: Long,
    name: String,
    status: TransferStatus = TransferStatus.Queued,
    direction: TransferDirection = TransferDirection.Upload,
    bytesDone: Long = 0,
    totalBytes: Long? = null,
) = TransferJob(
    id = TransferId(id),
    direction = direction,
    directory = ROOT,
    remotePath = "$FOLDER/$name",
    localName = name,
    bytesDone = bytesDone,
    totalBytes = totalBytes,
    status = status,
)

internal val QUEUED = job(1, "rapor.pdf")
internal val UPLOADING = job(2, "ana kod.kt", TransferStatus.Running, bytesDone = 45, totalBytes = 100)
internal val DOWNLOADING_UNKNOWN = job(3, "veri.bin", TransferStatus.Running, TransferDirection.Download, bytesDone = 7)
internal val AWAITING = job(4, "notlar.md", TransferStatus.AwaitingOverwrite)
internal val UPLOADED = job(5, "resim.png", TransferStatus.Done, totalBytes = 10, bytesDone = 10)
internal val DOWNLOADED = job(6, "günlük.txt", TransferStatus.Done, TransferDirection.Download)
internal val CANCELLED = job(7, "eski.zip", TransferStatus.Cancelled)
internal val FAILED = job(8, "büyük.iso", TransferStatus.Failed(FilesFailure.TooLarge))

/** A document with a name, as one picked in the system picker; holds bytes in memory. */
internal class FakeDocument(override val name: String, override val sizeBytes: Long? = null) : LocalDocument {
    val written = ByteArrayOutputStream()
    var deleted = false

    override fun openInput(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun openOutput(): OutputStream = written

    override fun delete(): Boolean {
        deleted = true
        return true
    }
}

/**
 * Opens each picked URI as a document named after its last segment, and records them. As the real
 * access, it reads nothing when opening: the name is looked up when first asked for.
 */
internal class FakeDocuments : DocumentAccess {
    val opened: MutableList<Uri> = Collections.synchronizedList(mutableListOf())
    val writable: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
    val lookups = AtomicInteger()

    /** While closed, every lookup waits, as a slow provider would; open by default. */
    @Volatile
    var gate: CountDownLatch = CountDownLatch(0)

    override fun open(uri: Uri, fallbackName: String, forWriting: Boolean): LocalDocument {
        opened += uri
        writable += forWriting
        return LazyFakeDocument(uri.lastPathSegment ?: fallbackName)
    }

    private inner class LazyFakeDocument(private val realName: String) : LocalDocument {
        override val name: String by lazy {
            lookups.incrementAndGet()
            gate.await(10, TimeUnit.SECONDS)
            realName
        }
        override val sizeBytes: Long? = null

        override fun openInput(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun openOutput(): OutputStream = ByteArrayOutputStream()

        override fun delete(): Boolean = true
    }
}

/** Answers every launch at once with what [answer] gives for its input, as the system picker would. */
internal class FakePickerRegistry(var answer: (Any?) -> Any? = { null }) : ActivityResultRegistry() {
    val inputs = mutableListOf<Any?>()

    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
        inputs += input
        dispatchResult(requestCode, answer(input))
    }
}

/** The screen in the app theme, with [state] changeable by the test and everything it does recorded. */
internal class TransferHarness(initial: TransferState) {
    val state: MutableState<TransferState> = mutableStateOf(initial)
    val intents = mutableListOf<TransferIntent>()
    val documents = FakeDocuments()
    val registry = FakePickerRegistry()
    var serviceStarts = 0

    @Composable
    fun Content() {
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
            PhopencodeTheme(darkTheme = false) {
                TransferScreen(
                    route = ROUTE,
                    state = state.value,
                    onIntent = { intents += it },
                    documents = documents,
                    startService = { serviceStarts++ },
                )
            }
        }
    }
}

internal fun ComposeContentTestRule.setTransfers(state: TransferState): TransferHarness {
    val harness = TransferHarness(state)
    setContent { harness.Content() }
    return harness
}

/** Whether some node carries [tag]. */
internal fun ComposeContentTestRule.hasTag(tag: String): Boolean =
    onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
