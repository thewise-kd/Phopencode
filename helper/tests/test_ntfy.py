"""Tests for notifyd.ntfy against a local HTTP server on 127.0.0.1.

No test contacts ntfy.sh or any other real server. The topic is a secret, so
the tests build sentinel topics at runtime and check that they never reach
logs, repr, str or exception text.
"""
import ast
import http.server
import io
import logging
import os
import socket
import threading
import traceback
import unittest

from notifyd import ntfy


def _sentinel_topic():
    # Assembled at runtime so the repository holds no topic-shaped literal.
    return "SENTINEL" + "-" + "t0p1c" + "-" + "9f3a7c"


class _Recorder:
    def __init__(self):
        self.requests = []
        self.statuses = []
        self.delay = 0.0
        self.release = threading.Event()
        self.extra_headers = {}
        self.lock = threading.Lock()


def _make_handler(recorder):
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def _handle(self):
            length = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(length) if length else b""
            with recorder.lock:
                recorder.requests.append(
                    {
                        "method": self.command,
                        "path": self.path,
                        "headers": {k.lower(): v for k, v in self.headers.items()},
                        "body": body,
                    }
                )
                status = recorder.statuses.pop(0) if recorder.statuses else 200
            if recorder.delay:
                recorder.release.wait(recorder.delay)
            payload = b'{"id":"x"}'
            try:
                self.send_response(status)
                for name, value in recorder.extra_headers.items():
                    self.send_header(name, value)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
            except (BrokenPipeError, ConnectionResetError):
                pass

        do_POST = _handle
        do_GET = _handle
        do_PUT = _handle

    return Handler


class _Server:
    def __init__(self):
        self.recorder = _Recorder()
        self.httpd = http.server.ThreadingHTTPServer(
            ("127.0.0.1", 0), _make_handler(self.recorder)
        )
        self.httpd.daemon_threads = True
        self.thread = threading.Thread(
            target=self.httpd.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True
        )
        self.thread.start()

    @property
    def url(self):
        return "http://127.0.0.1:%d" % self.httpd.server_address[1]

    def close(self):
        self.recorder.release.set()
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(5)


class _Sleeps:
    def __init__(self):
        self.calls = []

    def __call__(self, seconds):
        self.calls.append(seconds)


def _free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class SenderTestBase(unittest.TestCase):
    def setUp(self):
        self.server = _Server()
        self.addCleanup(self.server.close)
        self.topic = _sentinel_topic()
        self.sleeps = _Sleeps()

    def sender(self, **kwargs):
        kwargs.setdefault("server", self.server.url)
        kwargs.setdefault("sleep", self.sleeps)
        kwargs.setdefault("timeout", 2.0)
        return ntfy.NtfySender(
            self.topic, allow_http=ntfy.ALLOW_HTTP_FOR_TESTS_ONLY, **kwargs
        )

    def assert_no_secret(self, text):
        self.assertNotIn(self.topic, text)
        self.assertNotIn(self.topic.lower(), text.lower())


