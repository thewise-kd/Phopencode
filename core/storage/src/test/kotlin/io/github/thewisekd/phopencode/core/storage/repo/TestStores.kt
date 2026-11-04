package io.github.thewisekd.phopencode.core.storage.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.thewisekd.phopencode.core.model.ServerProfile
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking

/**
 * Opens DataStores on files in [dir], each on a scope of its own, and closes them all in [close].
 * A DataStore holds its file until its scope ends, so [reopen] waits for that before opening the
 * same file again, as a new process would.
 */
internal class TestStores(private val dir: File) {
    private val scopes = mutableListOf<CoroutineScope>()
    private val counter = AtomicInteger()

    fun newFile(name: String): File = File(dir, "${counter.incrementAndGet()}-$name")

    fun scope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO).also { synchronized(scopes) { scopes += it } }

    fun profiles(file: File = newFile(PROFILES)): Opened<List<ServerProfile>> = open(file) { profileDataStore(it) { file } }

    fun knownHosts(file: File = newFile(KNOWN_HOSTS)): Opened<KnownHostsFile> = open(file) { knownHostsDataStore(it) { file } }

    fun settings(file: File = newFile(SETTINGS)): Opened<Preferences> = open(file) { settingsDataStore(it) { file } }

    private fun <T> open(file: File, create: (CoroutineScope) -> DataStore<T>): Opened<T> {
        val scope = scope()
        return Opened(file, scope, SwitchableDataStore(create(scope)))
    }

    suspend fun <T> reopen(opened: Opened<T>, create: (File) -> Opened<T>): Opened<T> {
        opened.scope.coroutineContext.job.cancelAndJoin()
        return create(opened.file)
    }

    fun close() = runBlocking {
        synchronized(scopes) { scopes.toList() }.forEach { it.coroutineContext.job.cancelAndJoin() }
    }

    class Opened<T>(val file: File, val scope: CoroutineScope, val store: SwitchableDataStore<T>)

    companion object {
        const val PROFILES = "profiles.json"
        const val KNOWN_HOSTS = "known_hosts.json"
        const val SETTINGS = "settings.preferences_pb"
    }
}

/**
 * Passes everything to [delegate], except that writes throw [IOException] while [failing], and the
 * next [readFailures] collections of [data] throw [IOException] before reading. [reads] counts
 * collections of [data].
 */
internal class SwitchableDataStore<T>(private val delegate: DataStore<T>) : DataStore<T> {
    @Volatile
    var failing: Boolean = false

    val readFailures = AtomicInteger()
    val reads = AtomicInteger()

    override val data: Flow<T> = flow {
        reads.incrementAndGet()
        if (readFailures.getAndUpdate { maxOf(it - 1, 0) } > 0) throw IOException("Test storage cannot be read")
        emitAll(delegate.data)
    }

    override suspend fun updateData(transform: suspend (t: T) -> T): T {
        if (failing) throw IOException("Test storage is unavailable")
        return delegate.updateData(transform)
    }
}
