package io.github.thewisekd.phopencode.feature.preview.ui.page

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ExternalLink

/**
 * Hands an address off the preview to another app. The page screen calls it only when the user
 * confirms the dialog that shows the address in full; the WebView never loads it, and the app
 * fetches nothing itself.
 */
internal object ExternalLinks {
    private val SCHEMES = setOf("http", "https")

    /**
     * Opens [link] in an app that takes browsable `http(s)` addresses. False when [link] is not one,
     * or nothing on the phone can open it. The address is the page's: it is never logged.
     */
    fun open(context: Context, link: ExternalLink): Boolean {
        val uri = Uri.parse(link.url)
        if (uri.scheme?.lowercase() !in SCHEMES || uri.host.isNullOrEmpty()) return false
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
