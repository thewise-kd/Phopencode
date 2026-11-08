package io.github.thewisekd.phopencode.core.ui.icon

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The app's shared icons, all 24 dp, so the screens speak one visual language: rounded
 * glyphs for actions, outlined ones for things. Each is built on first use and then
 * reused, so a screen pays only for the icons it draws.
 *
 * They come from the Material icon library core:ui already depends on (Material Icons
 * and Material Symbols shapes, Apache License 2.0); [Git], which that library lacks,
 * is drawn here. An icon only decorates when a text next to it says the same thing;
 * otherwise its caller gives it a content description.
 */
object PhopencodeIcons {
    /** Send a prompt: an arrow pointing up. */
    val Send: ImageVector get() = Icons.Rounded.ArrowUpward

    /** Stop the running work: a filled square. */
    val Stop: ImageVector get() = Icons.Rounded.Stop

    val Add: ImageVector get() = Icons.Rounded.Add

    val Folder: ImageVector get() = Icons.Outlined.Folder

    val FolderOpen: ImageVector get() = Icons.Outlined.FolderOpen

    /** A git branch: two commits on a line and a branch forking off it. */
    val Git: ImageVector by lazy { buildGit() }

    val Terminal: ImageVector get() = Icons.Rounded.Terminal

    val Chat: ImageVector get() = Icons.Outlined.ChatBubbleOutline

    /** A server rack, for a configured opencode server. */
    val Server: ImageVector get() = Icons.Outlined.Dns

    val Key: ImageVector get() = Icons.Outlined.Key

    val Settings: ImageVector get() = Icons.Outlined.Settings

    val MoreVert: ImageVector get() = Icons.Rounded.MoreVert

    val Refresh: ImageVector get() = Icons.Rounded.Refresh

    /** Sparkles, for skills. */
    val Sparkle: ImageVector get() = Icons.Outlined.AutoAwesome

    /** A small robot, for agents and subagents. */
    val Agent: ImageVector get() = Icons.Outlined.SmartToy

    val Attach: ImageVector get() = Icons.Rounded.AttachFile

    /** A page of text, for a file. */
    val File: ImageVector get() = Icons.Outlined.Description

    val Check: ImageVector get() = Icons.Rounded.Check

    val Close: ImageVector get() = Icons.Rounded.Close

    /** Points forward: it mirrors in a right-to-left layout. */
    val ChevronRight: ImageVector get() = Icons.AutoMirrored.Rounded.KeyboardArrowRight

    val Edit: ImageVector get() = Icons.Outlined.Edit

    val History: ImageVector get() = Icons.Rounded.History

    val Search: ImageVector get() = Icons.Rounded.Search

    val Warning: ImageVector get() = Icons.Rounded.WarningAmber

    val Info: ImageVector get() = Icons.Outlined.Info

    /** Back to the previous screen: it mirrors in a right-to-left layout. */
    val Back: ImageVector get() = Icons.AutoMirrored.Rounded.ArrowBack
}

private const val STROKE = 2f
private const val RING = 2.25f

/**
 * Drawn for this app as strokes on the 24 unit grid, in the weight of the Material
 * outlined set: a trunk with a commit at each end, and a branch that curves in from a
 * third commit at the top right.
 */
private fun buildGit(): ImageVector = ImageVector.Builder(
    name = "Phopencode.Git",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    fun ring(cx: Float, cy: Float) = path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = STROKE,
    ) {
        moveTo(cx - RING, cy)
        arcToRelative(RING, RING, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = RING * 2, dy1 = 0f)
        arcToRelative(RING, RING, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = -RING * 2, dy1 = 0f)
        close()
    }
    ring(7f, 5.5f)
    ring(7f, 18.5f)
    ring(17f, 6.5f)
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = STROKE,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        // The trunk, between the two commits on the left.
        moveTo(7f, 7.75f)
        verticalLineTo(16.25f)
        // The branch, from the top right commit down into the trunk.
        moveTo(17f, 8.75f)
        verticalLineTo(9.5f)
        curveTo(17f, 12.5f, 14.5f, 13.5f, 11f, 13.75f)
        curveTo(8.5f, 13.9f, 7f, 14.75f, 7f, 16.25f)
    }
}.build()