class PublishTest(SenderTestBase):
    def test_posts_message_to_topic_path(self):
        self.sender().send("Something needs your answer")
        self.assertEqual(len(self.server.recorder.requests), 1)
        req = self.server.recorder.requests[0]
        self.assertEqual(req["method"], "POST")
        self.assertEqual(req["path"], "/" + self.topic)
        self.assertEqual(req["body"], "Something needs your answer".encode("utf-8"))

    def test_title_priority_and_click_headers(self):
        self.sender().send("body", title="phopencode", priority="high")
        headers = self.server.recorder.requests[0]["headers"]
        self.assertEqual(headers["title"], "phopencode")
        self.assertEqual(headers["priority"], "high")
        self.assertEqual(headers["click"], "phopencode://pending")

    def test_default_click_is_pending_deep_link(self):
        self.assertEqual(ntfy.DEFAULT_CLICK, "phopencode://pending")

    def test_numeric_priority(self):
        self.sender().send("body", priority=5)
        self.assertEqual(self.server.recorder.requests[0]["headers"]["priority"], "5")

    def test_no_title_or_priority_headers_when_not_given(self):
        self.sender().send("body")
        headers = self.server.recorder.requests[0]["headers"]
        self.assertNotIn("title", headers)
        self.assertNotIn("priority", headers)
        self.assertIn("click", headers)

    def test_custom_click(self):
        self.sender().send("body", click="phopencode://pending?x=1")
        headers = self.server.recorder.requests[0]["headers"]
        self.assertEqual(headers["click"], "phopencode://pending?x=1")

    def test_non_ascii_title_is_rfc2047_encoded(self):
        title = "Yanıt bekleniyor"
        self.sender().send("Onay gerekiyor ç", title=title)
        req = self.server.recorder.requests[0]
        raw = req["headers"]["title"]
        self.assertTrue(raw.startswith("=?UTF-8?B?"), raw)
        import email.header

        decoded = email.header.decode_header(raw)
        text = "".join(
            part.decode(charset or "ascii") if isinstance(part, bytes) else part
            for part, charset in decoded
        )
        self.assertEqual(text, title)
        self.assertEqual(req["body"].decode("utf-8"), "Onay gerekiyor ç")

    def test_server_path_prefix_is_kept(self):
        sender = self.sender(server=self.server.url + "/ntfy/")
        sender.send("body")
        self.assertEqual(
            self.server.recorder.requests[0]["path"], "/ntfy/" + self.topic
        )

    def test_invalid_priority_rejected(self):
        for bad in (0, 6, "loud", True, 2.5):
            with self.assertRaises(ValueError):
                self.sender().send("body", priority=bad)
        self.assertEqual(self.server.recorder.requests, [])

    def test_header_injection_rejected(self):
        with self.assertRaises(ValueError):
            self.sender().send("body", title="a\r\nX-Evil: 1")
        with self.assertRaises(ValueError):
            self.sender().send("body", click="phopencode://pending\nX-Evil: 1")
        self.assertEqual(self.server.recorder.requests, [])

    def test_click_must_be_absolute_url(self):
        with self.assertRaises(ValueError):
            self.sender().send("body", click="pending")

    def test_empty_or_oversized_message_rejected(self):
        with self.assertRaises(ValueError):
            self.sender().send("")
        with self.assertRaises(ValueError):
            self.sender().send("x" * 4097)
        self.assertEqual(self.server.recorder.requests, [])


class RetryTest(SenderTestBase):
    def test_retries_server_errors_then_succeeds(self):
        self.server.recorder.statuses = [500, 503]
        self.sender(attempts=3, backoff=0.5).send("body")
        self.assertEqual(len(self.server.recorder.requests), 3)
        self.assertEqual(self.sleeps.calls, [0.5, 1.0])

    def test_gives_up_after_bounded_attempts(self):
        self.server.recorder.statuses = [502, 502, 502, 502, 502]
        with self.assertRaises(ntfy.NtfyError) as ctx:
            self.sender(attempts=3, backoff=1.0).send("body")
        self.assertEqual(len(self.server.recorder.requests), 3)
        self.assertEqual(self.sleeps.calls, [1.0, 2.0])
        self.assertEqual(ctx.exception.status, 502)

    def test_backoff_is_capped(self):
        self.server.recorder.statuses = [500] * 5
        with self.assertRaises(ntfy.NtfyError):
            self.sender(attempts=5, backoff=10.0, max_backoff=15.0).send("body")
        self.assertEqual(self.sleeps.calls, [10.0, 15.0, 15.0, 15.0])

    def test_retries_429_and_honours_retry_after_up_to_cap(self):
        self.server.recorder.statuses = [429]
        self.server.recorder.extra_headers = {"Retry-After": "7"}
        self.sender(attempts=2, backoff=1.0, max_backoff=30.0).send("body")
        self.assertEqual(len(self.server.recorder.requests), 2)
        self.assertEqual(self.sleeps.calls, [7.0])

    def test_retry_after_is_capped(self):
        self.server.recorder.statuses = [429]
        self.server.recorder.extra_headers = {"Retry-After": "100000"}
        self.sender(attempts=2, backoff=1.0, max_backoff=30.0).send("body")
        self.assertEqual(self.sleeps.calls, [30.0])

    def test_client_errors_are_not_retried(self):
        for status in (400, 401, 403, 404, 413):
            self.server.recorder.requests.clear()
            self.server.recorder.statuses = [status]
            with self.assertRaises(ntfy.NtfyError) as ctx:
                self.sender(attempts=3).send("body")
            self.assertEqual(len(self.server.recorder.requests), 1, status)
            self.assertEqual(ctx.exception.status, status)
        self.assertEqual(self.sleeps.calls, [])

    def test_redirect_is_not_followed(self):
        self.server.recorder.statuses = [302]
        self.server.recorder.extra_headers = {"Location": self.server.url + "/other"}
        with self.assertRaises(ntfy.NtfyError):
            self.sender(attempts=3).send("body")
        self.assertEqual(len(self.server.recorder.requests), 1)

    def test_connection_refused_is_retried(self):
        url = "http://127.0.0.1:%d" % _free_port()
        with self.assertRaises(ntfy.NtfyError) as ctx:
            self.sender(server=url, attempts=3, backoff=0.25).send("body")
        self.assertEqual(self.sleeps.calls, [0.25, 0.5])
        self.assertIsNone(ctx.exception.status)

    def test_timeout_is_applied_and_retried(self):
        self.server.recorder.delay = 3.0
        with self.assertRaises(ntfy.NtfyError) as ctx:
            self.sender(attempts=2, timeout=0.2, backoff=0.1).send("body")
        self.assertEqual(len(self.server.recorder.requests), 2)
        self.assertEqual(self.sleeps.calls, [0.1])
        self.assertIn("timed out", str(ctx.exception))

    def test_invalid_retry_settings_rejected(self):
        for kwargs in (
            {"attempts": 0},
            {"attempts": 100},
            {"timeout": 0},
            {"timeout": -1},
            {"backoff": -1},
            {"max_backoff": -1},
        ):
            with self.assertRaises(ValueError, msg=str(kwargs)):
                self.sender(**kwargs)

    def test_non_finite_numbers_rejected(self):
        inf = float("inf")
        nan = float("nan")
        for name in ("timeout", "backoff", "max_backoff"):
            for value in (inf, -inf, nan):
                with self.assertRaises(ValueError, msg="%s=%r" % (name, value)):
                    self.sender(**{name: value})
        self.assertEqual(self.server.recorder.requests, [])

    def test_non_numbers_and_bools_rejected(self):
        for name in ("timeout", "backoff", "max_backoff", "attempts"):
            for value in (True, False, "1", None, [1]):
                with self.assertRaises(TypeError, msg="%s=%r" % (name, value)):
                    self.sender(**{name: value})
        with self.assertRaises(TypeError):
            self.sender(attempts=2.0)

    def test_zero_backoff_and_integer_values_accepted(self):
        self.server.recorder.statuses = [500]
        self.sender(timeout=2, attempts=2, backoff=0, max_backoff=0).send("body")
        self.assertEqual(self.sleeps.calls, [0.0])


