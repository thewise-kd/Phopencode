"""Client for the event stream of `opencode serve`: `GET /api/event`.

The server sends `data: <json>` lines and `: heartbeat` comments; it sends no `event:` or `id:`
lines. Each JSON payload is an envelope `{id, created?, type, data, location?, metadata?,
durable?}`. The first event of a connection is `server.connected`.

Secrets: the password, the `Authorization` value and the event contents never reach a log line, a
`repr` or an exception message. Logs carry fixed text, HTTP status codes and delays only.

Standard library only; runs on Python 3.9.
"""
import base64
import http.client
import json
import logging
import socket
import ssl
import threading
import time
from typing import Callable, Iterable, Iterator, Optional
from urllib.parse import urlencode, urlsplit

__all__ = [
    "AuthError",
    "Backoff",
    "Comment",
    "Event",
    "SseClient",
    "SseError",
    "SseMessage",
    "SseParser",
    "decode_event",
]

LOG = logging.getLogger("notifyd.sse")
LOG.addHandler(logging.NullHandler())

DEFAULT_USERNAME = "opencode"
EVENT_PATH = "/api/event"
DEFAULT_MAX_EVENT_BYTES = 8 * 1024 * 1024
# Longest line around a largest event: `data: ` (6 bytes) plus a BOM on the first line (3 bytes).
# Line endings are not part of the line the reader measures.
_LINE_OVERHEAD = len("\ufeffdata: ".encode("utf-8"))
_BOM = "\ufeff"
_RETRYABLE_STATUSES = frozenset({408, 429})


class SseError(Exception):
    """A connection failed. The message is fixed text; it holds no secret and no event content.

    `status` is the HTTP status, or None when there was no HTTP answer. `retryable` tells whether
    another connection may get past it.
    """

    def __init__(self, reason: str, status: Optional[int] = None, retryable: bool = True):
        self.reason = reason
        self.status = status
        self.retryable = retryable
        text = reason if status is None else "%s (HTTP %d)" % (reason, status)
        super().__init__(text)

    def __repr__(self):
        return "%s(%r, status=%r, retryable=%r)" % (
            type(self).__name__, self.reason, self.status, self.retryable)


class AuthError(SseError):
    """The server refused the credentials (401 or 403). Retrying would only loop."""

    def __init__(self, status: int):
        super().__init__("server refused the credentials", status=status, retryable=False)


class Comment:
    """A comment line, such as `: heartbeat`. It only shows that the stream is alive."""

    __slots__ = ("text",)

    def __init__(self, text: str):
        self.text = text

    def __eq__(self, other):
        return isinstance(other, Comment) and other.text == self.text

    def __hash__(self):
        return hash(self.text)

    def __repr__(self):
        return "Comment(%r)" % self.text


class SseMessage:
    """One undecoded server-sent event. `data` is the `data:` lines joined with `\\n`."""

    __slots__ = ("type", "id", "data")

    def __init__(self, type: Optional[str], id: Optional[str], data: str):
        self.type = type
        self.id = id
        self.data = data

    def __repr__(self):
        # Leaves out the data: it can hold file content or a key.
        return "SseMessage(type=%r, id=%r, %d chars)" % (self.type, self.id, len(self.data))


class SseParser:
    """Reads the lines of one connection by the HTML event stream rules.

    Lines are given without their line ending. `retry:` is ignored: reconnect delays are the
    client's. One parser per connection.
    """

    def __init__(self, max_event_bytes: int = DEFAULT_MAX_EVENT_BYTES):
        if max_event_bytes <= 0:
            raise ValueError("max_event_bytes must be positive")
        self._max = max_event_bytes
        self._data = []  # type: list
        self._bytes = 0
        self._type = None  # type: Optional[str]
        self._last_id = None  # type: Optional[str]
        self._first = True

    def feed(self, line: str):
        """Takes one line; returns the SseMessage or Comment it completes, or None.

        Raises SseError when one event grows past `max_event_bytes`.
        """
        if self._first:
            self._first = False
            if line.startswith(_BOM):
                line = line[1:]
        if not line:
            return self._dispatch()
        if line[0] == ":":
            text = line[1:]
            return Comment(text[1:] if text.startswith(" ") else text)
        field, colon, value = line.partition(":")
        if colon and value.startswith(" "):
            value = value[1:]
        if field == "data":
            size = len(value.encode("utf-8", "surrogatepass"))
            # The joining newline before this value counts too.
            added = size + (1 if self._data else 0)
            if self._bytes + added > self._max:
                self._reset()
                raise SseError("event too large")
            self._data.append(value)
            self._bytes += added
        elif field == "event":
            self._type = value
        elif field == "id":
            if "\0" not in value:
                self._last_id = value
        return None

    def _dispatch(self):
        if not self._data:
            self._type = None
            return None
        message = SseMessage(self._type, self._last_id, "\n".join(self._data))
        self._reset()
        return message

    def _reset(self):
        self._data = []
        self._bytes = 0
        self._type = None


