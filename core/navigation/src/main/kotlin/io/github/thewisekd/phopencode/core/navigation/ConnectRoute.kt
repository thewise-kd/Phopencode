package io.github.thewisekd.phopencode.core.navigation

import kotlinx.serialization.Serializable

sealed interface ConnectRoute : Route {
    /** The start of the app. */
    @Serializable
    data object ServerList : ConnectRoute

    /** @property serverId The server to edit, or null for a new one. */
    @Serializable
    data class ServerForm(val serverId: String? = null) : ConnectRoute

    @Serializable
    data object Keys : ConnectRoute

    /** Reads the key to approve from the connection in progress; the route does not carry it. */
    @Serializable
    data class HostKey(val serverId: String) : ConnectRoute

    /** Browses the server of the active connection. */
    @Serializable
    data object FolderPicker : ConnectRoute

    @Serializable
    data class Progress(val serverId: String) : ConnectRoute
}
