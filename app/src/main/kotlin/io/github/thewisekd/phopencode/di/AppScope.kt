package io.github.thewisekd.phopencode.di

import kotlinx.coroutines.CoroutineScope

/**
 * The app-lifetime [scope], under a type of its own so that Koin can never mix it up with a
 * screen's scope.
 *
 * Presenters are factories that take their screen's [CoroutineScope] as a Koin parameter, and Koin
 * answers an unqualified `get()` inside a definition from the parameters of the current resolution
 * first. A single created lazily while such a presenter is resolved would therefore capture the
 * screen's scope and end with the screen. Nothing ever passes an [AppScope] as a parameter, so
 * `get<AppScope>().scope` always is the app's.
 */
class AppScope(val scope: CoroutineScope)