class Event:
    """One decoded opencode event.

    `type` and `id` come from the envelope, `data` is its `data` object (empty if missing) and
    `envelope` is the whole JSON object. `repr` shows the type and id only.
    """

    __slots__ = ("type", "id", "data", "envelope")

    def __init__(self, type: str, id: Optional[str], data: dict, envelope: dict):
        self.type = type
        self.id = id
        self.data = data
        self.envelope = envelope

    def __repr__(self):
        return "Event(type=%r, id=%r)" % (self.type, self.id)


def decode_event(raw: str) -> Optional[Event]:
    """Decodes one `data:` payload; None if it is not an opencode event envelope."""
    try:
        body = json.loads(raw)
    except (ValueError, RecursionError):
        # RecursionError: nesting too deep for the decoder; a bad payload like any other.
        return None
    if not isinstance(body, dict):
        return None
    event_type = body.get("type")
    if not isinstance(event_type, str) or not event_type:
        return None
    event_id = body.get("id")
    if not isinstance(event_id, str):
        event_id = None
    data = body.get("data")
    if not isinstance(data, dict):
        data = {}
    return Event(event_type, event_id, data, body)


class Backoff:
    """Exponential reconnect delays: `initial_delay * multiplier ** (attempt - 1)`, capped."""

    def __init__(self, initial_delay: float = 1.0, max_delay: float = 30.0, multiplier: float = 2.0):
        if not initial_delay > 0:
            raise ValueError("initial_delay must be positive")
        if not max_delay >= initial_delay:
            raise ValueError("max_delay must not be below initial_delay")
        if not multiplier >= 1.0:
            raise ValueError("multiplier must be at least 1")
        self.initial_delay = float(initial_delay)
        self.max_delay = float(max_delay)
        self.multiplier = float(multiplier)

    def delay_for(self, attempt: int) -> float:
        """The wait after the `attempt`th failure in a row, counting from 1."""
        if attempt < 1:
            raise ValueError("attempt counts from 1")
        delay = self.initial_delay
        for _ in range(attempt - 1):
            delay *= self.multiplier
            if delay >= self.max_delay:
                return self.max_delay
        return min(delay, self.max_delay)

    def __repr__(self):
        return "Backoff(initial_delay=%r, max_delay=%r, multiplier=%r)" % (
            self.initial_delay, self.max_delay, self.multiplier)


class _Secret:
    """Holds the `Authorization` value; prints as `***`."""

    __slots__ = ("value",)

    def __init__(self, value: str):
        self.value = value

    def __repr__(self):
        return "***"

    __str__ = __repr__


