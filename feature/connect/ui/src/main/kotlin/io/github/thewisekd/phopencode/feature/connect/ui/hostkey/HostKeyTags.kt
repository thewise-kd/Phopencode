package io.github.thewisekd.phopencode.feature.connect.ui.hostkey

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsPropertyKey

/** The red box that warns about a changed key. */
internal const val HOSTKEY_WARNING_TAG = "hostkey:warning"

/** The fingerprint of the key the server presents now. */
internal const val HOSTKEY_NEW_FINGERPRINT_TAG = "hostkey:fingerprint:new"

/** The fingerprint the user approved before, shown for a changed key. */
internal const val HOSTKEY_APPROVED_FINGERPRINT_TAG = "hostkey:fingerprint:approved"

/**
 * The colours the changed-key warning is drawn in, exposed so a test can check they come from the
 * theme's error roles without reading pixels.
 */
internal val WarningContainerColor = SemanticsPropertyKey<Color>("HostKeyWarningContainerColor")
internal val WarningContentColor = SemanticsPropertyKey<Color>("HostKeyWarningContentColor")
