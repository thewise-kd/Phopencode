package io.github.thewisekd.phopencode.feature.preview.logic.console

import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleEntry
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleLevel
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleState

/*
 * The console's pure steps (P13.12). Each returns a new state and changes nothing else; the page
 * logic keeps the result in its own state, in memory only. The error count for the badge is
 * [ConsoleState.errorCount] and counts every entry, whatever the filter.
 */

/**
 * This state with [entry] added last, its text and source cut at [ConsoleState.MAX_TEXT_LENGTH]
 * characters; when more than [ConsoleState.MAX_ENTRIES] entries would be kept, the oldest are
 * dropped first. The filter stays.
 */
fun ConsoleState.recorded(entry: ConsoleEntry): ConsoleState {
    val kept = ConsoleEntries.bounded(entry)
    val drop = (entries.size + 1 - ConsoleState.MAX_ENTRIES).coerceAtLeast(0)
    val next = ArrayList<ConsoleEntry>(minOf(entries.size + 1, ConsoleState.MAX_ENTRIES))
    for (index in drop until entries.size) next.add(entries[index])
    next.add(kept)
    return copy(entries = next)
}

/** This state showing only [level]; null shows every level. The entries stay. */
fun ConsoleState.filteredBy(level: ConsoleLevel?): ConsoleState = copy(filter = level)

/** This state with every entry dropped; the filter stays. */
fun ConsoleState.cleared(): ConsoleState = copy(entries = emptyList())
