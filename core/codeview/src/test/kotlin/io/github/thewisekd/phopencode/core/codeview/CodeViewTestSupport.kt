package io.github.thewisekd.phopencode.core.codeview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.dp

/** Shows [content] in a phone-sized box under a Material theme that [darkTheme] picks. */
internal fun ComposeContentTestRule.setCodeViewContent(
    darkTheme: () -> Boolean = { false },
    content: @Composable () -> Unit,
) {
    setContent {
        MaterialTheme(colorScheme = if (darkTheme()) darkColorScheme() else lightColorScheme()) {
            Surface {
                Box(Modifier.size(360.dp, 480.dp)) { content() }
            }
        }
    }
}