class SseClient:
    """Reads `GET /api/event` of one opencode server, with Basic auth and reconnects.

    `base_url` is `http(s)://host:port` with an optional path prefix and no credentials, query or
    fragment. `types` lists the event types wanted: they are sent as `types` query parameters and
    only those are yielded (the server also sends `server.connected`, which is dropped unless
    listed). Empty or None means every type.

    `timeout` is the connect and read timeout in seconds; the server's heartbeats keep a quiet
    stream within it. `sleep` and `clock` are for tests; by default the wait between attempts ends
    early when `close()` is called. A connection that lived at least `healthy_after` seconds resets
    the backoff.
    """

    def __init__(
        self,
        base_url: str,
        password: str,
        username: str = DEFAULT_USERNAME,
        types: Optional[Iterable[str]] = None,
        timeout: float = 60.0,
        initial_delay: float = 1.0,
        max_delay: float = 30.0,
        multiplier: float = 2.0,
        healthy_after: float = 10.0,
        max_event_bytes: int = DEFAULT_MAX_EVENT_BYTES,
        sleep: Optional[Callable[[float], object]] = None,
        clock: Callable[[], float] = time.monotonic,
        ssl_context: Optional[ssl.SSLContext] = None,
    ):
        self._scheme, self._host, self._port, self._prefix = _parse_base_url(base_url)
        if not isinstance(password, str) or not isinstance(username, str):
            raise ValueError("username and password must be text")
        if ":" in username:
            raise ValueError("username must not contain a colon")
        token = base64.b64encode((username + ":" + password).encode("utf-8")).decode("ascii")
        self._auth = _Secret("Basic " + token)
        wanted = sorted(set(types)) if types else []
        if any(not isinstance(t, str) or not t for t in wanted):
            raise ValueError("types must be non-empty strings")
        self._types = frozenset(wanted)
        self._query = urlencode([("types", t) for t in wanted])
        if not timeout > 0:
            raise ValueError("timeout must be positive")
        if healthy_after < 0:
            raise ValueError("healthy_after must not be negative")
        if max_event_bytes <= 0:
            raise ValueError("max_event_bytes must be positive")
        self._timeout = float(timeout)
        self._backoff = Backoff(initial_delay, max_delay, multiplier)
        self._healthy_after = float(healthy_after)
        self._max_event_bytes = max_event_bytes
        self._closed = threading.Event()
        self._sleep = sleep if sleep is not None else self._closed.wait
        self._clock = clock
        self._ssl_context = ssl_context
        self._lock = threading.Lock()
        # The connection being read, so close() can break off a blocked read and release its
        # descriptor even while a consumer holds a suspended iterator. The socket is kept apart:
        # http.client drops conn.sock once a response that closes the connection arrives.
        self._conn = None  # type: Optional[http.client.HTTPConnection]
        self._sock = None  # type: Optional[socket.socket]
        self._response = None  # type: Optional[http.client.HTTPResponse]
        self._opened_at = None  # type: Optional[float]

    def __repr__(self):
        return "SseClient(%s://%s:%d, types=%d, closed=%r)" % (
            self._scheme, self._host, self._port, len(self._types), self._closed.is_set())

    @property
    def closed(self) -> bool:
        return self._closed.is_set()

    def close(self) -> None:
        """Stops `events()`: breaks off a read or a wait in progress. Safe from another thread."""
        self._closed.set()
        with self._lock:
            conn, sock, response = self._conn, self._sock, self._response
        if sock is not None:
            # Wakes a read blocked in another thread before its buffers are closed.
            _shutdown(sock)
        _release(conn, sock, response)

    def events(self) -> Iterator[Event]:
        """Yields wanted events forever, reconnecting with exponential backoff.

        Returns when `close()` was called. Raises AuthError, or SseError with `retryable` false,
        when retrying could not help. Any other failure waits and connects again.
        """
        attempt = 0
        while not self._closed.is_set():
            self._opened_at = None
            try:
                for event in self.connect_once():
                    yield event
                failure = None
            except SseError as error:
                if self._closed.is_set():
                    return
                if not error.retryable:
                    LOG.error("event stream failed: %s", error)
                    raise
                failure = error
            if self._closed.is_set():
                return
            opened = self._opened_at
            if opened is not None and self._clock() - opened >= self._healthy_after:
                attempt = 0
            attempt += 1
            delay = self._backoff.delay_for(attempt)
            if failure is None:
                LOG.info("event stream ended; reconnecting in %.1fs", delay)
            else:
                LOG.warning("event stream failed: %s; reconnecting in %.1fs", failure, delay)
            self._sleep(delay)

    def connect_once(self) -> Iterator[Event]:
        """One connection: yields its wanted events until the server ends the stream.

        Raises SseError (or AuthError) on failure; a stream the server ends cleanly just returns.
        Once `close()` was called it returns quietly instead, whatever failed: closing from another
        thread can break any step in ways http.client reports with any exception type.
        """
        if self._closed.is_set():
            return
        conn = None
        response = None
        try:
            conn = self._new_connection()
            with self._lock:
                self._conn = conn
            response = self._open(conn)
            with self._lock:
                self._response = response
            if self._closed.is_set():
                return
            self._opened_at = self._clock()
            LOG.info("event stream connected")
            parser = SseParser(self._max_event_bytes)
            reader = _LineReader(response, self._max_event_bytes + _LINE_OVERHEAD)
            while True:
                line = reader.read_line()
                if line is None:
                    return
                item = parser.feed(line)
                if not isinstance(item, SseMessage):
                    continue
                event = decode_event(item.data)
                if event is None:
                    LOG.debug("skipped a payload that is not an opencode event")
                    continue
                if self._types and event.type not in self._types:
                    continue
                # Events already buffered must not come out after close().
                if self._closed.is_set():
                    return
                yield event
        except Exception:
            if self._closed.is_set():
                return
            raise
        finally:
            sock = None
            with self._lock:
                if conn is not None and self._conn is conn:
                    sock = self._sock
                    self._conn = self._sock = self._response = None
            _release(conn, sock, response)

    def _new_connection(self) -> http.client.HTTPConnection:
        if self._scheme == "https":
            context = self._ssl_context or ssl.create_default_context()
            return http.client.HTTPSConnection(self._host, self._port, timeout=self._timeout, context=context)
        return http.client.HTTPConnection(self._host, self._port, timeout=self._timeout)

    def _open(self, conn: http.client.HTTPConnection) -> http.client.HTTPResponse:
        path = self._prefix + EVENT_PATH
        if self._query:
            path += "?" + self._query
        headers = {
            "Authorization": self._auth.value,
            "Accept": "text/event-stream",
            "Cache-Control": "no-cache",
        }
        try:
            conn.connect()
            # After close() the connection must fail, not quietly open a new socket.
            conn.auto_open = 0
            with self._lock:
                self._sock = conn.sock
            if self._closed.is_set():
                raise SseError("client closed", retryable=False)
            conn.request("GET", path, headers=headers)
            response = conn.getresponse()
        except (OSError, http.client.HTTPException, ValueError) as error:
            raise _io_error(error) from None
        status = response.status
        if status != 200:
            _discard(response)
            if status in (401, 403):
                raise AuthError(status)
            retryable = status >= 500 or status in _RETRYABLE_STATUSES
            raise SseError("unexpected answer", status=status, retryable=retryable)
        content_type = (response.getheader("Content-Type") or "").split(";")[0].strip().lower()
        if content_type != "text/event-stream":
            _discard(response)
            raise SseError("answer is not an event stream", status=status)
        return response


