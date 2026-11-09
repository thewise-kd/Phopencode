package io.github.thewisekd.phopencode.feature.notify.logic.setup

import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.storage_api.SecretReadResult
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyException
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyFailure
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifySecrets
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyService
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyServiceStatus
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NtfyServer
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NtfySettings
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NtfyTopic
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupError
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupEvent
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupIntent
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupPresenter
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupState
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupStep
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.security.SecureRandom
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * The notification setup (P11.9), following `SetupContract.kt`.
 *
 * - Loading starts in [scope] after a `yield()`: it reads [NotifySecrets.TOPIC] and
 *   [NotifySecrets.SERVER]. A missing, unreadable or invalid topic is replaced by a new one of
 *   [NtfyTopic.MIN_LENGTH] characters drawn uniformly from [NtfyTopic.ALPHABET] with [random]
 *   (each byte's low six bits pick a character; 64 divides 256, so every character is equally
 *   likely), which is at least [NtfyTopic.MIN_BITS] bits. The new topic is used only once it is
 *   stored. A missing, unreadable or invalid server is [NtfyServer.DEFAULT] and is not written.
 *   Then the status is read as [SetupStep.CheckStatus].
 * - A load that fails (the store cannot be reached) leaves no topic; [SetupIntent.RefreshStatus]
 *   loads again, and nothing that needs the topic or the server runs until a load has worked.
 * - One step at a time: an intent that starts a step is ignored while [SetupState.busy]. Starting
 *   a step clears [SetupState.error] and [SetupState.testSent]. Install and remove read the status
 *   again afterwards, whether they worked or not; if both fail, the first reason is kept.
 * - The server field follows the stored server until the user types; a save or a reset that
 *   finishes after the user typed again leaves the typed text.
 * - A call to [service] that fails with [NotifyException] gives its failure; anything else it
 *   throws is [NotifyFailure.Unknown] (the port throws only [NotifyException]). A call to
 *   [secrets] that fails with an [IOException] is [NotifyFailure.StorageFailed]; anything else is
 *   [NotifyFailure.Unknown]. A cancellation that is not this presenter's own, such as a timeout
 *   inside a port, is [NotifyFailure.Unknown] too; the scope's own cancellation passes through and
 *   leaves the state as it was.
 *
 * The topic lives only in the secret store, in this object (as a masked [NtfyTopic]), in the
 * settings handed to [service], and in the masked [SetupState.subscriptionLink]. Nothing here logs,
 * and no exception message is built from it. Every `SecretValue` read or written is closed once used.
 *
 * @param secrets Keeps the topic and the server ([NotifySecrets]).
 * @param service The notification service on the server of the active connection.
 * @param random Makes a new topic.
 * @param scope The destination's scope; work started here stops with it.
 */