class HttpsOnlyTest(unittest.TestCase):
    def setUp(self):
        self.topic = _sentinel_topic()

    def test_default_server_is_https_ntfy(self):
        sender = ntfy.NtfySender(self.topic)
        self.assertEqual(sender.server, "https://ntfy.sh")

    def test_https_server_accepted(self):
        ntfy.NtfySender(self.topic, server="https://ntfy.example.org/")

    def test_http_refused_by_default(self):
        with self.assertRaises(ValueError):
            ntfy.NtfySender(self.topic, server="http://127.0.0.1:1")

    def test_truthy_values_do_not_enable_http(self):
        for value in (True, 1, "yes", object()):
            with self.assertRaises(ValueError):
                ntfy.NtfySender(
                    self.topic, server="http://127.0.0.1:1", allow_http=value
                )

    def test_http_switch_only_allows_loopback(self):
        for url in ("http://ntfy.example.org", "http://192.0.2.1", "http://[::2]"):
            with self.assertRaises(ValueError, msg=url):
                ntfy.NtfySender(
                    self.topic, server=url, allow_http=ntfy.ALLOW_HTTP_FOR_TESTS_ONLY
                )
        for url in ("http://127.0.0.1:8", "http://localhost:8", "http://[::1]:8"):
            ntfy.NtfySender(
                self.topic, server=url, allow_http=ntfy.ALLOW_HTTP_FOR_TESTS_ONLY
            )

    def test_other_schemes_and_malformed_servers_refused(self):
        for url in (
            "ftp://ntfy.example.org",
            "file:///etc/passwd",
            "ntfy.example.org",
            "https://",
            "https://user:pw@ntfy.example.org",
            "https://ntfy.example.org/?q=1",
            "https://ntfy.example.org/#f",
            "https://ntfy.example.org/a b",
            "",
        ):
            with self.assertRaises(ValueError, msg=url):
                ntfy.NtfySender(self.topic, server=url)

    def test_invalid_topics_refused(self):
        for topic in ("", "a/b", "a b", "x" * 65, "a?b", "..", None, 5):
            with self.assertRaises((ValueError, TypeError)):
                ntfy.NtfySender(topic)

    def test_switch_is_not_a_bool(self):
        self.assertNotIsInstance(ntfy.ALLOW_HTTP_FOR_TESTS_ONLY, bool)


