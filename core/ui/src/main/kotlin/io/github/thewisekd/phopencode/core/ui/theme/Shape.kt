package io.github.thewisekd.phopencode.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Generous rounding, so cards, fields and sheets read as soft, separate objects.
 * Material picks the step per component: chips use [Shapes.small], cards
 * [Shapes.medium], sheets and dialogs [Shapes.large] and [Shapes.extraLarge].
 */
internal val PhopencodeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
