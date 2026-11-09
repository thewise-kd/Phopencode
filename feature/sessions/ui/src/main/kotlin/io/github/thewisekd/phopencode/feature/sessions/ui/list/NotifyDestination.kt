package io.github.thewisekd.phopencode.feature.sessions.ui.list

/**
 * The notification screens the session list's top bar menu opens, below the catalog's screens. The
 * screen names where to go; the sessions graph turns it into a route, so the screen never sees the
 * navigation controller.
 */
enum class NotifyDestination {
    /** How notifications reach this phone: ntfy, its subscription and the service's status. */
    Setup,

    /** The questions and permissions that wait for an answer, from every session. */
    Pending,
}
