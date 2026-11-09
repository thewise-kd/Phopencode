"""notifyd entry point: reads the opencode event stream and sends fixed-text ntfy notifications.

Usage: `python3 notifyd.pyz [--config PATH] [--send-test]` (or `python3 -m notifyd` from the
source tree). The default config path is `config.default_path()`.

`--send-test` loads the config, sends one fixed test notification (TEST_NOTIFICATION) to the
configured ntfy topic and exits. It does not connect to opencode and is not throttled.

Exit codes:
- 0: stopped by SIGTERM or SIGINT, or the config file was removed (how uninstall stops it).
- 1: internal failure (the event loop ended on its own; should not happen).
- 2: the configuration cannot be loaded at startup, or the command line is invalid.
- 3: `--send-test` only: ntfy did not accept the test notification.
- 4: `--send-test` only: stopped by SIGTERM or SIGINT before the send finished.

Signals: in service mode SIGTERM/SIGINT only set a flag that the main thread polls, and the
shutdown closes the stream. `--send-test` runs a single blocking send on the main thread, so
there the handlers raise an exception instead, which breaks off a blocked read or a retry wait
at once; the send is then reported as interrupted (exit 4) without a traceback.

Threads: the main thread installs the signal handlers, checks the config file on a timer and
runs the shutdown. A reader thread runs SSE -> rules and puts notifications on a small bounded
queue; one sender thread posts them to ntfy. A slow or retrying ntfy server therefore never stops
the event stream from being read; when the queue is full the notification is dropped and a fixed
line is logged. A failed send is logged and the loop goes on.

Waiting: while the server is down the SSE client reconnects with exponential backoff, so the
service simply waits until the server appears. Before each reconnect the reader checks that the
config file still exists.

Refused credentials (HTTP 401/403) and other errors that a quick reconnect cannot fix: the reader
waits AUTH_RETRY_DELAY seconds (interruptible by a signal), reloads the config file and tries
again. A fixed long delay instead of a backoff keeps the server's auth logs quiet and stays
predictable, and reloading lets a corrected password take effect without a restart.

Logs: stderr only, fixed text. Never the password, the topic, the server URL or event content.

Standard library only; Python 3.9 syntax.
"""
import argparse
import logging
import os
import queue
import signal
import sys
import threading
import time

from . import config, ntfy, rules, sse

__all__ = [
    "AUTH_RETRY_DELAY",
    "CONFIG_CHECK_INTERVAL",
    "EXIT_CONFIG",
    "EXIT_FAILURE",
    "EXIT_INTERRUPTED",
    "EXIT_OK",
    "EXIT_SEND_FAILED",
    "QUEUE_SIZE",
    "TEST_NOTIFICATION",
    "main",
]

EXIT_OK = 0
EXIT_FAILURE = 1
EXIT_CONFIG = 2
EXIT_SEND_FAILED = 3
EXIT_INTERRUPTED = 4

TEST_NOTIFICATION = rules.Notification(
    "test",
    "Test notification",
    "Notifications from your opencode server work.",
    "default",
    rules.CLICK_URL,
)

# Seconds to wait after refused credentials or another error that reconnecting cannot fix.
AUTH_RETRY_DELAY = 300.0
# Seconds between checks that the config file still exists.
CONFIG_CHECK_INTERVAL = 30.0
# Notifications waiting for the sender; more are dropped.
QUEUE_SIZE = 16

_POLL = 0.1
_JOIN_TIMEOUT = 5.0

LOG = logging.getLogger("notifyd")

_REASON_SIGNAL = "signal received"
_REASON_REMOVED = "configuration file removed"
_REASON_REQUESTED = "stop requested"


def _default_client(cfg, sleep):
    return sse.SseClient(
        cfg.server_url,
        cfg.password.reveal(),
        username=cfg.username,
        types=rules.EVENT_TYPES,
        sleep=sleep,
    )


def _default_sender(cfg, sleep):
    # HTTPS only: the config accepts only https ntfy servers and plain http is never enabled here.
    return ntfy.NtfySender(cfg.ntfy_topic.reveal(), server=cfg.ntfy_server, sleep=sleep)


