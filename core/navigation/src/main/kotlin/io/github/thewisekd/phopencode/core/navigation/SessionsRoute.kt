package io.github.thewisekd.phopencode.core.navigation

import kotlinx.serialization.Serializable

sealed interface SessionsRoute : Route {
    /** @property directory Limits the list to one folder; null lists all. */
    @Serializable
    data class SessionList(val directory: String? = null) : SessionsRoute

    /**
     * @property directory The folder to preselect.
     * @property skillId A skill to attach to the first prompt.
     */
    @Serializable
    data class Create(val directory: String? = null, val skillId: String? = null) : SessionsRoute

    /** Delete, rename and archive. */
    @Serializable
    data class Manage(val sessionId: String) : SessionsRoute
}
