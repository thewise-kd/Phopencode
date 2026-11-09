"""Publishes notifications to an ntfy server. Standard library only.

Uses ntfy's HTTP publish API: `POST <server>/<topic>` with the message as the
body and the `Title`, `Priority` and `Click` headers. The Click URL defaults to
the app's deep link, so tapping the notification opens the pending items.

The topic is a secret: anyone who knows it can read the notifications. It never
appears in logs, `repr`, `str` or exception messages. Errors raised here carry
no chained urllib exception, whose text can contain the URL.

Only HTTPS servers are accepted. Tests that talk to a local `http.server` pass
`allow_http=ALLOW_HTTP_FOR_TESTS_ONLY`; that switch is an object, not a bool, so
`True` or a config value cannot turn it on, and even then only loopback hosts
are accepted.
"""
import base64
import http.client
import ipaddress
import logging
import math
import re
import socket
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request

__all__ = [
    "ALLOW_HTTP_FOR_TESTS_ONLY",
    "DEFAULT_CLICK",
    "DEFAULT_SERVER",
    "NtfyError",
    "NtfySender",
]

DEFAULT_SERVER = "https://ntfy.sh"
DEFAULT_CLICK = "phopencode://pending"

MAX_MESSAGE_BYTES = 4096
MAX_ATTEMPTS = 10

_LOG = logging.getLogger(__name__)

_TOPIC_RE = re.compile(r"[A-Za-z0-9_-]{1,64}\Z")
_PRIORITY_NAMES = frozenset(["min", "low", "default", "high", "max", "urgent"])
_CLICK_RE = re.compile(r"[A-Za-z][A-Za-z0-9+.-]*:\S+\Z")
_CONTROL_RE = re.compile(r"[\x00-\x1f\x7f]")
_LOOPBACK_NAMES = frozenset(["localhost"])


class _TestOnlySwitch(object):
    __slots__ = ()

    def __repr__(self):
        return "ALLOW_HTTP_FOR_TESTS_ONLY"

    def __bool__(self):
        # Never truthy, so `if allow_http:` style checks cannot enable it.
        return False


ALLOW_HTTP_FOR_TESTS_ONLY = _TestOnlySwitch()


class NtfyError(Exception):
    """Publishing failed. The message never contains the topic or the URL.

    `status` is the HTTP status of the last attempt, or None when no HTTP
    response arrived (connection error, timeout, TLS error).
    """

    def __init__(self, message, status=None):
        super(NtfyError, self).__init__(message)
        self.status = status


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    # A redirect could move the topic to another host or downgrade to HTTP.
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def _validate_server(server, allow_http):
    if not isinstance(server, str) or not server:
        raise ValueError("ntfy server URL is missing")
    if _CONTROL_RE.search(server) or " " in server:
        raise ValueError("ntfy server URL contains invalid characters")
    try:
        parts = urllib.parse.urlsplit(server)
        host = parts.hostname
        parts.port  # raises ValueError on a bad port
    except ValueError:
        raise ValueError("ntfy server URL is malformed") from None
    if parts.scheme == "https":
        pass
    elif parts.scheme == "http":
        if allow_http is not ALLOW_HTTP_FOR_TESTS_ONLY:
            raise ValueError("ntfy server URL must use https")
        if not _is_loopback(host):
            raise ValueError("plain http is only allowed for a loopback test server")
    else:
        raise ValueError("ntfy server URL must use https")
    if not host:
        raise ValueError("ntfy server URL has no host")
    if parts.username is not None or parts.password is not None:
        raise ValueError("ntfy server URL must not contain credentials")
    if parts.query or parts.fragment or "?" in server or "#" in server:
        raise ValueError("ntfy server URL must not contain a query or fragment")
    return server.rstrip("/")


def _is_loopback(host):
    if not host:
        return False
    if host.lower() in _LOOPBACK_NAMES:
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def _finite_number(name, value):
    """Returns `value` as a finite float; bools and non-numbers are TypeError."""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise TypeError("%s must be a number" % name)
    try:
        number = float(value)
    except OverflowError:
        raise ValueError("%s must be finite" % name) from None
    if not math.isfinite(number):
        raise ValueError("%s must be finite" % name)
    return number


