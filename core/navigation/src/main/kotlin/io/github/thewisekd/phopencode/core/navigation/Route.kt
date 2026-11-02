package io.github.thewisekd.phopencode.core.navigation

/**
 * A screen of the app, as a navigation-compose type-safe route. Each feature has its own sealed
 * group, so that its graph sees its own routes. All routes are defined here up front: no later
 * task owns this module.
 *
 * Rules for route arguments, which `RouteTest` enforces where it can:
 * - IDs and paths only, as plain strings. Never a secret or file contents; a route ends up in the
 *   back stack, in saved state and possibly in logs.
 * - Only types navigation handles without a custom `NavType`, so no value classes from `core:model`.
 * - A required string must not be empty: its value is a URL path segment, and an empty segment
 *   matches no destination.
 * - A nullable argument has a default, which makes it a query argument. A nullable string must not
 *   hold a free-form name, because navigation reads the text `null` in it as null.
 *
 * Routes past the connect step carry no server ID: they work on the active connection.
 *
 * Arguments are percent-encoded by navigation, so paths with `/`, spaces or Turkish letters are
 * safe. A `directory` is the absolute path of the project's root. A `path` is either absolute or
 * relative to that root, as `core:files` or `core:git` gave it.
 */
sealed interface Route