class _Service(object):
    """The reader and sender threads. `request_stop` is safe from any thread, not a signal handler."""

    def __init__(self, path, cfg, client_factory, sender_factory, rules_obj, queue_size,
                 auth_retry_delay):
        self._path = path
        self._config = cfg
        self._client_factory = client_factory
        self._sender_factory = sender_factory
        self._rules = rules_obj
        self._auth_retry_delay = float(auth_retry_delay)
        self._stop = threading.Event()
        self._lock = threading.Lock()
        self._client = None
        self._sender = sender_factory(cfg, self._stop.wait)
        self._queue = queue.Queue(maxsize=queue_size)
        self.reason = None
        self._reader = threading.Thread(target=self._read_loop, name="notifyd-reader", daemon=True)
        self._sending = threading.Thread(target=self._send_loop, name="notifyd-sender", daemon=True)

    def start(self):
        self._reader.start()
        self._sending.start()

    @property
    def stopping(self):
        return self._stop.is_set()

    def alive(self):
        return self._reader.is_alive()

    def config_present(self):
        return os.path.lexists(self._path)

    def request_stop(self, reason):
        with self._lock:
            if self.reason is None:
                self.reason = reason
            self._stop.set()
            client = self._client
        if client is not None:
            client.close()

    def join(self, timeout):
        deadline = time.monotonic() + timeout
        for thread in (self._reader, self._sending):
            if thread.is_alive():
                thread.join(max(0.0, deadline - time.monotonic()))
        return not (self._reader.is_alive() or self._sending.is_alive())

    # Reader thread.

    def _read_loop(self):
        while not self._stop.is_set():
            try:
                client = self._client_factory(self._config, self._reconnect_wait)
            except Exception as error:
                LOG.error("cannot set up the event stream (%s); retrying in %.0fs",
                          type(error).__name__, self._auth_retry_delay)
                self._wait_and_reload()
                continue
            with self._lock:
                if self._stop.is_set():
                    client.close()
                    return
                self._client = client
            try:
                for event in client.events():
                    self._dispatch(event)
                continue
            except sse.AuthError:
                LOG.error("server refused the credentials; retrying in %.0fs",
                          self._auth_retry_delay)
            except sse.SseError as error:
                LOG.error("event stream cannot continue (%s); retrying in %.0fs",
                          error, self._auth_retry_delay)
            except Exception as error:
                LOG.error("event loop failed (%s); retrying in %.0fs",
                          type(error).__name__, self._auth_retry_delay)
            finally:
                with self._lock:
                    if self._client is client:
                        self._client = None
                client.close()
            self._wait_and_reload()

    def _reconnect_wait(self, delay):
        """The SSE client's wait between connection attempts."""
        if not self.config_present():
            self.request_stop(_REASON_REMOVED)
            return
        self._stop.wait(delay)

    def _wait_and_reload(self):
        """Waits the long delay, then reloads the config so a corrected password is used."""
        while not self._stop.wait(self._auth_retry_delay):
            if not self.config_present():
                self.request_stop(_REASON_REMOVED)
                return
            try:
                cfg = config.load(self._path)
                sender = self._sender_factory(cfg, self._stop.wait)
            except config.ConfigError as error:
                LOG.error("cannot reload the configuration: %s; retrying in %.0fs",
                          error, self._auth_retry_delay)
                continue
            except Exception as error:
                LOG.error("cannot reload the configuration (%s); retrying in %.0fs",
                          type(error).__name__, self._auth_retry_delay)
                continue
            with self._lock:
                self._config = cfg
                self._sender = sender
            LOG.info("configuration reloaded")
            return

    def _dispatch(self, event):
        notification = self._rules.handle(event.envelope)
        if notification is None:
            return
        try:
            self._queue.put_nowait(notification)
        except queue.Full:
            LOG.warning("notification queue is full; dropped a notification")

    # Sender thread.

    def _send_loop(self):
        while True:
            try:
                notification = self._queue.get(timeout=_POLL)
            except queue.Empty:
                if self._stop.is_set():
                    return
                continue
            if self._stop.is_set():
                return
            with self._lock:
                sender = self._sender
            try:
                sender.send(
                    notification.body,
                    title=notification.title,
                    priority=notification.priority,
                    click=notification.click,
                )
            except ntfy.NtfyError as error:
                if error.status is None:
                    LOG.warning("notification send failed (no answer)")
                else:
                    LOG.warning("notification send failed (HTTP %d)", error.status)
            except Exception as error:
                LOG.warning("notification send failed (%s)", type(error).__name__)
            else:
                LOG.info("notification sent")


def _parser():
    parser = argparse.ArgumentParser(
        prog="notifyd",
        description="Sends a phone notification through ntfy when an opencode server needs "
                    "attention. Runs until SIGTERM or until its config file is removed.",
    )
    parser.add_argument(
        "--config",
        metavar="PATH",
        help="config file (default: $XDG_CONFIG_HOME/phopencode/notifyd.json, "
             "else ~/.config/phopencode/notifyd.json)",
    )
    parser.add_argument(
        "--send-test",
        action="store_true",
        help="send one test notification to the configured ntfy topic and exit",
    )
    return parser


class _Interrupted(BaseException):
    """Raised by the `--send-test` signal handler. A BaseException, so no `except Exception`
    on the way (urllib, ntfy) can swallow it."""


