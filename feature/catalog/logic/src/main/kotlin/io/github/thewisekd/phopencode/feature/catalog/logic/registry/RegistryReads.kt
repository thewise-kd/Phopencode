package io.github.thewisekd.phopencode.feature.catalog.logic.registry

import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogList
import kotlin.coroutines.cancellation.CancellationException

/** How a read through the registry ended: its value, or why there is none. Carries no server text. */
internal sealed interface Outcome<out T> {
    data class Read<T>(val value: T) : Outcome<T>

    data class Failed(val failure: CatalogFailure) : Outcome<Nothing>
}

/**
 * Runs [block] and turns its failure into a [CatalogFailure], dropping the server's words. Anything
 * that is not an `ApiException` is [CatalogFailure.Unknown], so a broken answer never ends the
 * screen's scope; cancellation passes through.
 */
internal suspend fun <T> attempt(block: suspend () -> T): Outcome<T> = try {
    Outcome.Read(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: ApiException) {
    Outcome.Failed(CatalogFailure.of(failure.error))
} catch (failure: Exception) {
    Outcome.Failed(CatalogFailure.Unknown)
}

/** Read again: the items stay on screen, the last error goes. */
internal fun <T> CatalogList<T>.reading(): CatalogList<T> = copy(loading = true, error = null)

/** The read ended: its items, or its failure beside the items read before. */
internal fun <T, R> CatalogList<T>.settled(outcome: Outcome<R>, items: (R) -> List<T>): CatalogList<T> =
    when (outcome) {
        is Outcome.Read -> CatalogList(loading = false, items = items(outcome.value))
        is Outcome.Failed -> copy(loading = false, error = outcome.failure)
    }