class DefaultSetupPresenter(
    private val secrets: SecretStore,
    private val service: NotifyService,
    private val random: SecureRandom,
    private val scope: CoroutineScope,
) : SetupPresenter {
    private val mutableState = MutableStateFlow(SetupState())

    override val state: StateFlow<SetupState> = mutableState.asStateFlow()

    /** Unlimited, so `trySend` never drops an event. */
    private val eventChannel = Channel<SetupEvent>(Channel.UNLIMITED)

    override val events: Flow<SetupEvent> = eventChannel.receiveAsFlow()

    /** Guards every state change that reads the state first, [topic] and [edited]. */
    private val lock = Any()

    /** Guarded by [lock]: the stored topic; null until a load has worked. */
    private var topic: NtfyTopic? = null

    /** Guarded by [lock]: the user typed in the server field, so it no longer follows the store. */
    private var edited = false

    /** Guarded by [lock]: how many times the user typed in the server field. */
    private var edits = 0L

    /** Guarded by [lock]: [edits] when the running step started; one step runs at a time. */
    private var editsAtStepStart = 0L

    /** Guarded by [lock]: the server field's text when the running step started. */
    private var textAtStepStart = ""

    init {
        scope.launch {
            yield()
            load()
        }
    }

    override fun onIntent(intent: SetupIntent) {
        when (intent) {
            is SetupIntent.EditServer -> synchronized(lock) {
                edited = true
                edits++
                mutableState.update {
                    it.copy(serverText = intent.text, serverProblem = NtfyServer.problemOf(intent.text))
                }
            }
            SetupIntent.SaveServer -> saveField()
            SetupIntent.ResetServer -> startWithSettings(SetupStep.SaveServer) { saveServer(NtfyServer.DEFAULT) }
            SetupIntent.RefreshStatus -> refresh()
            SetupIntent.Install -> startWithSettings(SetupStep.Install) { settings ->
                val failure = serviceFailureOf { service.install(settings) }
                checkStatus(earlier = failure?.let { SetupError(SetupStep.Install, it) })
            }
            SetupIntent.Remove -> start(SetupStep.Remove) {
                val failure = serviceFailureOf { service.remove() }
                checkStatus(earlier = failure?.let { SetupError(SetupStep.Remove, it) })
            }
            SetupIntent.SendTest -> startWithSettings(SetupStep.SendTest) { settings ->
                val failure = serviceFailureOf { service.sendTest(settings) }
                finish {
                    it.copy(testSent = failure == null, error = failure?.let { f -> SetupError(SetupStep.SendTest, f) })
                }
            }
            SetupIntent.DismissError -> synchronized(lock) { mutableState.update { it.copy(error = null) } }
            SetupIntent.Close -> eventChannel.trySend(SetupEvent.Close)
        }
    }

    /** Reads the status again, or loads again when no load has worked yet. */
    private fun refresh() {
        val reload = synchronized(lock) {
            val current = mutableState.value
            if (current.busy) return
            if (topic == null) {
                mutableState.value = current.copy(loading = true, error = null, testSent = false)
                true
            } else {
                mutableState.value = current.copy(operation = SetupStep.CheckStatus, error = null, testSent = false)
                false
            }
        }
        scope.launch { if (reload) load() else checkStatus(earlier = null) }
    }

    /**
     * Under [lock]: marks [step] as running, clearing the last result, and records the server field
     * as it is now; false while busy.
     */
    private fun begin(step: SetupStep): Boolean {
        val current = mutableState.value
        if (current.busy) return false
        mutableState.value = current.copy(operation = step, error = null, testSent = false)
        editsAtStepStart = edits
        textAtStepStart = current.serverText
        return true
    }

    /**
     * Saves the server field. Reading the text, parsing it and starting the step happen in one
     * locked block, so the text recorded by [begin] is exactly the text being saved.
     */
    private fun saveField() {
        val server = synchronized(lock) {
            val current = mutableState.value
            val parsed = NtfyServer.parse(current.serverText)
            if (parsed == null) {
                mutableState.value = current.copy(serverProblem = NtfyServer.problemOf(current.serverText))
                return
            }
            if (topic == null || !begin(SetupStep.SaveServer)) return
            parsed
        }
        scope.launch { saveServer(server) }
    }

    /** Starts [step], which needs nothing loaded, unless busy. [work] ends by clearing the operation. */
    private fun start(step: SetupStep, work: suspend () -> Unit) {
        if (synchronized(lock) { begin(step) }) scope.launch { work() }
    }

    /**
     * Starts [step] with the stored server and topic as they are now, unless busy or no load has
     * worked yet. [work] ends by clearing the operation.
     */
    private fun startWithSettings(step: SetupStep, work: suspend (NtfySettings) -> Unit) {
        val settings = synchronized(lock) {
            val loaded = topic ?: return
            val server = mutableState.value.server
            if (!begin(step)) return
            NtfySettings(server, loaded)
        }
        scope.launch { work(settings) }
    }

    private suspend fun load() {
        var loaded: Pair<NtfyTopic, NtfyServer>? = null
        val failure = storageFailureOf { loaded = readOrMakeTopic() to readServer() }
        val proceed = synchronized(lock) {
            val values = loaded
            if (failure != null || values == null) {
                mutableState.update {
                    it.copy(loading = false, error = SetupError(SetupStep.Load, failure ?: NotifyFailure.Unknown))
                }
                false
            } else {
                val (newTopic, server) = values
                topic = newTopic
                mutableState.update {
                    it.copy(
                        loading = false,
                        server = server,
                        serverText = if (edited) it.serverText else server.url,
                        serverProblem = if (edited) it.serverProblem else null,
                        subscriptionLink = server.subscriptionLink(newTopic),
                        operation = SetupStep.CheckStatus,
                    )
                }
                true
            }
        }
        if (proceed) checkStatus(earlier = null)
    }

    /** The stored topic, or a new one once it is stored. Throws what the store throws. */
    private suspend fun readOrMakeTopic(): NtfyTopic {
        readText(NotifySecrets.TOPIC)?.let { text -> NtfyTopic.of(text)?.let { return it } }
        val made = makeTopic()
        SecretValue.ofText(made.reveal()).use { secrets.put(NotifySecrets.TOPIC, it) }
        return made
    }

    private suspend fun readServer(): NtfyServer =
        readText(NotifySecrets.SERVER)?.let { NtfyServer.parse(it) } ?: NtfyServer.DEFAULT

    /** The text under [ref]; null when there is none, it is unreadable, or it is not UTF-8. */
    private suspend fun readText(ref: SecretRef): String? =
        when (val result = secrets.read(ref)) {
            is SecretReadResult.Found -> result.value.use {
                try {
                    it.revealText()
                } catch (_: CharacterCodingException) {
                    null
                }
            }
            SecretReadResult.NotFound, SecretReadResult.Unreadable -> null
        }

    private fun makeTopic(): NtfyTopic {
        val bytes = ByteArray(NtfyTopic.MIN_LENGTH)
        random.nextBytes(bytes)
        val text = buildString(bytes.size) {
            for (b in bytes) append(NtfyTopic.ALPHABET[b.toInt() and ALPHABET_MASK])
        }
        bytes.fill(0)
        return checkNotNull(NtfyTopic.of(text)) { "Made an invalid topic" }
    }

    /**
     * Stores [server]. Afterwards the field shows it, unless the user typed something else since
     * the step started: that newer text stays, for a save and a reset alike.
     */
    private suspend fun saveServer(server: NtfyServer) {
        val failure = storageFailureOf { SecretValue.ofText(server.url).use { secrets.put(NotifySecrets.SERVER, it) } }
        finish { current ->
            if (failure != null) {
                current.copy(error = SetupError(SetupStep.SaveServer, failure))
            } else {
                // Text typed while saving stays; otherwise the field follows the store again.
                val untouched = edits == editsAtStepStart && current.serverText == textAtStepStart
                val follows = untouched || NtfyServer.parse(current.serverText) == server
                if (follows) edited = false
                current.copy(
                    server = server,
                    serverText = if (follows) server.url else current.serverText,
                    serverProblem = if (follows) null else current.serverProblem,
                    subscriptionLink = topic?.let { server.subscriptionLink(it) },
                )
            }
        }
    }

    /** Reads the status; [earlier] is the failure of the step before, which wins over this one's. */
    private suspend fun checkStatus(earlier: SetupError?) {
        synchronized(lock) { mutableState.update { it.copy(operation = SetupStep.CheckStatus) } }
        var status: NotifyServiceStatus? = null
        val failure = serviceFailureOf { status = service.status() }
        finish {
            it.copy(status = status, error = earlier ?: failure?.let { f -> SetupError(SetupStep.CheckStatus, f) })
        }
    }

    /** Under [lock]: applies [change] and ends the running step. */
    private fun finish(change: (SetupState) -> SetupState) {
        synchronized(lock) { mutableState.update { change(it).copy(operation = null) } }
    }

    /** Runs a call to [service]: a [NotifyException] gives its failure, anything else is unknown. */
    private suspend fun serviceFailureOf(block: suspend () -> Unit): NotifyFailure? =
        failureOf(block) { if (it is NotifyException) it.failure else NotifyFailure.Unknown }

    /** Runs a call to [secrets]: an [IOException] is a storage failure, anything else is unknown. */
    private suspend fun storageFailureOf(block: suspend () -> Unit): NotifyFailure? =
        failureOf(block) { if (it is IOException) NotifyFailure.StorageFailed else NotifyFailure.Unknown }

    /**
     * Runs [block]; null if it worked, or what [map] makes of the exception it threw. A
     * cancellation that is not this coroutine's own is [NotifyFailure.Unknown]; this coroutine's
     * own is rethrown.
     */
    private suspend fun failureOf(block: suspend () -> Unit, map: (Exception) -> NotifyFailure): NotifyFailure? =
        try {
            block()
            null
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            NotifyFailure.Unknown
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            map(e)
        }

    override fun toString(): String = "DefaultSetupPresenter"

    private companion object {
        /** The low six bits of a byte: an index into the 64 characters of [NtfyTopic.ALPHABET]. */
        const val ALPHABET_MASK = 0x3F
    }
}
