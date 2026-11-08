package io.github.thewisekd.phopencode.feature.chat.logic.palette

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSessionState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.BuiltInCommand
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteAction
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteError
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteEvent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteItem
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PalettePresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteTrigger
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PromptAttachment
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SlashWord
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * The command palette (P7.8); the rules are on [PaletteState].
 *
 * - Every draft from [PaletteIntent.QueryChanged] is read for its trigger word. The lists follow
 *   the newest draft only: each new word cancels the work of the one before, so an old answer never
 *   replaces a newer one.
 * - Commands, skills and agents are asked once per folder through [ChatSession.call] and kept; a
 *   filter on a list already kept shows at once. Whatever must still be asked waits [DEBOUNCE_MS]
 *   first, so a burst of keystrokes asks once, for the last word. Files are searched per query, in
 *   the session's folder, after the same wait; a session without a folder searches none.
 * - Until the session is loaded, its folder is unknown and nothing is asked; the palette opens
 *   empty.
 * - A pick counts only while the items shown were made for the draft's current trigger word and
 *   the session's current folder.
 * - Events wait, unbounded, until the chat collects them: a pick has already changed the draft, so
 *   its event is never dropped. They come only from the user's picks, so they stay few.
 * - A command runs on its own, one at a time, and is not cancelled by later typing. Once it is
 *   accepted, [PaletteEvent.CommandSent] names the exact draft it was sent from, never a copy kept
 *   here, which may be older; the chat empties the composer only if it still holds that draft. A
 *   run cut short counts as failed and frees the slot; only this presenter's own scope ending
 *   cancels it.
 * - A sent slash word ([PaletteIntent.Submit]) counts as a command on its way from the moment it
 *   is handed over: one at a time with picked commands. The commands it is checked against are the
 *   kept ones for the session's folder, else asked for and kept like the palette's own.
 * - Commands and skills are asked and kept apart: a list that fails shows its error and keeps the
 *   other, and a retry asks only for what is missing.
 * - Built-ins are the app's, so they show at once, while the server's lists load or after they
 *   failed. A built-in runs nothing here: [PaletteEvent.RunBuiltIn] hands it to the chat.
 *
 * Nothing runs in the constructor: work starts in [scope] after a `yield()`. Nothing here logs; the
 * draft, the query and file paths are user data and print masked.
 *
 * @param route The destination's route.
 * @param scope The destination's scope; work started here stops with it.
 * @param session The destination's session, shared with its other presenters.
 */
