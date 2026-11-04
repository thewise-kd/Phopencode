package io.github.thewisekd.phopencode.feature.sessions.logic.create

import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.CreateSessionRequest
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.model.Model
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.storage_api.RecentFoldersStore
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.CreateFolderError
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionCreateEvent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionCreateIntent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionCreatePresenter
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionCreateState
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionsFailure
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.selectableAgents
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Creates a session on the server of the active connection (P5.4).
 *
 * Which server: the connection standing now, `ConnectionState.Connected` in
 * [ConnectionManager.state]. A connection is identified by its profile and `generation`. Every new
 * one loads the catalog again and starts with nothing chosen, whether or not a disconnect was seen
 * in between; a state other than `Connected` shows [SessionsFailure.NotConnected] as the catalog's
 * error, with no agents or models.
 *
 * The folder: kept as typed. It is used trimmed; a blank one is [CreateFolderError.Missing] when
 * creating, one that does not start with `/` is [CreateFolderError.NotAbsolute], shown as it is
 * typed. The catalog is loaded for the folder when it is absolute, else for the server's default
 * folder. Typing waits [TYPING_PAUSE] after the last change before loading, and the state shows
 * `loading` from the first change, so a session is never created with the previous folder's catalog.
 * Each change or retry is a request with a serial of its own. Only the answer to the latest
 * request ends `loading` or is shown; an older one that finishes late is dropped, and Create waits
 * for the latest one's answer.
 *
 * Choices: an agent must be one of [SessionCreateState.agents], a model one of
 * [SessionCreateState.models] with one of its variants or none; anything else is ignored. A new
 * catalog on the same connection keeps the choices it still offers and drops the rest; a catalog
 * on another connection, or a failed one, drops them all. Choices are sent only on the connection
 * whose catalog offered them.
 *
 * Creating: sent once. While one runs, or once the server has created a session, whatever happens
 * to the connection afterwards, [SessionCreateIntent.Create] does nothing. It needs a connection
 * and a catalog that is not loading. On success the folder is added to the recent folders of the
 * profile the session was created on (a storage failure is ignored: the session matters more than
 * the history), and then [SessionCreateEvent.OpenChat] follows as soon as that profile is connected:
 * at once, or, after a disconnect, when it is connected again, with [SessionsFailure.NotConnected]
 * shown meanwhile. It is never opened on another profile's connection. A failed create shows
 * [SessionsFailure] in [SessionCreateState.error] and allows another try.
 *
 * Recent folders follow [RecentFoldersStore.observe] for the connected profile; without a
 * connection the last profile's stay. A failing store shows an empty list and is read again after
 * [RECENT_RETRY_DELAY]; the contract has no field for its error.
 *
 * Nothing runs in the constructor: loading starts in [scope] after a `yield()`, as the screen's
 * scope may run a launch at once.
 *
 * @param directory The folder to preselect, from the route.
 * @param skillId The skill to attach to the first prompt, from the route; kept in the state.
 * @param connection The server is the one of `ConnectionState.Connected`, whose
 *   `server.profileId` names the recent folders to offer.
 * @param recentFolders The active server's recent folders; a created session's folder is added.
 */
