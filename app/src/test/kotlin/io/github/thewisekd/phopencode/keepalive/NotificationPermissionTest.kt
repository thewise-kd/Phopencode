package io.github.thewisekd.phopencode.keepalive

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Plain JVM: whether to ask for the notification permission, for every case. */
class NotificationPermissionTest {
    private val levels = listOf(26, 30, 32, 33, 34, 36, 37)
    private val bothWays = listOf(false, true)

    @Test
    fun `it asks on API 33 and later when a connection is active, the permission is missing and it never asked`() {
        levels.filter { it >= 33 }.forEach { sdk ->
            ACTIVE_STATES.forEach { state ->
                assertTrue(asksForNotifications(sdk, granted = false, askedBefore = false, state = state), "$sdk $state")
            }
        }
    }

    @Test
    fun `it never asks below API 33`() {
        levels.filter { it < 33 }.forEach { sdk ->
            (ACTIVE_STATES + ENDED_STATES).forEach { state ->
                bothWays.forEach { granted ->
                    bothWays.forEach { askedBefore ->
                        assertFalse(asksForNotifications(sdk, granted, askedBefore, state), "$sdk $state $granted $askedBefore")
                    }
                }
            }
        }
    }

    @Test
    fun `it never asks when the permission is granted`() {
        levels.forEach { sdk ->
            (ACTIVE_STATES + ENDED_STATES).forEach { state ->
                bothWays.forEach { askedBefore ->
                    assertFalse(asksForNotifications(sdk, granted = true, askedBefore = askedBefore, state = state), "$sdk $state")
                }
            }
        }
    }

    @Test
    fun `it never asks a second time`() {
        levels.forEach { sdk ->
            (ACTIVE_STATES + ENDED_STATES).forEach { state ->
                assertFalse(asksForNotifications(sdk, granted = false, askedBefore = true, state = state), "$sdk $state")
            }
        }
    }

    @Test
    fun `it never asks while no connection is active`() {
        levels.forEach { sdk ->
            ENDED_STATES.forEach { state ->
                assertFalse(asksForNotifications(sdk, granted = false, askedBefore = false, state = state), "$sdk $state")
            }
        }
    }
}
