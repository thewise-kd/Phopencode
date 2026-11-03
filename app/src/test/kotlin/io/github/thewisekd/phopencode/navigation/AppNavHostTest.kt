package io.github.thewisekd.phopencode.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import io.github.thewisekd.phopencode.StopKoinRule
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.navigation.Route
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The levels of `MainActivityTest`: level 36 has no arm64 Linux runtime, so the Pi passes 26 only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class AppNavHostTest {
    @get:Rule(order = 0)
    val koin = StopKoinRule()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private lateinit var navController: NavHostController

    private fun setNavHost() {
        compose.setContent {
            PhopencodeTheme {
                navController = rememberNavController()
                AppNavHost(navController = navController)
            }
        }
    }

    private fun concreteClasses(type: Class<*>): Set<Class<*>> =
        if (type.isSealed) type.permittedSubclasses.orEmpty().flatMap(::concreteClasses).toSet() else setOf(type)

    @Test
    fun `every route has a sample`() {
        assertEquals(concreteClasses(Route::class.java), allRoutes.map { it.javaClass }.toSet())
    }

    @Test
    fun `placeholder tags tell every route apart`() {
        assertEquals(allRoutes.size, allRoutes.map(::placeholderTag).toSet().size)
    }

    @Test
    fun `the start is the server list`() {
        setNavHost()

        compose.onNodeWithTag(placeholderTag(ConnectRoute.ServerList)).assertIsDisplayed()
        val destination = assertNotNull(navController.currentDestination)
        assertTrue(destination.hasRoute(ConnectRoute.ServerList::class))
    }

    @Test
    fun `every route opens its placeholder`() {
        setNavHost()

        allRoutes.forEach { route ->
            compose.runOnIdle { navController.navigate(route) }

            compose.onNodeWithTag(placeholderTag(route)).assertIsDisplayed()
            val destination = assertNotNull(navController.currentDestination)
            assertTrue(destination.hasRoute(route::class), "$route opened ${destination.route}")
        }
    }
}