class DefaultSessionCreatePresenter(
    private val directory: String?,
    private val skillId: String?,
    private val connection: ConnectionManager,
    private val recentFolders: RecentFoldersStore,
    private val scope: CoroutineScope,
) : SessionCreatePresenter {
    private val mutableState = MutableStateFlow(
        SessionCreateState(
            directory = directory.orEmpty(),
            skillId = skillId,
            folderError = typingError(directory.orEmpty()),
        ),
    )

    override val state: StateFlow<SessionCreateState> = mutableState.asStateFlow()

    // Unlimited, so that onIntent can send without suspending and no event is dropped.
    private val eventChannel = Channel<SessionCreateEvent>(Channel.UNLIMITED)

    override val events: Flow<SessionCreateEvent> = eventChannel.receiveAsFlow()

    /**
     * What the catalog is loaded for. Every request has a [serial] of its own, so two requests are
     * never equal and a `StateFlow` cannot conflate a change away: the collector always serves the
     * latest one, and serving it is what ends `loading`.
     */
    private data class CatalogRequest(val location: Location?, val serial: Long, val afterTyping: Boolean = false)

    private val catalogRequest = MutableStateFlow(CatalogRequest(catalogLocation(directory.orEmpty()), serial = 0))

    /** One connection: its profile and its generation. A new connect is a new identity. */
    private data class ConnectionKey(val profile: ServerProfileId, val generation: Long)

    private val lock = Any()

    /**
     * Guarded by [lock]: the connection the agents, models and choices shown belong to; null when
     * none are shown. Choices are sent only on this very connection.
     */
    private var catalogKey: ConnectionKey? = null

    /**
     * Guarded by [lock]: the [CatalogRequest.serial] whose catalog, or failure, is shown. Only the
     * latest request may end `loading` or show its result, and Create needs this to be the latest.
     */
    private var catalogSerial: Long = -1

    /** Guarded by [lock]: a create is running. */
    private var sending = false

    /**
     * Guarded by [lock]: the server created a session for this screen. Nothing is created again;
     * its chat opens once its profile is connected.
     */
    private var created: Pair<ServerProfileId, SessionId>? = null

    init {
        scope.launch {
            // The screen's scope may be Dispatchers.Main.immediate, which runs a launch at once; the
            // yield hands it back to the dispatcher, so nothing runs inside the constructor.
            yield()
            launch { followRecent() }
            followCatalog()
        }
    }

    override fun onIntent(intent: SessionCreateIntent) {
        when (intent) {
            is SessionCreateIntent.FolderChanged -> changeFolder(intent.directory)
            is SessionCreateIntent.AgentSelected -> mutableState.update { current ->
                val id = intent.agentId
                if (id == null || current.agents.any { it.id == id }) current.copy(selectedAgent = id) else current
            }
            is SessionCreateIntent.ModelSelected -> mutableState.update { current ->
                val model = intent.model
                if (model == null || offers(current.models, model)) current.copy(selectedModel = model) else current
            }
            is SessionCreateIntent.TitleChanged -> mutableState.update { it.copy(title = intent.title) }
            SessionCreateIntent.Create -> create()
            SessionCreateIntent.Retry -> synchronized(lock) {
                catalogRequest.update { CatalogRequest(it.location, it.serial + 1) }
                mutableState.update { it.copy(loading = true) }
            }
            SessionCreateIntent.DismissError -> mutableState.update { it.copy(error = null) }
            SessionCreateIntent.Cancel -> eventChannel.trySend(SessionCreateEvent.Close)
        }
    }

    private fun changeFolder(typed: String) {
        val location = catalogLocation(typed)
        synchronized(lock) {
            val reload = catalogRequest.value.location != location
            if (reload) catalogRequest.update { CatalogRequest(location, it.serial + 1, afterTyping = true) }
            mutableState.update {
                it.copy(directory = typed, folderError = typingError(typed), loading = it.loading || reload)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun followRecent() {
        connection.state
            .map { state ->
                when (state) {
                    is ConnectionState.Connected -> state.server.profileId
                    else -> null
                }
            }
            .distinctUntilChanged()
            .flatMapLatest { profile -> if (profile == null) flowOf(null) else recentOf(profile) }
            .collect { recent -> if (recent != null) mutableState.update { it.copy(recentFolders = recent) } }
    }

    private fun recentOf(profile: ServerProfileId): Flow<List<String>?> =
        // observe() itself may throw rather than return a failing flow.
        flow<List<String>?> { emitAll(recentFolders.observe(profile)) }.retryWhen { e, _ ->
            if (e is CancellationException) return@retryWhen false
            emit(emptyList())
            delay(RECENT_RETRY_DELAY)
            true
        }

    private suspend fun followCatalog() {
        val connected = connection.state
            .map { it as? ConnectionState.Connected }
            .distinctUntilChanged { old, new -> old?.let(::keyOf) == new?.let(::keyOf) }
        combine(connected, catalogRequest) { current, request -> current to request }
            .collectLatest { (current, request) -> loadCatalog(current, request) }
    }

    private suspend fun loadCatalog(connected: ConnectionState.Connected?, request: CatalogRequest) {
        if (connected == null) {
            showCatalogFailure(SessionsFailure.NotConnected, request.serial)
            return
        }
        val key = keyOf(connected)
        val sameConnection = synchronized(lock) {
            (catalogKey == key).also { same -> if (!same) catalogKey = null }
        }
        mutableState.update {
            if (sameConnection) {
                it.copy(loading = true)
            } else {
                // Another connection, seen or not as a disconnect first: nothing chosen on the
                // previous one carries over, even where the new server offers the same.
                it.copy(
                    loading = true,
                    agents = emptyList(),
                    models = emptyList(),
                    defaultModel = null,
                    selectedAgent = null,
                    selectedModel = null,
                )
            }
        }
        if (request.afterTyping) delay(TYPING_PAUSE)
        try {
            val catalog = connected.client.catalog
            val agents = selectableAgents(catalog.agents(request.location))
            val models = catalog.models(request.location)
            val defaultModel = catalog.defaultModel(request.location)
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                // A newer request may not have reached this collector yet; its own load decides.
                if (catalogRequest.value.serial != request.serial) return
                catalogKey = key
                catalogSerial = request.serial
                mutableState.update { current ->
                    current.copy(
                        loading = false,
                        agents = agents,
                        models = models,
                        defaultModel = defaultModel,
                        selectedAgent = current.selectedAgent?.takeIf { id -> agents.any { it.id == id } },
                        selectedModel = current.selectedModel?.takeIf { offers(models, it) },
                        catalogError = null,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            showCatalogFailure(SessionsFailure.of(e.error), request.serial)
        } catch (_: Exception) {
            showCatalogFailure(SessionsFailure.Unknown, request.serial)
        }
    }

    /** Shows [failure] as the answer to request [serial], if that is still the latest request. */
    private fun showCatalogFailure(failure: SessionsFailure, serial: Long) {
        synchronized(lock) {
            if (catalogRequest.value.serial != serial) return
            catalogKey = null
            catalogSerial = serial
            mutableState.update {
                it.copy(
                    loading = false,
                    agents = emptyList(),
                    models = emptyList(),
                    defaultModel = null,
                    selectedAgent = null,
                    selectedModel = null,
                    catalogError = failure,
                )
            }
        }
    }

    /**
     * Everything is read here, when the user asks, under [lock]: the folder, the choices, the
     * connection, and a catalog that answers the latest request, so that what is sent is what the
     * screen showed.
     */
    private fun create() {
        val connected: ConnectionState.Connected
        val folder: String
        val request: CreateSessionRequest
        synchronized(lock) {
            val current = mutableState.value
            if (current.loading || current.creating || sending || created != null) return
            // The catalog shown answers an older request; the latest one's load decides.
            if (catalogSerial != catalogRequest.value.serial) return
            folder = current.directory.trim()
            val folderError = when {
                folder.isEmpty() -> CreateFolderError.Missing
                !folder.startsWith('/') -> CreateFolderError.NotAbsolute
                else -> null
            }
            if (folderError != null) {
                mutableState.update { it.copy(folderError = folderError) }
                return
            }
            connected = connection.state.value as? ConnectionState.Connected ?: run {
                mutableState.update { it.copy(error = SessionsFailure.NotConnected) }
                return
            }
            val hasChoices = current.selectedAgent != null || current.selectedModel != null
            // The catalog shown is from another connection; its reload is on its way.
            if (hasChoices && catalogKey != keyOf(connected)) return
            sending = true
            request = CreateSessionRequest(
                title = current.title.trim().ifEmpty { null },
                agent = current.selectedAgent,
                model = current.selectedModel,
                location = Location(folder),
            )
            mutableState.update { it.copy(creating = true, error = null, folderError = null) }
        }
        scope.launch {
            val profile = connected.server.profileId
            val session = try {
                send(connected, request)?.also { synchronized(lock) { created = profile to it.id } }
            } finally {
                synchronized(lock) { sending = false }
            } ?: return@launch
            try {
                recentFolders.add(profile, folder)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The history is best effort; the session exists either way.
            }
            openChat(profile, session.id)
        }
    }

    /**
     * Opens the created session's chat once [profile] is connected: at once if it is now, else
     * when it is connected again, showing [SessionsFailure.NotConnected] meanwhile. Never on
     * another profile's connection, where the session does not exist.
     */
    private suspend fun openChat(profile: ServerProfileId, id: SessionId) {
        if (!serves(connection.state.value, profile)) {
            mutableState.update { it.copy(error = SessionsFailure.NotConnected) }
            connection.state.first { serves(it, profile) }
        }
        mutableState.update { it.copy(error = null) }
        eventChannel.trySend(SessionCreateEvent.OpenChat(id))
    }

    /** The created session, or null after showing why it could not be created. */
    private suspend fun send(connected: ConnectionState.Connected, request: CreateSessionRequest): Session? {
        val failure = try {
            return connected.client.sessions.create(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            SessionsFailure.of(e.error)
        } catch (_: Exception) {
            SessionsFailure.Unknown
        }
        mutableState.update { it.copy(creating = false, error = failure) }
        return null
    }

    private companion object {
        fun keyOf(connected: ConnectionState.Connected) = ConnectionKey(connected.server.profileId, connected.generation)

        fun serves(state: ConnectionState, profile: ServerProfileId): Boolean =
            state is ConnectionState.Connected && state.server.profileId == profile

        /** How long typing in the folder field must pause before its catalog loads. */
        val TYPING_PAUSE = 400.milliseconds

        /** How long to wait before reading recent folders again after a failed read. */
        val RECENT_RETRY_DELAY = 1.seconds

        /** The folder to load the catalog for: the typed one when absolute, else the server's default. */
        fun catalogLocation(typed: String): Location? =
            typed.trim().takeIf { it.startsWith('/') }?.let(::Location)

        /** Shown while typing: a relative path. A blank one waits for Create. */
        fun typingError(typed: String): CreateFolderError? {
            val folder = typed.trim()
            return if (folder.isNotEmpty() && !folder.startsWith('/')) CreateFolderError.NotAbsolute else null
        }

        fun offers(models: List<Model>, ref: ModelRef): Boolean = models.any { model ->
            model.providerId == ref.providerId && model.id == ref.modelId &&
                (ref.variant == null || ref.variant in model.variants)
        }
    }
}
