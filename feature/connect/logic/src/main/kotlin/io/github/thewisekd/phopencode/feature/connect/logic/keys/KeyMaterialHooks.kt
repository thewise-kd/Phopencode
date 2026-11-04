package io.github.thewisekd.phopencode.feature.connect.logic.keys

import io.github.thewisekd.phopencode.core.ssh.key.GeneratedKey

/**
 * For tests only: lets a test see the key material the presenter makes, so that it can check the
 * material is wiped on every way out, cancellation included. Never set in the app.
 */
internal object KeyMaterialHooks {
    /** Called on the worker with each newly generated key, before the presenter uses it. */
    @Volatile
    var generated: ((GeneratedKey) -> Unit)? = null
}
