package io.github.thewisekd.phopencode.feature.git.logic.diff

import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffState
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffTarget
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.toGitException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * The diff of one file (P9.6), as [DiffState] describes it; the steps and decisions are in
 * [DiffLoader].
 *
 * Loading starts in [scope] after a `yield()`, so the first frame shows the initial state; nothing
 * runs in the constructor. [DiffIntent.Refresh] reads again, [DiffIntent.Retry] reads again only
 * after an error, and either does nothing while a read runs. While it reads again, the diff shown
 * stays; a failed read keeps it too and sets [DiffState.error]. Leaving the screen cancels [scope],
 * which ends the running git command.
 *
 * Nothing here logs; [DiffState] prints no path or content.
 *
 * @param directory The folder from the route, where git runs.
 * @param target The diff to show, from the route (`DiffTarget.of`).
 * @param git Runs `git` on the active connection.
 * @param maxBytes Above this much diff output, the content is `DiffContent.TooLarge`; git's output
 *   is cut there and never held whole.
 */
class DefaultDiffPresenter(
    private val directory: String,
    private val target: DiffTarget,
    private val git: GitConnection,
    private val scope: CoroutineScope,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : DiffPresenter {
    init {
        require(maxBytes > 0) { "The byte limit must be positive" }
    }

    private val mutableState = MutableStateFlow(DiffState(target = target))

    override val state: StateFlow<DiffState> = mutableState.asStateFlow()

    // Unlimited, so that onIntent sends without suspending and no event is dropped.
    private val eventChannel = Channel<DiffEvent>(Channel.UNLIMITED)

    override val events: Flow<DiffEvent> = eventChannel.receiveAsFlow()

    private val loader = DiffLoader(directory, target, maxBytes)

    private val lock = Any()

    /** The running read; guarded by [lock]. */
    private var job: Job? = null

    init {
        load()
    }

    override fun onIntent(intent: DiffIntent) {
        when (intent) {
            DiffIntent.Refresh -> load()
            DiffIntent.Retry -> if (mutableState.value.error != null) load()
            DiffIntent.Close -> eventChannel.trySend(DiffEvent.Close)
        }
    }

    private fun load() {
        synchronized(lock) {
            if (job?.isActive == true) return
            mutableState.update { it.copy(loading = true, error = null) }
            job = scope.launch {
                yield()
                try {
                    val diff = git.withExec { exec -> loader.load(exec) }
                    mutableState.update { it.copy(loading = false, diff = diff, error = null) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    val error = failure.toGitException().failure
                    mutableState.update { it.copy(loading = false, error = error) }
                }
            }
        }
    }

    override fun toString(): String = "DefaultDiffPresenter($target)"

    companion object {
        /** 1 MiB. */
        const val DEFAULT_MAX_BYTES: Long = 1L shl 20
    }
}
