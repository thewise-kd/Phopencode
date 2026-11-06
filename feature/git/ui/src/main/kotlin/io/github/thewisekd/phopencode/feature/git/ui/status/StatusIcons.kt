package io.github.thewisekd.phopencode.feature.git.ui.status

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The Material icons the status screen draws. This module does not depend on the icon library, so
 * the paths of the 24 dp Material symbols are kept here, as in the files feature's tree.
 */
internal object StatusIcons {
    val Stage: ImageVector = icon("Stage", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")

    val Unstage: ImageVector = icon("Unstage", "M19,13H5v-2h14v2z")

    val Resolve: ImageVector = icon("Resolve", "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z")

    val Refresh: ImageVector = icon(
        "Refresh",
        "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 " +
            "7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 " +
            "4.22,1.78L13,11h7V4l-2.35,2.35z",
    )

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = "GitStatus.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black)).build()
}