class SecrecyTest(SenderTestBase):
    def test_repr_and_str_hide_topic(self):
        sender = self.sender()
        self.assert_no_secret(repr(sender))
        self.assert_no_secret(str(sender))

    def test_validation_errors_hide_topic(self):
        bad = self.topic + "/x"
        try:
            ntfy.NtfySender(bad)
        except ValueError as exc:
            self.assert_no_secret(str(exc))
            self.assert_no_secret(repr(exc))
        else:
            self.fail("expected ValueError")

    def _format(self, exc):
        return "".join(traceback.format_exception(type(exc), exc, exc.__traceback__))

    def test_http_error_hides_topic_everywhere(self):
        self.server.recorder.statuses = [500, 500]
        logger = logging.getLogger("notifyd")
        with self.assertLogs(logger, level="DEBUG") as logs:
            logger.debug("marker")
            with self.assertRaises(ntfy.NtfyError) as ctx:
                self.sender(attempts=2).send("body")
        exc = ctx.exception
        self.assert_no_secret(str(exc))
        self.assert_no_secret(repr(exc))
        self.assert_no_secret(self._format(exc))
        self.assertIsNone(exc.__cause__)
        self.assertTrue(exc.__suppress_context__)
        self.assert_no_secret("\n".join(logs.output))
        self.assertTrue(any("retry" in line.lower() for line in logs.output))

    def test_connection_error_hides_topic_everywhere(self):
        url = "http://127.0.0.1:%d" % _free_port()
        logger = logging.getLogger("notifyd")
        with self.assertLogs(logger, level="DEBUG") as logs:
            logger.debug("marker")
            with self.assertRaises(ntfy.NtfyError) as ctx:
                self.sender(server=url, attempts=2).send("body")
        exc = ctx.exception
        self.assert_no_secret(str(exc))
        self.assert_no_secret(self._format(exc))
        self.assert_no_secret("\n".join(logs.output))

    def test_timeout_error_hides_topic(self):
        self.server.recorder.delay = 3.0
        with self.assertRaises(ntfy.NtfyError) as ctx:
            self.sender(attempts=1, timeout=0.2).send("body")
        self.assert_no_secret(self._format(ctx.exception))

    def test_success_logs_hide_topic(self):
        logger = logging.getLogger("notifyd")
        with self.assertLogs(logger, level="DEBUG") as logs:
            logger.debug("marker")
            self.sender().send("body", title="t")
        self.assert_no_secret("\n".join(logs.output))

    def test_message_body_never_logged(self):
        body = "BODY" + "-SENTINEL-" + "42"
        self.server.recorder.statuses = [500]
        logger = logging.getLogger("notifyd")
        with self.assertLogs(logger, level="DEBUG") as logs:
            logger.debug("marker")
            self.sender(attempts=2).send(body)
        self.assertNotIn(body, "\n".join(logs.output))

    def test_proxy_environment_is_ignored(self):
        # A proxy would see the topic; the sender must talk to the server directly.
        old = {k: os.environ.get(k) for k in ("http_proxy", "HTTP_PROXY", "no_proxy", "NO_PROXY")}
        dead = "http://127.0.0.1:%d" % _free_port()
        os.environ["http_proxy"] = dead
        os.environ["HTTP_PROXY"] = dead
        os.environ.pop("no_proxy", None)
        os.environ.pop("NO_PROXY", None)

        def restore():
            for k, v in old.items():
                if v is None:
                    os.environ.pop(k, None)
                else:
                    os.environ[k] = v

        self.addCleanup(restore)
        self.sender(attempts=1).send("body")
        self.assertEqual(len(self.server.recorder.requests), 1)


class _RawServer:
    """A TCP server on 127.0.0.1 that answers each request with raw bytes.

    `responses` holds one byte string per connection; when it runs out, a valid
    200 response is sent. It exists to send responses http.server cannot.
    """

    OK = (
        b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
        b"Content-Length: 2\r\nConnection: close\r\n\r\n{}"
    )

    def __init__(self, responses):
        self.responses = list(responses)
        self.requests = 0
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(8)
        self.sock.settimeout(0.05)
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self._serve, daemon=True)
        self.thread.start()

    @property
    def url(self):
        return "http://127.0.0.1:%d" % self.sock.getsockname()[1]

    def _read_request(self, conn):
        conn.settimeout(2.0)
        data = b""
        while b"\r\n\r\n" not in data:
            chunk = conn.recv(4096)
            if not chunk:
                return
            data += chunk
        head, _, body = data.partition(b"\r\n\r\n")
        length = 0
        for line in head.split(b"\r\n")[1:]:
            name, _, value = line.partition(b":")
            if name.strip().lower() == b"content-length":
                length = int(value.strip())
        while len(body) < length:
            chunk = conn.recv(4096)
            if not chunk:
                return
            body += chunk

    def _serve(self):
        while not self.stop.is_set():
            try:
                conn, _ = self.sock.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            with conn:
                try:
                    self._read_request(conn)
                    self.requests += 1
                    reply = self.responses.pop(0) if self.responses else self.OK
                    conn.sendall(reply)
                    conn.shutdown(socket.SHUT_WR)
                except OSError:
                    pass

    def close(self):
        self.stop.set()
        self.thread.join(5)
        self.sock.close()