def _validate_topic(topic):
    if not isinstance(topic, str):
        raise TypeError("ntfy topic must be a string")
    if not _TOPIC_RE.match(topic):
        raise ValueError(
            "ntfy topic must be 1-64 characters of letters, digits, '-' or '_'"
        )
    return topic


def _priority_header(priority):
    if priority is None:
        return None
    if isinstance(priority, bool):
        raise ValueError("ntfy priority must be 1-5 or a priority name")
    if isinstance(priority, int):
        if 1 <= priority <= 5:
            return str(priority)
        raise ValueError("ntfy priority must be 1-5 or a priority name")
    if isinstance(priority, str) and priority in _PRIORITY_NAMES:
        return priority
    raise ValueError("ntfy priority must be 1-5 or a priority name")


def _text_header(name, value):
    if not isinstance(value, str):
        raise TypeError("ntfy %s must be a string" % name)
    if _CONTROL_RE.search(value):
        raise ValueError("ntfy %s must not contain control characters" % name)
    try:
        value.encode("ascii")
        return value
    except UnicodeEncodeError:
        # ntfy decodes RFC 2047 encoded words in headers.
        encoded = base64.b64encode(value.encode("utf-8")).decode("ascii")
        return "=?UTF-8?B?%s?=" % encoded


def _click_header(click):
    if not isinstance(click, str):
        raise TypeError("ntfy click URL must be a string")
    if _CONTROL_RE.search(click) or not _CLICK_RE.match(click):
        raise ValueError("ntfy click URL must be an absolute URL")
    try:
        click.encode("ascii")
    except UnicodeEncodeError:
        raise ValueError("ntfy click URL must be ASCII") from None
    return click


def _retry_after(headers):
    if headers is None:
        return None
    value = headers.get("Retry-After")
    if value is None:
        return None
    try:
        seconds = float(value.strip())
    except ValueError:
        return None
    if seconds != seconds or seconds < 0:
        return None
    return seconds


