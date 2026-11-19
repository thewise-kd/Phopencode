package io.github.thewisekd.phopencode.feature.chat.ui.timeline

import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.thewisekd.phopencode.feature.chat.ui.R
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * Up to this level (Android 12L) the system says nothing when an app writes to the clipboard, so
 * the chat shows a short "Copied" notice; from Android 13 on the system shows its own confirmation,
 * and a second notice would only repeat it.
 */
private const val LAST_LEVEL_WITHOUT_SYSTEM_NOTICE = Build.VERSION_CODES.S_V2

/**
 * What puts a text on the clipboard as plain text, through [LocalClipboard] as `ConnectGraph` does
 * for the public key, then shows the short "Copied" notice where the system shows none.
 *
 * The write starts at once, in the caller's frame, so a caller that closes itself right after (a
 * prompt's actions) cannot cancel it. The text is user data: it goes to the clipboard only, never
 * to a log or anywhere else.
 */
@Composable
internal fun rememberCopyText(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notice = stringResource(R.string.chat_timeline_copied)
    return remember(clipboard, context, scope, notice) {
        { text ->
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text)))
                if (Build.VERSION.SDK_INT <= LAST_LEVEL_WITHOUT_SYSTEM_NOTICE) {
                    Toast.makeText(context.applicationContext, notice, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
