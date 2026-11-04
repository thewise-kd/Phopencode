package io.github.thewisekd.phopencode.feature.connect.ui.keys

import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysIntent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FALLBACK = "Fallback name"

/** The seam between a picked file and the [KeysIntent.Import] intent, without the system picker. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class KeyFileReaderTest {
    private val source = FakeKeyFileSource()
    private val intents: MutableList<KeysIntent> = Collections.synchronizedList(mutableListOf())
    private val allocator = TrackingAllocator()

    private fun reader(maxBytes: Int = MAX_KEY_FILE_BYTES) =
        KeyFileReader(source, maxBytes, Dispatchers.IO, allocator::allocate)

    private fun import(maxBytes: Int = MAX_KEY_FILE_BYTES): KeyFileError? = runBlocking {
        reader(maxBytes).import(PICKED_URI, FALLBACK) { intents += it }
    }

    /** Runs an import on its own caller thread, the way the screen's scope would, and waits for it. */
    private fun importCancelledBy(cancelOn: (cancel: () -> Unit) -> Unit): Job {
        val caller = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        return caller.use {
            runBlocking {
                lateinit var job: Job
                cancelOn { job.cancel() }
                job = launch(caller, start = CoroutineStart.LAZY) {
                    reader().import(PICKED_URI, FALLBACK) { intents += it }
                }
                job.start()
                job.join()
                job
            }
        }
    }

    @Test
    fun `a picked file becomes one Import intent with its name and bytes`() {
        source.content = "not a real key".toByteArray()
        source.name = "id_test"

        assertNull(import())

        val intent = assertIs<KeysIntent.Import>(intents.single())
        assertEquals("id_test", intent.name)
        assertContentEquals("not a real key".toByteArray(), intent.content)
        assertEquals(listOf(PICKED_URI), source.opened)
        assertTrue(source.closed)
    }

    @Test
    fun `a file exactly at the limit is read`() {
        source.content = ByteArray(16) { 'a'.code.toByte() }

        assertNull(import(maxBytes = 16))

        assertEquals(16, assertIs<KeysIntent.Import>(intents.single()).content.size)
    }

    @Test
    fun `a file over the limit is refused without an intent`() {
        source.content = ByteArray(17) { 'a'.code.toByte() }

        assertEquals(KeyFileError.TooLarge, import(maxBytes = 16))

        assertTrue(intents.isEmpty())
        assertTrue(source.closed)
    }

    @Test
    fun `the default limit is 64 KiB`() {
        assertEquals(64 * 1024, MAX_KEY_FILE_BYTES)
        source.content = ByteArray(MAX_KEY_FILE_BYTES + 1)

        assertEquals(KeyFileError.TooLarge, import())
        assertTrue(intents.isEmpty())
    }

    @Test
    fun `an empty file is refused`() {
        source.content = ByteArray(0)

        assertEquals(KeyFileError.Empty, import())
        assertTrue(intents.isEmpty())
    }

    @Test
    fun `a file that cannot be opened is unreadable`() {
        source.content = null

        assertEquals(KeyFileError.Unreadable, import())
        assertTrue(intents.isEmpty())
    }

    @Test
    fun `a missing file, an IO error and a refused permission are unreadable`() {
        for (failure in listOf(FileNotFoundException("gone"), IOException("broken"), SecurityException("denied"))) {
            source.failure = failure

            assertEquals(KeyFileError.Unreadable, import(), failure.javaClass.simpleName)
        }
        assertTrue(intents.isEmpty())
    }

    @Test
    fun `a screen that leaves during the read sends nothing and wipes what was read`() {
        source.content = "fake key bytes".toByteArray()

        val job = importCancelledBy { cancel -> source.onOpen = { cancel() } }

        assertTrue(job.isCancelled)
        assertTrue(intents.isEmpty())
        assertTrue(source.closed)
        assertTrue(allocator.count > 0)
        assertTrue(allocator.allWipedExcept())
    }

    @Test
    fun `a cancel after the read finished but before its result is delivered wipes the bytes and sends nothing`() {
        source.content = "fake key bytes".toByteArray()
        val caller = ManualDispatcher()
        // The read waits until the caller has suspended, so its result must be dispatched back.
        val callerSuspended = CountDownLatch(1)
        source.onOpen = { callerSuspended.await(5, TimeUnit.SECONDS) }
        val job = CoroutineScope(caller).launch { reader().import(PICKED_URI, FALLBACK) { intents += it } }

        caller.runNext() // Starts the import, which hands the read to the IO dispatcher and suspends.
        callerSuspended.countDown()
        val delivery = caller.next() // The read is done and its result waits to be delivered here.
        job.cancel()
        delivery.run()
        caller.runAll()

        assertTrue(job.isCancelled)
        assertTrue(intents.isEmpty())
        assertEquals(2, allocator.count, "the read buffer and the content")
        assertTrue(allocator.allWipedExcept())
    }

    @Test
    fun `cancelling closes a stream stuck in read, so the read returns, nothing is sent and every buffer is zeroed`() {
        val stream = StuckInputStream("fake key bytes".toByteArray())
        source.stuck = stream
        val opened = CountDownLatch(1)
        source.onOpen = { opened.countDown() }

        runBlocking {
            val job = launch(Dispatchers.Default) { reader().import(PICKED_URI, FALLBACK) { intents += it } }
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            job.cancel()
            // Far less than the stream's own give-up time: only closing it can end the read this soon.
            withTimeout(5_000) { job.join() }
        }

        assertTrue(stream.isClosed)
        assertTrue(stream.readReturned)
        assertTrue(intents.isEmpty())
        assertTrue(allocator.count > 0)
        assertTrue(allocator.allWipedExcept())
    }

    @Test
    fun `a stream that fails to close after a normal read still imports`() {
        source.content = "fake key bytes".toByteArray()
        source.closeFails = true

        assertNull(import())

        val sent = assertIs<KeysIntent.Import>(intents.single()).content
        assertContentEquals("fake key bytes".toByteArray(), sent)
        assertTrue(source.closed)
        assertTrue(allocator.allWipedExcept(sent))
    }

    @Test
    fun `a stuck stream that fails to close on cancel sends nothing, crashes nothing and leaves no bytes`() {
        val stream = StuckInputStream("fake key bytes".toByteArray(), closeFails = true)
        source.stuck = stream
        val opened = CountDownLatch(1)
        source.onOpen = { opened.countDown() }
        val failures: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
        val scope = CoroutineScope(Dispatchers.Default + CoroutineExceptionHandler { _, error -> failures += error })

        val job = scope.launch { reader().import(PICKED_URI, FALLBACK) { intents += it } }
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        job.cancel()
        runBlocking { withTimeout(5_000) { job.join() } }

        assertTrue(job.isCancelled)
        assertEquals(emptyList(), failures.toList())
        assertTrue(stream.isClosed)
        assertTrue(stream.readReturned)
        assertTrue(intents.isEmpty())
        assertTrue(allocator.count > 0)
        assertTrue(allocator.allWipedExcept())
    }

    @Test
    fun `the read buffer is wiped and only the sent content stays`() {
        source.content = "fake key bytes".toByteArray()

        assertNull(import())

        val sent = assertIs<KeysIntent.Import>(intents.single()).content
        assertContentEquals("fake key bytes".toByteArray(), sent)
        assertTrue(allocator.allWipedExcept(sent))
    }

    @Test
    fun `a refused file leaves no bytes behind`() {
        for (content in listOf(ByteArray(17) { 'a'.code.toByte() }, ByteArray(0))) {
            source.content = content

            import(maxBytes = 16)
        }

        assertTrue(allocator.count > 0)
        assertTrue(allocator.allWipedExcept())
    }

    @Test
    fun `the file is read off the main thread`() {
        source.content = "x".toByteArray()

        import()

        assertFalse(checkNotNull(source.openedOnMainThread))
    }

    @Test
    fun `without a display name the fallback name is used`() {
        source.content = "x".toByteArray()
        source.name = null

        import()

        assertEquals(FALLBACK, assertIs<KeysIntent.Import>(intents.single()).name)
    }

    @Test
    fun `a blank display name uses the fallback name`() {
        source.content = "x".toByteArray()
        source.name = "  \n "

        import()

        assertEquals(FALLBACK, assertIs<KeysIntent.Import>(intents.single()).name)
    }

    @Test
    fun `control characters are dropped from the name and it is cut to a sane length`() {
        source.content = "x".toByteArray()
        source.name = " id\n_test\u0000 " + "z".repeat(200)

        import()

        val name = assertIs<KeysIntent.Import>(intents.single()).name
        assertTrue(name.startsWith("id_test"), name)
        assertTrue(name.none { it.isISOControl() }, name)
        assertEquals(MAX_KEY_NAME_LENGTH, name.length)
    }
}

/** A caller thread the test drives by hand, so it can cancel between the read and its delivery. */
private class ManualDispatcher : CoroutineDispatcher() {
    private val tasks = LinkedBlockingQueue<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks.put(block)
    }

    fun next(): Runnable = checkNotNull(tasks.poll(5, TimeUnit.SECONDS)) { "Nothing was dispatched" }

    fun runNext() = next().run()

    fun runAll() {
        while (true) (tasks.poll() ?: return).run()
    }
}
