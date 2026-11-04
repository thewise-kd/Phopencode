package io.github.thewisekd.phopencode.feature.connect.logic.contract

import io.github.thewisekd.phopencode.core.model.ServerProfileId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The saved server the connection was last started for. `ConnectionManager` knows the profile it
 * connects to but does not tell, and the folder picker needs it for that server's recent folders.
 * The connection flow sets it before it connects; the ID alone says nothing about whether the
 * connection stands, so a reader checks `ConnectionManager.state` too. One instance per app.
 */
class ActiveServer {
    private val current = MutableStateFlow<ServerProfileId?>(null)

    /** Null until the first connect, and after [set] with null. */
    val id: StateFlow<ServerProfileId?> = current.asStateFlow()

    fun set(id: ServerProfileId?) {
        current.value = id
    }
}
