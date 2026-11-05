package io.github.thewisekd.phopencode.feature.files.ui.editor

import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import io.github.thewisekd.phopencode.core.navigation.FilesRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorState
import kotlin.test.assertNotNull

internal const val ROOT = "/srv/çalışma alanı"
internal const val PATH = "src/ana kod.kt"

internal val ROUTE = FilesRoute.Editor(directory = ROOT, path = PATH)

internal val LOADING = EditorState(path = PATH)
internal val CLEAN = EditorState(path = PATH, loading = false, text = "fun main() = Unit\n")
internal val DIRTY = CLEAN.copy(text = "fun main() = println(1)\n", dirty = true)
internal val SAVING = DIRTY.copy(saving = true)

/** The class of core/codeview's editor; the feature does not compile against sora. */
private const val CODE_EDITOR_CLASS = "io.github.rosemoe.sora.widget.CodeEditor"

/** The editor screen in the app theme, with [state] changeable by the test and every intent recorded. */
internal class EditorHarness(initial: EditorState) {
    val state: MutableState<EditorState> = mutableStateOf(initial)
    val intents = mutableListOf<EditorIntent>()
    var root: View? = null
    var back: OnBackPressedDispatcher? = null

    @Composable
    fun Content() {
        root = LocalView.current
        back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
        PhopencodeTheme(darkTheme = false) {
            EditorScreen(route = ROUTE, state = state.value, onIntent = { intents += it })
        }
    }

    /** The sora editor inside the code view; looked up by class, since sora is not on the compile path. */
    fun codeEditor(): View = assertNotNull(find(assertNotNull(root, "not composed")), "no code editor on screen")

    private fun find(view: View): View? {
        if (view.javaClass.name == CODE_EDITOR_CLASS) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        }
        return null
    }
}

/** The editor's whole text, through sora's `getText()`. */
internal fun View.editorText(): String = javaClass.getMethod("getText").invoke(this).toString()

/** Whether sora's editor takes edits. */
internal fun View.editorEditable(): Boolean = javaClass.getMethod("isEditable").invoke(this) as Boolean

/** Types [text] at [line] and [column], as the keyboard would, through sora's `Content.insert`. */
internal fun View.typeAt(line: Int, column: Int, text: String) {
    val content = javaClass.getMethod("getText").invoke(this)
    content.javaClass
        .getMethod("insert", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, CharSequence::class.java)
        .invoke(content, line, column, text)
}

internal fun ComposeContentTestRule.setEditor(state: EditorState): EditorHarness {
    val harness = EditorHarness(state)
    setContent { harness.Content() }
    return harness
}

/** Whether some node carries [tag]. */
internal fun ComposeContentTestRule.hasTag(tag: String): Boolean =
    onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
