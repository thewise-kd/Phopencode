package io.github.thewisekd.phopencode.feature.files.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.maxBitmapSize
import coil3.size.Size
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerContent
import io.github.thewisekd.phopencode.feature.files.ui.R

/**
 * The longest side, in pixels, an image is decoded to. A larger image is sampled down while it
 * decodes, so the bitmap stays within 2048 x 2048 x 4 bytes = 16 MiB however large the file claims
 * to be. It is about the size of a phone screen's longer side, so the preview loses little.
 */
internal const val MAX_IMAGE_SIDE_PX = 2_048

/** The request for [image]'s preview: its bytes, decoded no larger than [MAX_IMAGE_SIDE_PX] on a side. */
internal fun previewRequest(context: PlatformContext, image: ViewerContent.Image): ImageRequest =
    ImageRequest.Builder(context)
        .data(image.bytes)
        .maxBitmapSize(Size(MAX_IMAGE_SIDE_PX, MAX_IMAGE_SIDE_PX))
        .build()

/**
 * [image] fitted into the space it is given, described by the file's [name]. Nothing animates:
 * a GIF shows its first frame, as the module has no animated decoder. An image the system cannot
 * decode says so instead.
 *
 * [onDecoded] gets the decoded bitmap's width and height once it is shown; tests use it to see the
 * success path where the pixels cannot be read back.
 */
@Composable
internal fun ImagePreview(
    image: ViewerContent.Image,
    name: String,
    modifier: Modifier = Modifier,
    onDecoded: (width: Int, height: Int) -> Unit = { _, _ -> },
) {
    val context = LocalPlatformContext.current
    val request = remember(context, image) { previewRequest(context, image) }
    var failed by remember(image) { mutableStateOf(false) }
    val description = stringResource(R.string.files_viewer_image_description, name)
    // One node says what the preview is, whether the image decoded or not.
    Box(
        modifier
            .testTag(ViewerTags.IMAGE)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Image
            },
        contentAlignment = Alignment.Center,
    ) {
        if (failed) {
            EmptyState(
                modifier = Modifier.fillMaxSize().testTag(ViewerTags.IMAGE_FAILED),
                title = stringResource(R.string.files_viewer_image_failed),
            )
        } else {
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(PhopencodeTheme.spacing.lg)
                    .testTag(ViewerTags.IMAGE_PICTURE),
                contentScale = ContentScale.Fit,
                onSuccess = { success -> onDecoded(success.result.image.width, success.result.image.height) },
                onError = { failed = true },
            )
        }
    }
}
