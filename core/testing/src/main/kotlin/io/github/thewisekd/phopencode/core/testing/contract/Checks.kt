package io.github.thewisekd.phopencode.core.testing.contract

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

// The checks contracts use. kotlin.test is not on this module's main classpath, and a contract must
// not make it a dependency of every module that reuses one.

internal fun contractFailure(message: String): Nothing = throw AssertionError(message)

internal fun expectThat(condition: Boolean, what: String) {
    if (!condition) contractFailure(what)
}

internal fun <T> expectEqual(expected: T, actual: T, what: String) {
    if (expected != actual) contractFailure("$what: expected <$expected>, got <$actual>")
}

/** Runs [block] and gives back what it threw, which must be a [T]. */
internal suspend inline fun <reified T : Throwable> expectThrows(what: String, block: () -> Unit): T {
    val thrown = try {
        block()
        null
    } catch (e: Throwable) {
        e
    }
    return thrown as? T ?: contractFailure("$what: expected ${T::class.simpleName}, got <$thrown>")
}

internal suspend inline fun expectApiError(expected: ApiError, what: String, block: () -> Unit) {
    expectEqual(expected, expectThrows<ApiException>(what, block).error, what)
}

internal suspend inline fun expectSftpFailure(expected: SftpFailure, what: String, block: () -> Unit) {
    expectEqual(expected, expectThrows<SshException.Sftp>(what, block).failure, what)
}

/**
 * Waits in real time. An implementation may work on other threads, where the virtual clock of
 * `runTest` would fire a timeout at once.
 */
internal suspend fun <T> withinTime(what: String, block: suspend () -> T): T = withContext(Dispatchers.Default) {
    try {
        withTimeout(WAIT) { block() }
    } catch (_: TimeoutCancellationException) {
        contractFailure("Timed out waiting: $what")
    }
}

private val WAIT = 20.seconds

/** Something a case started and watches, which may fail or be cancelled without failing the case. */
internal class Running<T> internal constructor() {
    private val outcome = CompletableDeferred<Result<T>>()
    internal lateinit var job: Job

    internal fun finish(result: Result<T>) {
        outcome.complete(result)
    }

    val isFinished: Boolean
        get() = outcome.isCompleted

    suspend fun result(what: String): Result<T> = withinTime(what) { outcome.await() }

    /** What it failed with. Fails the case if it ended without failing. */
    suspend fun failure(what: String): Throwable =
        result(what).exceptionOrNull() ?: contractFailure("$what: expected a failure, but it ended normally")

    fun cancel() {
        job.cancel()
    }
}

/** Starts [block] and runs it up to its first suspension before returning. */
internal fun <T> TestScope.start(block: suspend () -> T): Running<T> {
    val running = Running<T>()
    running.job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
        val result = try {
            Result.success(block())
        } catch (e: Throwable) {
            Result.failure(e)
        }
        running.finish(result)
        (result.exceptionOrNull() as? CancellationException)?.let { throw it }
    }
    return running
}

/** A collection of a flow that a case reads one item at a time. */
internal class Collection<T> internal constructor(
    private val items: Channel<T>,
    private val running: Running<Unit>,
) {
    val isFinished: Boolean
        get() = running.isFinished

    suspend fun next(what: String): T = withinTime(what) { items.receive() }

    suspend fun failure(what: String): Throwable = running.failure(what)

    fun cancel() {
        running.cancel()
    }

    /** Waits for the collection to end, however it ends. */
    suspend fun end(what: String) {
        running.result(what)
    }
}

internal fun <T> TestScope.collect(flow: Flow<T>): Collection<T> {
    val items = Channel<T>(Channel.UNLIMITED)
    return Collection(items, start { flow.collect { items.send(it) } })
}
