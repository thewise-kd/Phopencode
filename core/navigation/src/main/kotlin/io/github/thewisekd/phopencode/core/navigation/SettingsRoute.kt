package io.github.thewisekd.phopencode.core.navigation

import kotlinx.serialization.Serializable

sealed interface SettingsRoute : Route {
    @Serializable
    data object Preferences : SettingsRoute

    @Serializable
    data object Licenses : SettingsRoute
}