class DefaultPalettePresenter(
    @Suppress("unused") private val route: ChatRoute,
    private val scope: CoroutineScope,
    private val session: ChatSession,
) : PalettePresenter {
    /** The trigger word at the draft's end: [start] is its index, [text] includes the trigger. */
    private data class Word(val trigger: PaletteTrigger, val start: Int, val text: String) {
        val query: String
            get() = text.substring(1)

        override fun toString(): String = "Word(trigger=$trigger, start=$start, text=***)"
    }

    /** What the user typed and did; [attempt] grows with each retry. */
    private data class Input(val draft: String = "", val dismissed: Word? = null, val attempt: Int = 0) {
        val word: Word?
            get() = wordOf(draft)?.takeIf { it != dismissed }

        override fun toString(): String = "Input(draft=***, dismissed=$dismissed, attempt=$attempt)"
    }

    /** The session's folder, once it is loaded; [directory] may be null. */
    private data class Folder(val directory: String?) {
        val location: Location?
            get() = directory?.let(::Location)

        override fun toString(): String = "Folder(***)"
    }

    /** What the lists must show; null [folder] while the session is not loaded. */
    private data class Request(val word: Word, val folder: Folder?, val attempt: Int)

    private data class FoundFiles(val folder: Folder, val query: String, val files: List<PaletteItem.File>) {
        override fun toString(): String = "FoundFiles(files=${files.size})"
    }

    private val mutableState = MutableStateFlow(PaletteState())

    override val state: StateFlow<PaletteState> = mutableState.asStateFlow()

    private val eventChannel = Channel<PaletteEvent>(Channel.UNLIMITED)

    override val events: Flow<PaletteEvent> = eventChannel.receiveAsFlow()

    private val input = MutableStateFlow(Input())

    /**
     * Guards [running], [shownWord] and [shownFolder], and keeps the last two in step with the items
     * shown.
     */
    private val lock = Any()

    /** One command at a time. */
    private var running = false

    /** The trigger word the items shown were made for; null while closed. */
    private var shownWord: Word? = null

    /** The folder the items shown were made for; null while closed or not loaded. */
    private var shownFolder: Folder? = null

    // Kept apart, so one list that failed never drops the other. Written by the load collector and
    // by a sent slash word: both store the same lists.
    private val commandLists = ConcurrentHashMap<Folder, List<PaletteItem.Command>>()
    private val skillLists = ConcurrentHashMap<Folder, List<PaletteItem.Skill>>()

    // The request for a folder's commands on its way, shared by all who need it meanwhile.
    private val commandFetches = ConcurrentHashMap<Folder, CompletableDeferred<Result<List<PaletteItem.Command>>>>()

    // Read and written only by the load collector, which runs one request at a time.
    private val agentLists = HashMap<Folder, List<PaletteItem.Agent>>()
    private var lastFiles: FoundFiles? = null

    init {
        scope.launch {
            yield()
            val folders = session.state.map(::folderOf).distinctUntilChanged()
            combine(input, folders) { typed, folder -> typed.word?.let { Request(it, folder, typed.attempt) } }
                .distinctUntilChanged()
                .collectLatest(::load)
        }
    }

    override fun onIntent(intent: PaletteIntent) {
        when (intent) {
            is PaletteIntent.QueryChanged -> {
                val previous = input.getAndUpdate { typed ->
                    // A dismissed word stays closed only while it is the word typed.
                    val dismissed = typed.dismissed?.takeIf { it == wordOf(intent.draft) }
                    typed.copy(draft = intent.draft, dismissed = dismissed)
                }
                if (previous.draft != intent.draft) mutableState.update { it.copy(notice = null) }
            }
            is PaletteIntent.Pick -> pick(intent.item)
            PaletteIntent.Dismiss -> input.update { it.copy(dismissed = wordOf(it.draft)) }
            PaletteIntent.Retry -> if (mutableState.value.error?.action == PaletteAction.Load) {
                input.update { it.copy(attempt = it.attempt + 1) }
            }
            PaletteIntent.DismissError -> mutableState.update { it.copy(error = null) }
            is PaletteIntent.Submit -> submit(intent.draft)
            is PaletteIntent.ShowNotice -> mutableState.update { it.copy(notice = intent.notice) }
            PaletteIntent.DismissNotice -> mutableState.update { it.copy(notice = null) }
        }
    }

    // Lists

    private suspend fun load(request: Request?) {
        if (request == null) {
            publish(word = null, folder = null, items = emptyList(), loading = false, failure = null)
            return
        }
        val word = request.word
        val folder = request.folder
        if (folder == null) {
            publish(word, folder, emptyList(), loading = false, failure = null)
            return
        }
        when (word.trigger) {
            PaletteTrigger.Slash -> loadSlash(word, folder)
            PaletteTrigger.At -> loadAt(word, folder)
        }
    }

    private suspend fun loadSlash(word: Word, folder: Folder) {
        val query = word.query
        val commands = commandLists[folder]
        val skills = skillLists[folder]
        if (commands != null && skills != null) {
            publish(word, folder, slashItems(commands, skills, query), loading = false, failure = null)
            return
        }
        // What is known shows at once, the built-ins always; none is known to be overridden while
        // the commands are not.
        publish(word, folder, slashItems(commands.orEmpty(), skills.orEmpty(), query), loading = true, failure = null)
        delay(DEBOUNCE_MS)
        // Each list on its own: one that fails keeps the other, and only the first failure shows.
        var failure: ChatFailure? = null
        val loadedCommands = commands ?: try {
            fetchCommands(folder)
        } catch (e: ApiException) {
            failure = ChatFailure.of(e.error)
            null
        }
        val loadedSkills = skills ?: try {
            fetchSkills(folder)
        } catch (e: ApiException) {
            failure = failure ?: ChatFailure.of(e.error)
            null
        }
        publish(word, folder, slashItems(loadedCommands.orEmpty(), loadedSkills.orEmpty(), query), loading = false, failure = failure)
    }

    /**
     * The commands of [folder]: the kept ones, else those of the request already on its way, else
     * asked for and kept. The request runs in [scope], so a caller that stops waiting, as the list
     * does when the word changes, does not stop it for the others; one that failed is dropped, so
     * the next caller asks again.
     *
     * @throws ApiException The request failed.
     */
    private suspend fun fetchCommands(folder: Folder): List<PaletteItem.Command> {
        commandLists[folder]?.let { return it }
        val mine = CompletableDeferred<Result<List<PaletteItem.Command>>>()
        val pending = commandFetches.putIfAbsent(folder, mine)
        if (pending != null) return pending.await().getOrThrow()
        val request = scope.launch {
            val result = try {
                Result.success(
                    session.call { client ->
                        client.catalog.commands(folder.location).map { PaletteItem.Command(it.name, it.description) }
                    },
                )
            } catch (e: ApiException) {
                Result.failure(e)
            }
            result.onSuccess { commandLists[folder] = it }
            commandFetches.remove(folder, mine)
            mine.complete(result)
        }
        // A request cut short, or never started, ends as a failed one: dropped, so the next caller
        // asks again, and its waiters freed. Completing again after an answer does nothing.
        request.invokeOnCompletion {
            commandFetches.remove(folder, mine)
            mine.complete(Result.failure(ApiException(ApiError.Unexpected())))
        }
        // Waiting stops only this caller if it is cancelled; the request goes on for the others.
        return mine.await().getOrThrow()
    }

    /** Asks for the skills of [folder], and keeps them. */
    private suspend fun fetchSkills(folder: Folder): List<PaletteItem.Skill> {
        val skills = session.call { client ->
            client.catalog.skills(folder.location).map { PaletteItem.Skill(it.name, it.description) }
        }
        skillLists[folder] = skills
        return skills
    }

    private suspend fun loadAt(word: Word, folder: Folder) {
        val query = word.query
        val searchFiles = folder.location != null
        var agents = agentLists[folder]
        val found = lastFiles?.takeIf { it.folder == folder }
        if (agents != null && (!searchFiles || found?.query == query)) {
            publish(word, folder, atItems(agents, found?.files.orEmpty(), query), loading = false, failure = null)
            return
        }
        // What is known shows while the rest is asked; earlier files only if they still match.
        publish(word, folder, atItems(agents.orEmpty(), found?.files.orEmpty(), query), loading = true, failure = null)
        delay(DEBOUNCE_MS)
        var files = found?.files.orEmpty()
        try {
            session.call { client ->
                if (agents == null) {
                    val listed = client.catalog.agents(folder.location)
                        .filterNot { it.hidden }
                        .map { PaletteItem.Agent(it.id, it.name, it.description) }
                    agentLists[folder] = listed
                    agents = listed
                }
                val location = folder.location
                if (location != null) {
                    files = client.fs.find(location, query, limit = FILE_LIMIT).map(::fileItem)
                    lastFiles = FoundFiles(folder, query, files)
                }
            }
        } catch (e: ApiException) {
            publish(word, folder, atItems(agents.orEmpty(), files, query), loading = false, failure = ChatFailure.of(e.error))
            return
        }
        publish(word, folder, atItems(agents.orEmpty(), files, query), loading = false, failure = null)
    }

    /** Shows the lists made for [word] in [folder], keeping [running] and a command's error. */
    private fun publish(word: Word?, folder: Folder?, items: List<PaletteItem>, loading: Boolean, failure: ChatFailure?) {
        synchronized(lock) {
            shownWord = word
            shownFolder = folder
            mutableState.update { current ->
                val error = when {
                    failure != null -> PaletteError(PaletteAction.Load, failure)
                    current.error?.action == PaletteAction.RunCommand -> current.error
                    else -> null
                }
                current.copy(
                    trigger = word?.trigger,
                    query = word?.query.orEmpty(),
                    items = items,
                    loading = loading,
                    error = error,
                )
            }
        }
    }

    // Picks

    /**
     * Takes [item] only if it is shown and the items shown were made for the trigger word of the
     * draft as it is now, in the session's folder as it is now: a draft or a folder that changed
     * since, but whose list has not caught up, picks nothing.
     */
    private fun pick(item: PaletteItem) {
        val typed = input.value
        val draft = typed.draft
        val word = synchronized(lock) {
            val word = typed.word ?: return
            val folder = folderOf(session.state.value) ?: return
            val shown = mutableState.value
            if (word != shownWord || folder != shownFolder || !shown.open || item !in shown.items) return
            word
        }
        when (item) {
            is PaletteItem.Command -> run(item, draft, word)
            is PaletteItem.Skill -> attach(draft, word, PromptAttachment.Skill(item.name))
            is PaletteItem.Agent -> attach(draft, word, PromptAttachment.Agent(item.id))
            is PaletteItem.File -> attach(draft, word, PromptAttachment.File(item.path))
            // The same precedence as a send: a server command of the built-in's name wins.
            is PaletteItem.BuiltIn -> {
                if (!claim()) return
                dispatch(item.command.primary, item.command, SlashWord.of(draft)?.rest.orEmpty(), draft)
            }
        }
    }

    private fun attach(draft: String, word: Word, attachment: PromptAttachment) {
        val next = draft.substring(0, word.start)
        input.update { it.copy(draft = next, dismissed = null) }
        eventChannel.trySend(PaletteEvent.Attached(next, attachment))
    }

    private fun run(command: PaletteItem.Command, draft: String, word: Word) {
        if (!claim()) return
        val text = draft.substring(word.start + word.text.length).trim()
        scope.launch { execute(command.name, text, draft) }
    }

    /** Claims the one command that may be on its way; false when one already is. */
    private fun claim(): Boolean {
        synchronized(lock) {
            if (running) return false
            running = true
        }
        mutableState.update {
            it.copy(running = true, notice = null, error = it.error?.takeIf { error -> error.action == PaletteAction.Load })
        }
        return true
    }

    /** Frees the claim, setting [failure] as the command's error if there is one. */
    private fun release(failure: ChatFailure? = null) {
        synchronized(lock) { running = false }
        mutableState.update {
            it.copy(running = false, error = failure?.let { reason -> PaletteError(PaletteAction.RunCommand, reason) } ?: it.error)
        }
    }

    /** Runs the server command [name] with [text], sent from [draft]; the claim is held. */
    private suspend fun execute(name: String, text: String, draft: String) {
        val failure = try {
            session.call { client -> client.messages.command(session.sessionId, name, text) }
            null
        } catch (e: ApiException) {
            ChatFailure.of(e.error)
        } catch (e: CancellationException) {
            // Only this presenter's scope ending stops here; a call cut short is a failed run.
            if (!currentCoroutineContext().isActive) {
                release()
                throw e
            }
            ChatFailure.Unknown
        }
        if (failure == null) {
            // The kept copy empties only if it is the draft sent; the chat checks its own the same way.
            input.update { if (it.draft == draft) it.copy(draft = "", dismissed = null) else it }
            release()
            eventChannel.trySend(PaletteEvent.CommandSent(from = draft))
        } else {
            release(failure)
        }
    }

    // Sending

    /**
     * Works out what the sent [draft] is: a server command of the slash word's exact name runs, else
     * a built-in of that name or alias goes to the chat, else the draft goes back as a prompt. See
     * [PaletteState] for the rules.
     */
    private fun submit(draft: String) {
        val slash = SlashWord.of(draft)
        if (slash == null) {
            eventChannel.trySend(PaletteEvent.SendPrompt(draft))
            return
        }
        if (!claim()) return
        dispatch(slash.name, BuiltInCommand.named(slash.name), slash.rest, draft)
    }

    /**
     * Runs the server command [name] if the session folder's commands hold it, waiting for them if
     * they are not known yet; else [builtIn], through [PaletteEvent.RunBuiltIn]; else hands [draft]
     * back as a prompt. When the commands cannot be had, [builtIn] still runs, and with none the
     * failure shows. [rest] is the command's text, or the draft the built-in leaves. The claim is
     * held, and freed here.
     */
    private fun dispatch(name: String, builtIn: BuiltInCommand?, rest: String, draft: String) {
        scope.launch {
            val folder = folderOf(session.state.value)
            var failure: ChatFailure? = null
            val commands: Set<String>? = when {
                // Not loaded yet: no server command is known, and none can run.
                folder == null -> emptySet()
                else -> try {
                    // Only the commands decide; the skills play no part, failed or not.
                    fetchCommands(folder).mapTo(HashSet()) { it.name }
                } catch (e: ApiException) {
                    failure = ChatFailure.of(e.error)
                    null
                }
            }
            when {
                commands != null && name in commands -> execute(name, rest, draft)
                builtIn != null -> {
                    release()
                    eventChannel.trySend(PaletteEvent.RunBuiltIn(builtIn, from = draft, draft = rest))
                }
                failure != null -> release(failure)
                else -> {
                    release()
                    eventChannel.trySend(PaletteEvent.SendPrompt(draft))
                }
            }
        }
    }

    private companion object {
        /** How long a word must stay before the server is asked for it. */
        const val DEBOUNCE_MS = 250L

        /** The most files one search shows. */
        const val FILE_LIMIT = 50

        /**
         * The draft's last word, from its last whitespace to its end, if the draft ends with it and
         * it opens the palette: `/` only as the first word, `@` anywhere.
         */
        fun wordOf(draft: String): Word? {
            if (draft.isEmpty() || draft.last().isWhitespace()) return null
            val start = draft.indexOfLast { it.isWhitespace() } + 1
            val text = draft.substring(start)
            return when (text.first()) {
                '/' -> if (draft.substring(0, start).isBlank()) Word(PaletteTrigger.Slash, start, text) else null
                '@' -> Word(PaletteTrigger.At, start, text)
                else -> null
            }
        }

        /** The session's folder, or null while the session is not loaded. */
        fun folderOf(chat: ChatSessionState): Folder? = chat.session?.let { Folder(it.session.directory) }

        fun fileItem(entry: FileEntry) = PaletteItem.File(entry.path, directory = entry.type == FileEntryType.Directory)

        fun slashItems(commands: List<PaletteItem.Command>, skills: List<PaletteItem.Skill>, query: String): List<PaletteItem> =
            commands.matching(query) { listOf(it.name) } +
                builtInItems(query, overridden = commands.mapTo(HashSet()) { it.name }) +
                skills.matching(query) { listOf(it.name) }

        /**
         * The built-ins with a name or alias that starts with [query], ignoring case, in their
         * order; none whose name is in [overridden], the server's commands.
         */
        fun builtInItems(query: String, overridden: Set<String>): List<PaletteItem.BuiltIn> =
            BuiltInCommand.entries
                .filter { it.primary !in overridden }
                .filter { command -> command.names.any { it.startsWith(query, ignoreCase = true) } }
                .map { PaletteItem.BuiltIn(it) }

        fun atItems(agents: List<PaletteItem.Agent>, files: List<PaletteItem.File>, query: String): List<PaletteItem> =
            agents.matching(query) { listOf(it.id, it.name) } +
                files.matching(query) { listOf(it.path, it.path.trimEnd('/').substringAfterLast('/')) }

        /**
         * Those of [this] with a key holding [query], case-insensitively; those with a key starting
         * with it first, each group in its given order.
         */
        fun <T> List<T>.matching(query: String, keys: (T) -> List<String>): List<T> {
            val (prefixed, others) = filter { item -> keys(item).any { it.contains(query, ignoreCase = true) } }
                .partition { item -> keys(item).any { it.startsWith(query, ignoreCase = true) } }
            return prefixed + others
        }
    }
}
