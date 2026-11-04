package io.github.thewisekd.phopencode.feature.connect.ui.keys

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.storage_api.SshKeyEntry
import io.github.thewisekd.phopencode.core.storage_api.SshKeyId
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysState
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A made-up key: the blob is not a real key, and no private half exists anywhere. */
internal fun testKey(id: String, name: String = "key-$id", type: String = "ssh-ed25519") = SshKeyEntry(
    id = SshKeyId(id),
    name = name,
    type = type,
    fingerprint = "SHA256:fake-fingerprint-$id",
    publicKey = "$type AAAAfakepublic$id comment-$id",
    privateKeyRef = SecretRef("private-$id"),
)

internal val PICKED_URI: Uri = Uri.parse("content://test.documents/document/picked")
internal val EARLIER_URI: Uri = Uri.parse("content://test.documents/document/earlier")

/**
 * A file source a test controls, in place of the content resolver. [contentByUri] overrides [content]
 * for one URI; [onOpen] runs on the reading thread, before anything is read, and may block it.
 */
internal class FakeKeyFileSource(
    @Volatile var content: ByteArray? = null,
    @Volatile var name: String? = null,
    @Volatile var failure: Exception? = null,
) : KeyFileSource {
    val opened: MutableList<Uri> = Collections.synchronizedList(mutableListOf())
    val contentByUri: MutableMap<Uri, ByteArray?> = ConcurrentHashMap()

    @Volatile var openedOnMainThread: Boolean? = null

    @Volatile var closed = false

    @Volatile var onOpen: (Uri) -> Unit = {}

    /** When set, the stream gives its content, then hangs in `read()` until it is closed: a stuck provider. */
    @Volatile var stuck: StuckInputStream? = null

    /** The stream's `close()` fails with an [IOException], after closing, as a broken provider pipe can. */
    @Volatile var closeFails = false

    override fun open(uri: Uri): InputStream? {
        opened += uri
        onOpen(uri)
        openedOnMainThread = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        failure?.let { throw it }
        stuck?.let { return it }
        val bytes = if (contentByUri.containsKey(uri)) contentByUri[uri] else content
        if (bytes == null) return null
        return object : ByteArrayInputStream(bytes) {
            override fun close() {
                closed = true
                super.close()
                if (closeFails) throw IOException("The provider failed to close the stream")
            }
        }
    }

    override fun displayName(uri: Uri): String? = name
}

/**
 * Gives [prefix], then blocks in `read()` until another thread closes it, as a provider pipe does when
 * the provider stalls; a read on a closed stream fails. [MAX_WAIT_SECONDS] only keeps a broken test
 * from hanging forever.
 */
internal class StuckInputStream(
    private val prefix: ByteArray,
    private val closeFails: Boolean = false,
) : InputStream() {
    private val closedSignal = CountDownLatch(1)
    private var given = false

    /** The thread blocked in `read()` came back out, by an exception or not. */
    @Volatile var readReturned = false

    val isClosed: Boolean get() = closedSignal.count == 0L

    override fun read(): Int = throw UnsupportedOperationException("The reader reads in blocks")

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (!given) {
            given = true
            prefix.copyInto(b, off, 0, minOf(len, prefix.size))
            return minOf(len, prefix.size)
        }
        try {
            closedSignal.await(MAX_WAIT_SECONDS, TimeUnit.SECONDS)
            throw IOException("The stream was closed while reading")
        } finally {
            readReturned = true
        }
    }

    override fun close() {
        closedSignal.countDown()
        if (closeFails) throw IOException("The provider failed to close the stream")
    }

    companion object {
        const val MAX_WAIT_SECONDS = 30L
    }
}

/** Hands out the reader's buffers and keeps them, so a test can see that each one was wiped. */
internal class TrackingAllocator {
    private val arrays = Collections.synchronizedList(mutableListOf<ByteArray>())

    fun allocate(size: Int): ByteArray = ByteArray(size).also { arrays += it }

    val count: Int get() = arrays.size

    /** Every buffer is all zeros, except those handed on in an intent, matched by identity. */
    fun allWipedExcept(vararg handedOn: ByteArray): Boolean = synchronized(arrays) {
        arrays.filter { array -> handedOn.none { it === array } }.all { array -> array.all { it == 0.toByte() } }
    }
}

/** Answers every launch at once with [result], as the system picker would after a choice. */
internal class FakePickerRegistry(var result: Uri? = PICKED_URI) : ActivityResultRegistry() {
    val inputs = mutableListOf<Any?>()

    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
        inputs += input
        dispatchResult(requestCode, result)
    }
}

/** The screen in the app theme, with [state] changeable by the test and every intent recorded. */
internal class KeysScreenHarness(initial: KeysState) {
    val state: MutableState<KeysState> = mutableStateOf(initial)
    val intents = mutableListOf<KeysIntent>()
    val source = FakeKeyFileSource()
    val registry = FakePickerRegistry()
    val allocator = TrackingAllocator()

    @Composable
    fun Content() {
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
            PhopencodeTheme {
                Surface {
                    KeysScreen(
                        route = ConnectRoute.Keys,
                        state = state.value,
                        onIntent = { intents += it },
                        fileSource = source,
                        newBuffer = allocator::allocate,
                    )
                }
            }
        }
    }
}

internal fun ComposeContentTestRule.setKeysScreen(state: KeysState): KeysScreenHarness {
    val harness = KeysScreenHarness(state)
    setContent { harness.Content() }
    return harness
}

private val TOUCH_SIZE_TOLERANCE = 0.5.dp

/** The node's touch bounds are at least [minimum] in both directions. */
internal fun SemanticsNodeInteraction.assertTouchSizeIsAtLeast(minimum: Dp): SemanticsNodeInteraction {
    val node = fetchSemanticsNode("Failed to check the touch size.")
    val bounds: Rect = node.touchBoundsInRoot
    val (width, height) = with(node.layoutInfo.density) { bounds.width.toDp() to bounds.height.toDp() }
    if (width < minimum - TOUCH_SIZE_TOLERANCE || height < minimum - TOUCH_SIZE_TOLERANCE) {
        throw AssertionError("Touch size is $width x $height, expected at least $minimum")
    }
    return this
}
