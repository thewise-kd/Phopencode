package io.github.thewisekd.phopencode.core.remote.connection

import java.util.logging.Level
import java.util.logging.Logger

/**
 * Whether the app keeps detailed diagnostics of its connection (P9.25). The app turns it on only
 * in a debuggable build, decided at runtime; a release build has it off, and then nothing below
 * records or logs anything.
 *
 * What it adds: [ConnectionManager.lastEnd], a trace of why the connection last ended, logged
 * through [ConnectionEndLog]; and in the folder picker, the full failure chain with messages and
 * stack frames. Such output may hold server addresses, user names and paths; that is accepted,
 * since it stays on the user's own device, in the screen and the device log, and is never sent
 * anywhere. It never holds a password or a private key: the code that makes these traces puts
 * no credential into an exception message, and both the log and the screen redact their whole
 * text by pattern as a further defence (see [DiagnosticText]).
 */
data class Diagnostics(val enabled: Boolean) {
    companion object {
        val Off = Diagnostics(enabled = false)
    }
}

/** Where [ConnectionManager] reports each end of a connection while [Diagnostics] are on. */
fun interface ConnectionEndLog {
    fun ended(trace: Throwable)

    companion object {
        /** The logger's name; on Android, `java.util.logging` reaches Logcat with it as the tag. */
        const val TAG = "phopencode"

        /**
         * Logs through `java.util.logging`, at INFO, under [TAG]: the trace's chain with its frames
         * as [DiagnosticText.chain] writes it, redacted by [DiagnosticText.redact] as a whole. Only
         * that string is logged, never the throwable, whose rendering would skip the redaction.
         */
        val JavaLogging = ConnectionEndLog { trace ->
            val text = DiagnosticText.redact("Connection ended:\n" + DiagnosticText.chain(trace)).trimEnd()
            Logger.getLogger(TAG).log(Level.INFO, text)
        }
    }
}

/**
 * Why a connection ended, made at the moment it ended while [Diagnostics] are on: its stack shows
 * who ended it, and [cause], when set, the failure or cancellation behind it. Its message is fixed
 * text or a [ConnectionError]'s type name, never a credential.
 */
class ConnectionEndTrace(message: String, cause: Throwable? = null) : Exception(message, cause)
