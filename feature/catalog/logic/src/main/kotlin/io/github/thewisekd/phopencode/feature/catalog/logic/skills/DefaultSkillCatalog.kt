package io.github.thewisekd.phopencode.feature.catalog.logic.skills

import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.CatalogApi
import io.github.thewisekd.phopencode.core.model.Skill
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogConnection
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillCatalog
import kotlinx.coroutines.CompletableDeferred

/**
 * The skills of the active connection, kept per connection generation as [SkillCatalog] describes.
 * `CatalogModule` makes one for the app; it takes no scope, so it never captures a screen's.
 *
 * - One list is kept, with the generation it was read on. A call on another generation, or one that
 *   asks for [refresh][skills], reads the server again; a failed read keeps nothing and the kept list
 *   stays as it was.
 * - While a read for the caller's generation is on its way, every caller of that generation joins
 *   it, refresh or not, instead of asking again or taking the kept list: its answer, or its failure,
 *   is theirs too. Only when no read runs does a caller without [refresh][skills] get the kept list.
 * - A read runs in the coroutine of the caller that started it. If that caller is cancelled, the
 *   read is dropped and those waiting on it start a read of their own.
 * - Generations only move forward: a caller still holding an older connection than the newest one
 *   seen reads for itself, without sharing, replacing or keeping anything, and a read that ends
 *   after a newer generation was seen keeps nothing.
 *
 * Nothing here logs: a skill's text and path are the user's.
 *
 * @param connection The catalog of the active connection.
 */
class DefaultSkillCatalog(private val connection: CatalogConnection) : SkillCatalog {
    private class Kept(val generation: Long, val skills: List<Skill>)

    private class Read(val generation: Long) {
        /** The list or the [ApiException]; null when the reader was cut short and waiters must retry. */
        val outcome = CompletableDeferred<Result<List<Skill>>?>()
    }

    /** What a caller does, decided under [lock]. */
    private sealed interface Step {
        class Return(val skills: List<Skill>) : Step

        /** On an older generation than the newest seen: read, and keep nothing. */
        data object ReadAlone : Step

        class Start(val read: Read) : Step

        class Wait(val read: Read) : Step
    }

    private val lock = Any()

    // Guarded by [lock].
    private var kept: Kept? = null
    private var reading: Read? = null
    private var newest = Long.MIN_VALUE

    override suspend fun skills(refresh: Boolean): List<Skill> =
        connection.call { catalog, generation -> skillsOf(catalog, generation, refresh) }

    private suspend fun skillsOf(catalog: CatalogApi, generation: Long, refresh: Boolean): List<Skill> {
        while (true) {
            when (val step = synchronized(lock) { step(generation, refresh) }) {
                is Step.Return -> return step.skills
                Step.ReadAlone -> return catalog.skills()
                is Step.Start -> return readInto(step.read, catalog)
                is Step.Wait -> step.read.outcome.await()?.let { return it.getOrThrow() }
            }
        }
    }

    /** Called holding [lock]. */
    private fun step(generation: Long, refresh: Boolean): Step {
        if (generation < newest) return Step.ReadAlone
        newest = generation
        // A read on its way is newer than anything kept: every caller of its generation joins it.
        reading?.takeIf { it.generation == generation }?.let { return Step.Wait(it) }
        val current = kept
        if (!refresh && current != null && current.generation == generation) return Step.Return(current.skills)
        // Any read still registered is of an older generation, which nobody may share any more.
        return Step.Start(Read(generation).also { reading = it })
    }

    private suspend fun readInto(read: Read, catalog: CatalogApi): List<Skill> {
        var outcome: Result<List<Skill>>? = null
        try {
            outcome = try {
                Result.success(catalog.skills())
            } catch (e: ApiException) {
                Result.failure(e)
            }
            outcome.onSuccess { skills ->
                synchronized(lock) { if (read.generation == newest) kept = Kept(read.generation, skills) }
            }
        } finally {
            synchronized(lock) { if (reading === read) reading = null }
            read.outcome.complete(outcome)
        }
        return checkNotNull(outcome).getOrThrow()
    }

    /** Keeps skills' text and paths out of logs. */
    override fun toString(): String = "DefaultSkillCatalog"
}