class _LineReader:
    """Splits a response body into lines ending in CRLF, LF or CR, as the SSE spec says.

    A CR at the end of one read and an LF at the start of the next are one line end. A line
    longer than `limit` bytes fails with SseError as soon as that many bytes arrived without a
    line end. Bytes after the last line end are dropped, as the spec drops a cut-off event.
    """

    _CHUNK = 64 * 1024

    def __init__(self, response: http.client.HTTPResponse, limit: int):
        self._response = response
        self._limit = limit
        self._buffer = b""
        self._scanned = 0  # bytes of the buffer known to hold no line end
        self._skip_lf = False  # the last line ended in CR, so a leading LF belongs to it

    def read_line(self) -> Optional[str]:
        """One line without its ending; None at the end of the body."""
        while True:
            if self._skip_lf and self._buffer:
                if self._buffer[:1] == b"\n":
                    self._buffer = self._buffer[1:]
                self._skip_lf = False
            end = self._find_end()
            if end >= 0:
                line = self._buffer[:end]
                terminator = self._buffer[end:end + 1]
                self._buffer = self._buffer[end + 1:]
                self._scanned = 0
                self._skip_lf = terminator == b"\r"
                return line.decode("utf-8", "replace")
            if len(self._buffer) > self._limit:
                raise SseError("event too large")
            chunk = self._read()
            if not chunk:
                return None
            self._buffer += chunk

    def _find_end(self) -> int:
        buffer = self._buffer
        stop = min(len(buffer), self._limit + 1)
        cr = buffer.find(b"\r", self._scanned, stop)
        lf = buffer.find(b"\n", self._scanned, stop)
        if cr < 0 and lf < 0:
            self._scanned = stop
            return -1
        if cr < 0:
            return lf
        if lf < 0:
            return cr
        return min(cr, lf)

    def _read(self) -> bytes:
        try:
            return self._response.read1(self._CHUNK)
        # AttributeError: close() in another thread can drop the response's file mid-read.
        except (OSError, http.client.HTTPException, ValueError, AttributeError) as error:
            raise _io_error(error) from None


def _parse_base_url(base_url: str):
    """Returns (scheme, host, port, path prefix). Errors never repeat the URL: it may hold a secret."""
    if not isinstance(base_url, str):
        raise ValueError("base_url must be text")
    try:
        parts = urlsplit(base_url)
        port = parts.port
    except ValueError:
        raise ValueError("base_url is not a valid URL") from None
    scheme = parts.scheme.lower()
    if scheme not in ("http", "https"):
        raise ValueError("base_url must use http or https")
    if "@" in parts.netloc:
        raise ValueError("base_url must not hold credentials")
    if not parts.hostname:
        raise ValueError("base_url has no host")
    if parts.query or parts.fragment or "?" in base_url or "#" in base_url:
        raise ValueError("base_url must not have a query or fragment")
    if port is None:
        port = 443 if scheme == "https" else 80
    prefix = parts.path.rstrip("/")
    if any(c.isspace() for c in prefix):
        raise ValueError("base_url path must not hold whitespace")
    return scheme, parts.hostname, port, prefix


def _io_error(error: BaseException) -> SseError:
    if isinstance(error, socket.timeout):
        return SseError("timed out")
    if isinstance(error, ssl.SSLError):
        return SseError("TLS error (%s)" % type(error).__name__)
    if isinstance(error, http.client.HTTPException):
        return SseError("malformed answer (%s)" % type(error).__name__)
    return SseError("network error (%s)" % type(error).__name__)


def _discard(response: http.client.HTTPResponse) -> None:
    try:
        response.close()
    except (OSError, http.client.HTTPException):
        pass


def _release(conn, sock, response) -> None:
    """Closes a response, its connection and socket, so the descriptor is freed. Idempotent.

    Runs while another thread may be closing the same objects, so no failure stops it.
    """
    for closable in (response, conn, sock):
        if closable is None:
            continue
        try:
            closable.close()
        except Exception:
            pass


def _shutdown(sock: socket.socket) -> None:
    try:
        sock.shutdown(socket.SHUT_RDWR)
    except OSError:
        pass
