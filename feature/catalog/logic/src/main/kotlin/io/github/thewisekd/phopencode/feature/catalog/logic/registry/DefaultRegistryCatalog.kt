package io.github.thewisekd.phopencode.feature.catalog.logic.registry

import io.github.thewisekd.phopencode.core.api.CatalogApi
import io.github.thewisekd.phopencode.core.model.Agent
import io.github.thewisekd.phopencode.core.model.Command
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogConnection
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelCatalog
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryCatalog
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The server's commands, agents and models, each kept apart per connection generation, as
 * [RegistryCatalog] describes. A read finds the generation standing now through
 * [CatalogConnection.call], so without a connection it fails as that does and returns nothing kept.
 * Every caller that asks while a read of the same generation is running waits for that read, refresh
 * or not, instead of starting another or taking a kept list older than it; only when none is running
 * does a caller without refresh get the kept list. A failed read is not kept, and leaves what was
 * kept before.
 * A caller of a connection that a newer one has replaced reads on its own: it neither takes the
 * place of the newer connection's read nor is what it reads kept.
 * `CatalogModule` makes one for the app; it takes no scope, so it never captures a screen's: a read
 * runs in its first caller's coroutine, and if that one is cancelled the callers waiting on it read
 * again on their own.
 *
 * @param connection The catalog of the active connection.
 */
class DefaultRegistryCatalog(private val connection: CatalogConnection) : RegistryCatalog {
    private val commands = Kept<List<Command>> { it.commands() }
    private val agents = Kept<List<Agent>> { it.agents() }
    private val models = Kept { catalog ->
        coroutineScope {
            val models = async { catalog.models() }
            val providers = async { catalog.providers() }
            val defaultModel = async { catalog.defaultModel() }
            ModelCatalog(models = models.await(), providers = providers.await(), defaultModel = defaultModel.await())
        }
    }

    override suspend fun commands(refresh: Boolean): List<Command> = commands.read(refresh)

    override suspend fun agents(refresh: Boolean): List<Agent> = agents.read(refresh)

    override suspend fun models(refresh: Boolean): ModelCatalog = models.read(refresh)

    /** Keeps descriptions and permissions out of logs. */
    override fun toString(): String = "DefaultRegistryCatalog"

    /** One list: the value kept for a generation, and the read running for one. */
    private inner class Kept<T>(private val fetch: suspend (CatalogApi) -> T) {
        private val lock = Any()
        private var kept: Entry<T>? = null
        private var running: Running<T>? = null

        /** The newest generation a caller has asked for; generations only grow. */
        private var newest: Long? = null

        suspend fun read(refresh: Boolean): T =
            connection.call { catalog, generation -> read(catalog, generation, refresh) }

        private suspend fun read(catalog: CatalogApi, generation: Long, refresh: Boolean): T {
            while (true) {
                when (val step = synchronized(lock) { next(generation, refresh) }) {
                    is Step.Kept -> return step.value
                    Step.Alone -> return fetch(catalog)
                    is Step.Own -> return fetchInto(catalog, generation, step.deferred)
                    is Step.Wait -> try {
                        return step.deferred.await()
                    } catch (cancelled: CancellationException) {
                        // Either this caller was cancelled, which rethrows here, or the one reading
                        // was, and this caller decides again.
                        currentCoroutineContext().ensureActive()
                    }
                }
            }
        }

        /**
         * Under [lock]. A read running for [generation] is joined by every caller, refresh or not, so
         * nobody gets a list older than one being read; only with none running is the kept list used.
         */
        private fun next(generation: Long, refresh: Boolean): Step<T> {
            val latest = newest
            if (latest != null && generation < latest) return Step.Alone
            newest = generation
            val current = running
            if (current != null && current.generation == generation) return Step.Wait(current.deferred)
            val value = kept
            if (!refresh && value != null && value.generation == generation) return Step.Kept(value.value)
            val started = Running(generation, CompletableDeferred<T>())
            running = started
            return Step.Own(started.deferred)
        }

        private suspend fun fetchInto(catalog: CatalogApi, generation: Long, deferred: CompletableDeferred<T>): T {
            val value = try {
                fetch(catalog)
            } catch (failure: Throwable) {
                synchronized(lock) { if (running?.deferred === deferred) running = null }
                deferred.completeExceptionally(failure)
                throw failure
            }
            synchronized(lock) {
                val previous = kept
                // Generations only grow: a read that ends after a newer connection's is not kept.
                if (previous == null || previous.generation <= generation) kept = Entry(generation, value)
                if (running?.deferred === deferred) running = null
            }
            deferred.complete(value)
            return value
        }
    }

    /** What a caller does next. */
    private sealed interface Step<out T> {
        class Kept<T>(val value: T) : Step<T>

        /** The caller's connection was replaced: read without sharing or keeping. */
        data object Alone : Step<Nothing>

        class Own<T>(val deferred: CompletableDeferred<T>) : Step<T>

        class Wait<T>(val deferred: CompletableDeferred<T>) : Step<T>
    }

    private class Entry<T>(val generation: Long, val value: T)

    private class Running<T>(val generation: Long, val deferred: CompletableDeferred<T>)
}
