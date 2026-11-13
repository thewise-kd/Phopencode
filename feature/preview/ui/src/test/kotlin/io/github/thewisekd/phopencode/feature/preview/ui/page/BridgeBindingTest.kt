package io.github.thewisekd.phopencode.feature.preview.ui.page

import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewBridgeFactory
import io.github.thewisekd.phopencode.feature.preview.logic.transport.LoopbackBridgeFactory
import io.github.thewisekd.phopencode.feature.preview.ui.previewModule
import org.junit.After
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * The page opens its bridge with P13.10's loopback bridge (D9, option (b)), one factory for the app.
 * Plain JVM: getting the factory opens no socket; each `open` does.
 */
class BridgeBindingTest {
    @After
    fun stop() {
        stopKoin()
    }

    @Test
    fun `the bridge port is the loopback bridge`() {
        val koin = startKoin { modules(previewModule) }.koin

        assertIs<LoopbackBridgeFactory>(koin.get<PreviewBridgeFactory>())
        assertSame(koin.get<PreviewBridgeFactory>(), koin.get<PreviewBridgeFactory>())
    }
}
