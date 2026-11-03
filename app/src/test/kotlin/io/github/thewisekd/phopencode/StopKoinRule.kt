package io.github.thewisekd.phopencode

import org.junit.rules.ExternalResource
import org.koin.core.context.stopKoin

/**
 * Stops the Koin that [PhopencodeApplication] starts. Robolectric creates the application for
 * every test but keeps static state, such as Koin's global context, between them, so a test that
 * leaves Koin running makes the next application fail to start it.
 */
class StopKoinRule : ExternalResource() {
    override fun after() {
        stopKoin()
    }
}