class NtfySender(object):
    """Sends notifications to one topic on one ntfy server.

    `send` makes up to `attempts` tries. Connection errors, timeouts, HTTP 429
    and 5xx responses are retried after `backoff * 2**n` seconds, capped at
    `max_backoff`; a numeric `Retry-After` on a 429 replaces the computed wait,
    capped the same way. Other responses fail at once. Redirects are not
    followed and proxy environment variables are ignored.
    """

    def __init__(
        self,
        topic,
        server=DEFAULT_SERVER,
        timeout=10.0,
        attempts=3,
        backoff=1.0,
        max_backoff=60.0,
        sleep=time.sleep,
        allow_http=None,
    ):
        self._topic = _validate_topic(topic)
        self._server = _validate_server(server, allow_http)
        if isinstance(attempts, bool) or not isinstance(attempts, int):
            raise TypeError("attempts must be an integer")
        if not 1 <= attempts <= MAX_ATTEMPTS:
            raise ValueError("attempts must be between 1 and %d" % MAX_ATTEMPTS)
        timeout = _finite_number("timeout", timeout)
        if not timeout > 0:
            raise ValueError("timeout must be positive")
        backoff = _finite_number("backoff", backoff)
        if not backoff >= 0:
            raise ValueError("backoff must not be negative")
        max_backoff = _finite_number("max_backoff", max_backoff)
        if not max_backoff >= 0:
            raise ValueError("max_backoff must not be negative")
        self._timeout = float(timeout)
        self._attempts = attempts
        self._backoff = float(backoff)
        self._max_backoff = float(max_backoff)
        self._sleep = sleep
        self._opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}),
            _NoRedirect(),
            urllib.request.HTTPSHandler(context=ssl.create_default_context()),
        )

    @property
    def server(self):
        """The server base URL. Not secret on its own; the topic is."""
        return self._server

    def __repr__(self):
        return "NtfySender(server=%r, topic=<redacted>)" % (self._server,)

    __str__ = __repr__

    def send(self, message, title=None, priority=None, click=DEFAULT_CLICK):
        """Publishes `message`. Returns None or raises NtfyError.

        Invalid arguments raise ValueError or TypeError before any request.
        """
        if not isinstance(message, str):
            raise TypeError("ntfy message must be a string")
        body = message.encode("utf-8")
        if not body:
            raise ValueError("ntfy message must not be empty")
        if len(body) > MAX_MESSAGE_BYTES:
            raise ValueError("ntfy message exceeds %d bytes" % MAX_MESSAGE_BYTES)
        headers = {"Click": _click_header(click)}
        if title is not None:
            headers["Title"] = _text_header("title", title)
        priority_value = _priority_header(priority)
        if priority_value is not None:
            headers["Priority"] = priority_value

        last = None
        for attempt in range(self._attempts):
            if attempt:
                delay = self._delay(attempt, last)
                _LOG.warning(
                    "ntfy publish failed (%s); retry %d of %d in %.1fs",
                    last.args[0],
                    attempt,
                    self._attempts - 1,
                    delay,
                )
                self._sleep(delay)
            error, retryable = self._attempt(body, headers)
            if error is None:
                _LOG.debug("ntfy publish succeeded")
                return None
            last = error
            if not retryable:
                break
        raise NtfyError(
            "ntfy publish failed: %s" % last.args[0], status=last.status
        ) from None

    def _delay(self, attempt, last):
        retry_after = getattr(last, "retry_after", None)
        if retry_after is not None:
            return min(retry_after, self._max_backoff)
        return min(self._backoff * (2 ** (attempt - 1)), self._max_backoff)

    def _attempt(self, body, headers):
        """Returns (None, False) on success or (NtfyError, retryable)."""
        request = urllib.request.Request(
            self._server + "/" + self._topic,
            data=body,
            headers=dict(headers),
            method="POST",
        )
        request.add_header("Content-Type", "text/plain; charset=utf-8")
        try:
            response = self._opener.open(request, timeout=self._timeout)
        except urllib.error.HTTPError as exc:
            status = exc.code
            retry_after = _retry_after(exc.headers) if status == 429 else None
            try:
                exc.close()
            except Exception:
                pass
            error = NtfyError("HTTP %d" % status, status=status)
            error.retry_after = retry_after
            return error, status == 429 or 500 <= status <= 599
        except urllib.error.URLError as exc:
            return NtfyError(_describe(exc.reason)), True
        except (socket.timeout, TimeoutError):
            return NtfyError("timed out"), True
        except (OSError, ValueError, http.client.HTTPException) as exc:
            # Socket errors and http.client errors such as BadStatusLine, which
            # urllib does not wrap in URLError.
            return NtfyError(_describe(exc)), True
        status = response.status
        try:
            with response:
                _drain(response)
        except (OSError, ValueError, http.client.HTTPException) as exc:
            if 200 <= status <= 299:
                # ntfy has accepted the message; a retry would duplicate it.
                _LOG.warning(
                    "ntfy accepted the message (HTTP %d) but reading the reply "
                    "failed (%s); not retrying",
                    status,
                    _describe(exc),
                )
                return None, False
            return NtfyError(_describe(exc), status=status), False
        if 200 <= status <= 299:
            return None, False
        return NtfyError("HTTP %d" % status, status=status), False


def _drain(response):
    """Reads up to MAX_MESSAGE_BYTES of the body and checks it is complete.

    `read(amt)` returns short data at EOF without complaint when a
    Content-Length body is cut off, so the remaining length is checked here.
    A longer body is left unread; closing the response discards it.
    """
    remaining = MAX_MESSAGE_BYTES
    while remaining > 0:
        chunk = response.read(min(remaining, 1024))
        if not chunk:
            if response.length:
                raise http.client.IncompleteRead(b"", response.length)
            return
        remaining -= len(chunk)


def _describe(reason):
    """A fixed description of a network error. Never includes the error text."""
    if isinstance(reason, (socket.timeout, TimeoutError)):
        return "timed out"
    if isinstance(reason, ssl.SSLError):
        return "TLS error"
    if isinstance(reason, ConnectionRefusedError):
        return "connection refused"
    if isinstance(reason, ConnectionError):
        return "connection error"
    if isinstance(reason, socket.gaierror):
        return "name resolution failed"
    if isinstance(reason, http.client.IncompleteRead):
        return "incomplete response"
    if isinstance(reason, http.client.BadStatusLine):
        return "malformed response"
    if isinstance(reason, http.client.HTTPException):
        return "HTTP protocol error"
    return "network error (%s)" % type(reason).__name__