class MalformedResponseTest(unittest.TestCase):
    BAD_STATUS = b"NOT-HTTP garbage\r\n\r\n"
    TRUNCATED_LENGTH = (
        b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
        b"Content-Length: 100\r\nConnection: close\r\n\r\n{\"id\""
    )
    TRUNCATED_CHUNKED = (
        b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
        b"Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
        b"20\r\n{\"id\""
    )

    def setUp(self):
        self.topic = _sentinel_topic()
        self.sleeps = _Sleeps()

    def run_sender(self, responses, attempts):
        server = _RawServer(responses)
        self.addCleanup(server.close)
        sender = ntfy.NtfySender(
            self.topic,
            server=server.url,
            timeout=2.0,
            attempts=attempts,
            backoff=0.5,
            sleep=self.sleeps,
            allow_http=ntfy.ALLOW_HTTP_FOR_TESTS_ONLY,
        )
        return server, sender

    def assert_clean_failure(self, exc):
        text = "".join(traceback.format_exception(type(exc), exc, exc.__traceback__))
        self.assertNotIn(self.topic, text)
        self.assertNotIn("127.0.0.1", str(exc))
        self.assertIsNone(exc.__cause__)
        self.assertTrue(exc.__suppress_context__)
        self.assertIsNone(exc.status)

    def check(self, raw):
        # One bad reply, then success: the bad reply is retried.
        server, sender = self.run_sender([raw], attempts=2)
        sender.send("body")
        self.assertEqual(server.requests, 2)
        self.assertEqual(self.sleeps.calls, [0.5])
        # Only bad replies: send raises NtfyError, never an http.client error.
        self.sleeps.calls.clear()
        server, sender = self.run_sender([raw, raw], attempts=2)
        with self.assertRaises(ntfy.NtfyError) as ctx:
            sender.send("body")
        self.assertEqual(server.requests, 2)
        self.assert_clean_failure(ctx.exception)

    def test_malformed_status_line_is_retried(self):
        self.check(self.BAD_STATUS)

    def check_accepted(self, raw):
        # A 2xx status means ntfy published; a retry could duplicate it. A body
        # cut off after that counts as success with a warning and no retry.
        server, sender = self.run_sender([raw, raw], attempts=3)
        logger = logging.getLogger("notifyd")
        with self.assertLogs(logger, level="WARNING") as logs:
            sender.send("BODY" + "-SENTINEL-" + "7")
        self.assertEqual(server.requests, 1)
        self.assertEqual(self.sleeps.calls, [])
        output = "\n".join(logs.output)
        self.assertNotIn(self.topic, output)
        self.assertNotIn("BODY-SENTINEL-7", output)
        self.assertNotIn("127.0.0.1", output)

    def test_truncated_content_length_2xx_body_is_success(self):
        self.check_accepted(self.TRUNCATED_LENGTH)

    def test_truncated_chunked_2xx_body_is_success(self):
        self.check_accepted(self.TRUNCATED_CHUNKED)


class SourceTest(unittest.TestCase):
    def test_parses_as_python_3_9(self):
        path = ntfy.__file__
        with io.open(path, encoding="utf-8") as handle:
            ast.parse(handle.read(), filename=path, feature_version=(3, 9))

    def test_standard_library_only(self):
        import sys

        path = ntfy.__file__
        with io.open(path, encoding="utf-8") as handle:
            tree = ast.parse(handle.read())
        names = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                names.update(alias.name.split(".")[0] for alias in node.names)
            elif isinstance(node, ast.ImportFrom) and node.level == 0:
                names.add(node.module.split(".")[0])
        stdlib = getattr(sys, "stdlib_module_names", None)
        if stdlib is None:
            self.skipTest("sys.stdlib_module_names needs Python 3.10+")
        self.assertEqual(names - set(stdlib) - {"__future__"}, set())


if __name__ == "__main__":
    unittest.main()
