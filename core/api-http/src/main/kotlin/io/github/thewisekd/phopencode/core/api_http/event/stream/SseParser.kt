package io.github.thewisekd.phopencode.core.api_http.event.stream

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException

/** One thing read off an event stream: an event or a comment. */
internal sealed interface SseItem

/**
 * One server-sent event, undecoded. Turning [data] into a `ServerEvent` is the decoder's job.
 *
 * opencode sends neither `event:` nor `id:` lines: its type and id are inside the JSON in [data].
 *
 * @property type The `event:` field, or null when the event had none (the spec then calls it
 *   `message`).
 * @property id The last `id:` field seen on this connection, as the spec defines it; null if none.
 * @property data The `data:` lines joined with `\n`.
 */
internal data class SseEvent(val type: String?, val id: String?, val data: String) : SseItem {
    /** Leaves out [data]: it can hold file content or a key. */
    override fun toString(): String = "SseEvent(type=$type, id=$id, ${data.length} chars)"
}

/** A comment line, such as the server's `: heartbeat`. It only shows that the stream is alive. */
internal data class SseComment(val text: String) : SseItem

/**
 * A line arrived that completed nothing, such as one `data:` line of an event still being sent.
 * Like a comment, it only shows that the stream is alive: a slow event is not silence.
 */
internal data object SseLineRead : SseItem

/**
 * Reads the lines of one connection, as the HTML spec's event stream rules say, and returns an item
 * when a line completes one. Line endings must already be gone; `\r\n` and `\n` are what the reader
 * splits on. A lone `\r` as a line end is not supported: opencode never sends it.
 *
 * `retry:` is ignored: the delay before a reconnect is [ReconnectPolicy]'s. Not thread-safe; one
 * per connection.
 *
 * @param maxEventBytes Largest [SseEvent.data] of one event, in UTF-8 bytes as on the wire, the
 *   joining newlines included: exactly this many pass. A larger one fails with [ApiError.MalformedResponse], so a broken server cannot fill the memory.
 */
internal class SseParser(private val maxEventBytes: Int = DEFAULT_MAX_EVENT_BYTES) {
    private val data = StringBuilder()

    /** UTF-8 size of [data], its final newline included. */
    private var dataBytes = 0L
    private var type: String? = null
    private var lastId: String? = null
    private var firstLine = true

    init {
        require(maxEventBytes > 0) { "maxEventBytes must be positive" }
    }

    /** Takes one line; returns the event or comment it completes, or null. */
    fun feed(rawLine: String): SseItem? {
        val line = if (firstLine) rawLine.removePrefix(BOM) else rawLine
        firstLine = false
        if (line.isEmpty()) return dispatch()
        if (line[0] == ':') return SseComment(line.substring(1).removePrefix(" "))

        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        when (field) {
            "data" -> {
                // The data sent out is the buffer, this value, and no final newline.
                val valueBytes = utf8Size(value)
                if (dataBytes + valueBytes > maxEventBytes) {
                    reset()
                    throw ApiException(ApiError.MalformedResponse)
                }
                data.append(value).append('\n')
                dataBytes += valueBytes + 1
            }
            "event" -> type = value
            "id" -> if ('\u0000' !in value) lastId = value
            else -> Unit // retry, and fields the spec does not know
        }
        return null
    }

    private fun dispatch(): SseEvent? {
        if (data.isEmpty()) {
            type = null
            return null
        }
        val event = SseEvent(type, lastId, data.substring(0, data.length - 1))
        reset()
        return event
    }

    private fun reset() {
        data.setLength(0)
        dataBytes = 0
        type = null
    }

    companion object {
        /** Large enough for a tool reply that quotes a big file. */
        const val DEFAULT_MAX_EVENT_BYTES: Int = 8 * 1024 * 1024

        private const val BOM = "﻿"
    }
}

/**
 * The UTF-8 size of [text], without encoding it. Text read off the wire as UTF-8 holds no lone
 * surrogate; one would count as the 3 bytes of the replacement character, as the encoder writes it.
 */
private fun utf8Size(text: String): Long {
    var bytes = 0L
    var i = 0
    while (i < text.length) {
        val c = text[i]
        bytes += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                i++
                4
            }
            else -> 3
        }
        i++
    }
    return bytes
}