def _install_signals(flag, interrupt=False):
    """Installs SIGTERM/SIGINT handlers; returns the previous handlers.

    By default the handlers only set `flag`. With `interrupt` they raise _Interrupted, once:
    later signals are ignored so the unwinding cannot be broken off again.
    """
    if threading.current_thread() is not threading.main_thread():
        return {}

    def handler(signum, frame):
        # Only a plain assignment: taking locks in a signal handler can deadlock.
        flag[0] = True
        if interrupt:
            for other in (signal.SIGTERM, signal.SIGINT):
                signal.signal(other, signal.SIG_IGN)
            raise _Interrupted()

    previous = {}
    for signum in (signal.SIGTERM, signal.SIGINT):
        previous[signum] = signal.signal(signum, handler)
    return previous


def _restore_signals(previous):
    for signum, handler in previous.items():
        try:
            signal.signal(signum, handler)
        except (ValueError, TypeError, OSError):
            pass


def main(
    argv=None,
    client_factory=None,
    sender_factory=None,
    rules_factory=None,
    stop_event=None,
    log_stream=None,
    queue_size=QUEUE_SIZE,
    auth_retry_delay=AUTH_RETRY_DELAY,
    config_check_interval=CONFIG_CHECK_INTERVAL,
):
    """Runs the service; returns the exit code.

    The keyword arguments are test seams. `client_factory(config, sleep)` returns an object with
    `events()` and `close()`; `sender_factory(config, sleep)` returns one with `send()`;
    `rules_factory()` returns one with `handle(envelope)`; setting `stop_event` stops the service
    like a signal; `log_stream` replaces stderr for logs.
    """
    try:
        args = _parser().parse_args(argv)
    except SystemExit as exit_:
        code = exit_.code
        return code if isinstance(code, int) else EXIT_CONFIG

    handler = logging.StreamHandler(log_stream if log_stream is not None else sys.stderr)
    handler.setFormatter(logging.Formatter("notifyd: %(levelname)s: %(message)s"))
    LOG.addHandler(handler)
    previous_level = LOG.level
    previous_propagate = LOG.propagate
    LOG.setLevel(logging.INFO)
    LOG.propagate = False
    signalled = [False]
    previous_signals = {}
    try:
        previous_signals = _install_signals(signalled, interrupt=args.send_test)
        return _run(
            args,
            client_factory or _default_client,
            sender_factory or _default_sender,
            rules_factory or rules.Rules,
            stop_event,
            queue_size,
            auth_retry_delay,
            config_check_interval,
            signalled,
        )
    except _Interrupted:
        LOG.error("test notification interrupted")
        return EXIT_INTERRUPTED
    finally:
        _restore_signals(previous_signals)
        LOG.removeHandler(handler)
        LOG.setLevel(previous_level)
        LOG.propagate = previous_propagate


def _send_test(cfg, sender_factory):
    """Sends TEST_NOTIFICATION once, without opencode and without the throttle."""
    note = TEST_NOTIFICATION
    try:
        sender = sender_factory(cfg, time.sleep)
        sender.send(note.body, title=note.title, priority=note.priority, click=note.click)
    except ntfy.NtfyError as error:
        if error.status is None:
            LOG.error("test notification failed (no answer)")
        else:
            LOG.error("test notification failed (HTTP %d)", error.status)
        return EXIT_SEND_FAILED
    except Exception as error:
        LOG.error("test notification failed (%s)", type(error).__name__)
        return EXIT_SEND_FAILED
    LOG.info("test notification sent")
    return EXIT_OK


def _run(args, client_factory, sender_factory, rules_factory, stop_event, queue_size,
         auth_retry_delay, config_check_interval, signalled):
    try:
        path = args.config if args.config is not None else os.fspath(config.default_path())
        cfg = config.load(path)
    except config.ConfigError as error:
        LOG.error("cannot load the configuration: %s", error)
        return EXIT_CONFIG

    if args.send_test:
        return _send_test(cfg, sender_factory)

    try:
        service = _Service(path, cfg, client_factory, sender_factory, rules_factory(),
                           queue_size, auth_retry_delay)
    except Exception as error:
        LOG.error("cannot start (%s)", type(error).__name__)
        return EXIT_CONFIG
    service.start()
    LOG.info("notifyd started")

    code = EXIT_OK
    next_check = time.monotonic() + config_check_interval
    while True:
        if signalled[0]:
            service.request_stop(_REASON_SIGNAL)
            break
        if stop_event is not None and stop_event.is_set():
            service.request_stop(_REASON_REQUESTED)
            break
        if service.stopping:
            break
        if not service.alive():
            LOG.error("event loop ended unexpectedly")
            service.request_stop("internal failure")
            code = EXIT_FAILURE
            break
        now = time.monotonic()
        if now >= next_check:
            if not service.config_present():
                service.request_stop(_REASON_REMOVED)
                break
            next_check = now + config_check_interval
        time.sleep(_POLL)

    if not service.join(_JOIN_TIMEOUT):
        LOG.warning("a worker did not stop in time")
    LOG.info("notifyd stopped: %s", service.reason)
    return code


if __name__ == "__main__":
    sys.exit(main())
