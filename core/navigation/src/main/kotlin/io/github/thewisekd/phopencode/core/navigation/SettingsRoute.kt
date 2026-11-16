package io.github.thewisekd.phopencode.core.navigation

import kotlinx.serialization.Serializable

sealed interface SettingsRoute : Route {
    @Serializable
    data object Preferences : SettingsRoute

    @Serializable
    data object Licenses : SettingsRoute

    /**
     * The usage guide (P14.1): opens by itself above the server list on the first launch, and from
     * the preferences at any time. Leaving it goes back to the screen below.
     */
    @Serializable
    data object Guide : SettingsRoute
}
