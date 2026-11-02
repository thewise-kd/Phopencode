package io.github.thewisekd.phopencode.core.navigation

import kotlinx.serialization.Serializable

sealed interface NotifyRoute : Route {
    /** Sets up the notification service on the server of the active connection. */
    @Serializable
    data object Setup : NotifyRoute

    /**
     * Forms and permissions waiting for an answer, from all sessions. [DeepLinks.PENDING] opens
     * it, so it takes no arguments. The app may have no connection at that point.
     */
    @Serializable
    data object Pending : NotifyRoute
}
