"""Tests for the notifyd main loop (`notifyd.__main__`) and the zipapp build script.

Every server here is a local fake on 127.0.0.1 with an ephemeral port: no test contacts a real
opencode server or ntfy.sh. Secrets and event text are sentinels assembled at runtime; the tests
check they never reach stderr.
"""
import ast
import contextlib
import importlib
import importlib.util
import io
import http.server
import json
import os
import queue
import shutil
import signal
import socket
import subprocess
import sys
import sysconfig
import tempfile
import threading
import time
import unittest
import zipfile
from pathlib import Path

from notifyd import ntfy, rules, sse

notifyd_main = importlib.import_module("notifyd.__main__")

HELPER = Path(__file__).resolve().parent.parent
PACKAGE = HELPER / "notifyd"
REPO = HELPER.parent
BUILD_SCRIPT = REPO / "scripts" / "build_helper.py"

# Assembled at runtime so that no literal secret sits in the repository.
PASSWORD = "sentinel" + "-pw-" + "M4in"
TOPIC = "SENTINEL" + "_t0p1c_" + "m41n"
CONTENT = "SENTINEL" + "_EVENT_" + "c0ntent"

WAIT = 10.0


def wait_for(predicate, timeout=WAIT, step=0.02):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(step)
    return predicate()


def free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def form_event(session="ses_root", text=CONTENT):
    return {
        "id": "evt_form",
        "type": "form.created",
        "created": 1700000000000,
        "data": {"form": {"id": "frm_1", "sessionID": session, "title": text,
                          "questions": [{"question": text}]}},
        "location": {"directory": "/workspace/" + text},
    }


def permission_event(session="ses_root", text=CONTENT):
    return {
        "id": "evt_perm",
        "type": "permission.asked",
        "data": {"sessionID": session, "permission": text, "patterns": [text]},
    }


class FakeOpencode:
    """A fake `GET /api/event` with Basic auth. Events put on `events` go to the open stream."""

    def __init__(self, password=PASSWORD, port=0):
        self.password = password
        self.events = queue.Queue()
        self.requests = []
        self.statuses = []
        self.lock = threading.Lock()
        self.stopping = threading.Event()
        outer = self

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.0"

            def log_message(self, *args):
                pass

            def do_GET(self):
                import base64
                expected = "Basic " + base64.b64encode(
                    ("opencode:" + outer.password).encode("utf-8")).decode("ascii")
                ok = self.headers.get("Authorization") == expected
                with outer.lock:
                    outer.requests.append((time.monotonic(), self.path))
                    outer.statuses.append(200 if ok else 401)
                if not self.path.startswith("/api/event"):
                    self.send_error(404)
                    return
                if not ok:
                    self.send_response(401)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Cache-Control", "no-cache")
                self.end_headers()
                try:
                    self.wfile.write(b'data: {"id":"evt_0","type":"server.connected","data":{}}\n\n')
                    self.wfile.flush()
                    last_beat = time.monotonic()
                    while not outer.stopping.is_set():
                        try:
                            body = outer.events.get(timeout=0.05)
                        except queue.Empty:
                            body = None
                        if body is not None:
                            self.wfile.write(("data: " + json.dumps(body) + "\n\n").encode("utf-8"))
                            self.wfile.flush()
                        elif time.monotonic() - last_beat > 0.2:
                            self.wfile.write(b": heartbeat\n\n")
                            self.wfile.flush()
                            last_beat = time.monotonic()
                except OSError:
                    pass

        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler)
        self.httpd.daemon_threads = True
        self.thread = threading.Thread(
            target=self.httpd.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        self.thread.start()

    @property
    def port(self):
        return self.httpd.server_address[1]

    @property
    def url(self):
        return "http://127.0.0.1:%d" % self.port

    def connections(self):
        with self.lock:
            return list(self.statuses)

    def close(self):
        self.stopping.set()
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(5)


class FakeNtfy:
    """Records POSTs. `statuses` scripts the answers; `hold` makes the handler wait."""

    def __init__(self):
        self.posts = []
        self.statuses = []
        self.hold = None
        self.lock = threading.Lock()
        outer = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                length = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(length) if length else b""
                with outer.lock:
                    outer.posts.append({
                        "path": self.path,
                        "headers": {k.lower(): v for k, v in self.headers.items()},
                        "body": body.decode("utf-8"),
                    })
                    status = outer.statuses.pop(0) if outer.statuses else 200
                    hold = outer.hold
                if hold is not None:
                    hold.wait(WAIT)
                payload = b'{"id":"x"}'
                try:
                    self.send_response(status)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(payload)))
                    self.end_headers()
                    self.wfile.write(payload)
                except OSError:
                    pass

        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.httpd.daemon_threads = True
        self.thread = threading.Thread(
            target=self.httpd.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        self.thread.start()

    @property
    def url(self):
        return "http://127.0.0.1:%d" % self.httpd.server_address[1]

    def count(self):
        with self.lock:
            return len(self.posts)

    def close(self):
        with self.lock:
            hold = self.hold
        if hold is not None:
            hold.set()
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(5)


def write_config(path, server_url, password=PASSWORD, topic=TOPIC):
    body = json.dumps({
        "server_url": server_url,
        "password": password,
        # A real https URL is required by the config; the test seam replaces the sender, so no
        # request ever goes here.
        "ntfy_server": "https://ntfy.invalid",
        "ntfy_topic": topic,
    }).encode("utf-8")
    tmp = str(path) + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as handle:
        handle.write(body)
    os.chmod(tmp, 0o600)
    os.replace(tmp, str(path))


class MainLoopBase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.config_path = Path(self.tmp.name) / "notifyd.json"
        self.ntfy = FakeNtfy()
        self.addCleanup(self.ntfy.close)
        self.stop = threading.Event()
        self.stderr = io.StringIO()
        self.result = {}
        self.thread = None
        self.server_urls = []

    def opencode(self, **kwargs):
        server = FakeOpencode(**kwargs)
        self.addCleanup(server.close)
        self.server_urls.append(server.url)
        return server

    def client_factory(self, cfg, sleep):
        return sse.SseClient(
            cfg.server_url, cfg.password.reveal(), username=cfg.username,
            types=rules.EVENT_TYPES, timeout=5.0, initial_delay=0.05, max_delay=0.2,
            sleep=sleep)

    def sender_factory(self, cfg, sleep):
        return ntfy.NtfySender(
            cfg.ntfy_topic.reveal(), server=self.ntfy.url, timeout=5.0, attempts=1,
            sleep=sleep, allow_http=ntfy.ALLOW_HTTP_FOR_TESTS_ONLY)

    def start(self, **overrides):
        kwargs = dict(
            client_factory=self.client_factory,
            sender_factory=self.sender_factory,
            rules_factory=lambda: rules.Rules(throttle=None),
            stop_event=self.stop,
            log_stream=self.stderr,
        )
        kwargs.update(overrides)

        def run():
            try:
                self.result["code"] = notifyd_main.main(
                    ["--config", str(self.config_path)], **kwargs)
            except BaseException as error:  # reported by finish()
                self.result["error"] = error

        self.thread = threading.Thread(target=run, daemon=True)
        self.thread.start()
        self.addCleanup(self.finish)

    def finish(self):
        if self.thread is None:
            return None
        self.stop.set()
        self.thread.join(WAIT)
        self.assertFalse(self.thread.is_alive(), "main did not stop")
        self.thread = None
        if "error" in self.result:
            raise self.result["error"]
        self.assert_no_secrets(self.stderr.getvalue())
        return self.result.get("code")

    def assert_no_secrets(self, text):
        self.assertNotIn(PASSWORD, text)
        self.assertNotIn(TOPIC, text)
        self.assertNotIn(TOPIC.lower(), text.lower())
        self.assertNotIn(CONTENT, text)
        self.assertNotIn("Basic ", text)
        for url in self.server_urls + [self.ntfy.url]:
            self.assertNotIn(url, text)
            self.assertNotIn(url.split("//", 1)[1], text)


class MainLoopTest(MainLoopBase):
    def test_form_event_sends_one_fixed_notification(self):
        server = self.opencode()
        write_config(self.config_path, server.url)
        self.start()
        self.assertTrue(wait_for(lambda: server.connections() == [200]))
        server.events.put(form_event())
        self.assertTrue(wait_for(lambda: self.ntfy.count() >= 1))
        time.sleep(0.3)
        self.assertEqual(self.ntfy.count(), 1)
        post = self.ntfy.posts[0]
        self.assertEqual(post["path"], "/" + TOPIC)
        self.assertEqual(post["body"], rules.FORM.body)
        self.assertEqual(post["headers"]["title"], rules.FORM.title)
        self.assertEqual(post["headers"]["priority"], rules.FORM.priority)
        self.assertEqual(post["headers"]["click"], rules.CLICK_URL)
        self.assertNotIn(CONTENT, json.dumps(post))
        self.assertEqual(self.finish(), 0)

    def test_events_without_a_rule_send_nothing(self):
        server = self.opencode()
        write_config(self.config_path, server.url)
        self.start()
        self.assertTrue(wait_for(lambda: server.connections() == [200]))
        server.events.put({"id": "e", "type": "session.execution.started",
                           "data": {"sessionID": "ses_root"}})
        server.events.put(permission_event())
        self.assertTrue(wait_for(lambda: self.ntfy.count() >= 1))
        time.sleep(0.2)
        self.assertEqual(self.ntfy.count(), 1)
        self.assertEqual(self.ntfy.posts[0]["body"], rules.PERMISSION.body)
        self.assertEqual(self.finish(), 0)

    def test_the_stream_asks_for_the_rule_event_types(self):
        server = self.opencode()
        write_config(self.config_path, server.url)
        self.start()
        self.assertTrue(wait_for(lambda: server.connections() == [200]))
        path = server.requests[0][1]
        for event_type in rules.EVENT_TYPES:
            self.assertIn("types=" + event_type, path)
        self.assertEqual(self.finish(), 0)

    def test_failed_send_is_logged_and_the_loop_keeps_going(self):
        server = self.opencode()
        write_config(self.config_path, server.url)
        self.ntfy.statuses = [500]
        self.start()
        self.assertTrue(wait_for(lambda: server.connections() == [200]))
        server.events.put(form_event())
        self.assertTrue(wait_for(lambda: self.ntfy.count() >= 1))
        self.assertTrue(wait_for(lambda: "send failed" in self.stderr.getvalue()))
        server.events.put(permission_event())
        self.assertTrue(wait_for(lambda: self.ntfy.count() >= 2))
        self.assertEqual(self.ntfy.posts[1]["body"], rules.PERMISSION.body)
        self.assertEqual(server.connections(), [200])
        self.assertEqual(self.finish(), 0)

    def test_slow_ntfy_does_not_block_reading_and_a_full_queue_drops(self):
        server = self.opencode()
        write_config(self.config_path, server.url)
        hold = threading.Event()
        self.ntfy.hold = hold
        self.start(queue_size=2)
        self.assertTrue(wait_for(lambda: server.connections() == [200]))
        server.events.put(form_event())
        # The sender is now stuck on the first POST.
        self.assertTrue(wait_for(lambda: self.ntfy.count() == 1))
        for _ in range(5):
            server.events.put(form_event())
        # The reader keeps going: two notifications fill the queue, the other three are dropped.
        self.assertTrue(wait_for(lambda: self.stderr.getvalue().count("dropped") == 3))
        self.assertEqual(self.ntfy.count(), 1)
        hold.set()
        self.assertTrue(wait_for(lambda: self.ntfy.count() == 3))
        time.sleep(0.2)
        self.assertEqual(self.ntfy.count(), 3)
        self.assertEqual(self.finish(), 0)

    def test_waits_while_there_is_no_server_and_connects_when_it_appears(self):
        port = free_port()
        url = "http://127.0.0.1:%d" % port
        self.server_urls.append(url)
        write_config(self.config_path, url)
        self.start()
        time.sleep(0.6)
        self.assertIsNone(self.result.get("code"))
        self.assertTrue(self.thread.is_alive())
        server = self.opencode(port=port)
        self.assertTrue(wait_for(lambda: 200 in server.connections()))
        server.events.put(form_event())
        self.assertTrue(wait_for(lambda: self.ntfy.count() == 1))
        self.assertEqual(self.finish(), 0)

    def test_refused_credentials_wait_and_a_corrected_password_works(self):
        server = self.opencode()
        write_config(self.config_path, server.url, password=PASSWORD + "-wrong")
        self.start(auth_retry_delay=1.0)
        self.assertTrue(wait_for(lambda: server.connections() == [401]))
        first = server.requests[0][0]
        time.sleep(0.5)
        # No hammering: one attempt per long delay, not the fast reconnect backoff.
        self.assertEqual(server.connections(), [401])
        self.assertTrue(self.thread.is_alive())
        self.assertIn("credentials", self.stderr.getvalue())
        write_config(self.config_path, server.url)
        self.assertTrue(wait_for(lambda: 200 in server.connections()))
        self.assertGreaterEqual(server.requests[1][0] - first, 0.9)
        server.events.put(form_event())
        self.assertTrue(wait_for(lambda: self.ntfy.count() == 1))
        self.assertEqual(self.finish(), 0)

    def test_stop_during_the_auth_wait_is_prompt(self):
        server = self.opencode(password=PASSWORD + "-other")
        write_config(self.config_path, server.url)
        self.start(auth_retry_delay=600.0)
        self.assertTrue(wait_for(lambda: server.connections() == [401]))
        started = time.monotonic()
        self.assertEqual(self.finish(), 0)
        self.assertLess(time.monotonic() - started, 3.0)

    def test_config_removed_exits_cleanly_on_the_timer(self):
        server = self.opencode()
        write_config(self.config_path, server.url)
        self.start(config_check_interval=0.2)
        self.assertTrue(wait_for(lambda: server.connections() == [200]))
        os.unlink(str(self.config_path))
        self.assertTrue(wait_for(lambda: not self.thread.is_alive()))
        self.assertEqual(self.result.get("code"), 0)
        self.assertIn("configuration file removed", self.stderr.getvalue())
        self.finish()

    def test_config_removed_exits_cleanly_on_reconnect(self):
        url = "http://127.0.0.1:%d" % free_port()
        self.server_urls.append(url)
        write_config(self.config_path, url)
        self.start(config_check_interval=3600.0)
        time.sleep(0.3)
        os.unlink(str(self.config_path))
        self.assertTrue(wait_for(lambda: not self.thread.is_alive(), timeout=3.0))
        self.assertEqual(self.result.get("code"), 0)
        self.finish()

    def test_config_removed_during_the_auth_wait_exits_cleanly(self):
        server = self.opencode(password=PASSWORD + "-other")
        write_config(self.config_path, server.url)
        self.start(auth_retry_delay=0.3, config_check_interval=3600.0)
        self.assertTrue(wait_for(lambda: server.connections() == [401]))
        os.unlink(str(self.config_path))
        self.assertTrue(wait_for(lambda: not self.thread.is_alive(), timeout=3.0))
        self.assertEqual(self.result.get("code"), 0)
        self.finish()


class StartupTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "notifyd.json"

    def run_main(self, argv):
        err = io.StringIO()
        with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
            code = notifyd_main.main(argv, log_stream=err)
        return code, err.getvalue()

    def test_missing_config_exits_2(self):
        code, err = self.run_main(["--config", str(self.path)])
        self.assertEqual(code, 2)
        self.assertIn("configuration", err)

    def test_bad_mode_exits_2_without_secrets(self):
        write_config(self.path, "http://127.0.0.1:9")
        os.chmod(str(self.path), 0o644)
        code, err = self.run_main(["--config", str(self.path)])
        self.assertEqual(code, 2)
        self.assertNotIn(PASSWORD, err)
        self.assertNotIn(TOPIC, err)

    def test_invalid_config_exits_2_without_secrets(self):
        fd = os.open(str(self.path), os.O_WRONLY | os.O_CREAT, 0o600)
        with os.fdopen(fd, "w") as handle:
            json.dump({"server_url": "http://127.0.0.1:9", "password": PASSWORD,
                       "ntfy_topic": TOPIC + "/bad"}, handle)
        code, err = self.run_main(["--config", str(self.path)])
        self.assertEqual(code, 2)
        self.assertNotIn(PASSWORD, err)
        self.assertNotIn(TOPIC, err)

    def test_help_exits_0(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
            code = notifyd_main.main(["--help"])
        self.assertEqual(code, 0)
        self.assertIn("--config", out.getvalue())

    def test_unknown_argument_exits_2(self):
        code, _ = self.run_main(["--bogus"])
        self.assertEqual(code, 2)


class SendTestTest(unittest.TestCase):
    """`--send-test`: one fixed notification, no opencode connection, no throttle."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "notifyd.json"
        self.ntfy = FakeNtfy()
        self.addCleanup(self.ntfy.close)
        # Nothing listens here; a connection attempt would be counted by the factory below.
        write_config(self.path, "http://127.0.0.1:%d" % free_port())
        self.clients = []

    def client_factory(self, cfg, sleep):
        self.clients.append(cfg)
        raise AssertionError("--send-test must not connect to opencode")

    def sender_factory(self, cfg, sleep):
        return ntfy.NtfySender(
            cfg.ntfy_topic.reveal(), server=self.ntfy.url, timeout=5.0, attempts=1,
            sleep=sleep, allow_http=ntfy.ALLOW_HTTP_FOR_TESTS_ONLY)

    def run_main(self, argv):
        err = io.StringIO()
        with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
            code = notifyd_main.main(
                argv, client_factory=self.client_factory, sender_factory=self.sender_factory,
                log_stream=err)
        text = err.getvalue()
        self.assertNotIn(TOPIC, text)
        self.assertNotIn(TOPIC.lower(), text.lower())
        self.assertNotIn(PASSWORD, text)
        self.assertNotIn(self.ntfy.url.split("//", 1)[1], text)
        return code, text

    def test_sends_one_fixed_test_notification(self):
        code, err = self.run_main(["--config", str(self.path), "--send-test"])
        self.assertEqual(code, notifyd_main.EXIT_OK)
        self.assertEqual(self.ntfy.count(), 1)
        post = self.ntfy.posts[0]
        self.assertEqual(post["path"], "/" + TOPIC)
        self.assertEqual(post["body"], notifyd_main.TEST_NOTIFICATION.body)
        self.assertEqual(post["headers"]["title"], notifyd_main.TEST_NOTIFICATION.title)
        self.assertEqual(post["headers"]["priority"], "default")
        self.assertEqual(post["headers"]["click"], "phopencode://pending")
        self.assertEqual(self.clients, [])
        self.assertIn("test notification sent", err)

    def test_is_not_throttled(self):
        for _ in range(3):
            code, _ = self.run_main(["--config", str(self.path), "--send-test"])
            self.assertEqual(code, 0)
        self.assertEqual(self.ntfy.count(), 3)

    def test_ntfy_failure_has_its_own_exit_code(self):
        self.ntfy.statuses = [500]
        code, err = self.run_main(["--config", str(self.path), "--send-test"])
        self.assertEqual(code, notifyd_main.EXIT_SEND_FAILED)
        self.assertNotIn(code, (0, 1, 2))
        self.assertIn("test notification failed", err)
        self.assertEqual(self.clients, [])

    def test_unreachable_ntfy_fails_with_the_send_code(self):
        self.ntfy.close()
        code, err = self.run_main(["--config", str(self.path), "--send-test"])
        self.assertEqual(code, notifyd_main.EXIT_SEND_FAILED)
        self.assertIn("test notification failed", err)

    def test_config_error_exits_2(self):
        os.unlink(str(self.path))
        code, _ = self.run_main(["--config", str(self.path), "--send-test"])
        self.assertEqual(code, 2)
        self.assertEqual(self.ntfy.count(), 0)

    def test_text_is_fixed_and_safe(self):
        note = notifyd_main.TEST_NOTIFICATION
        self.assertEqual(note.priority, "default")
        self.assertEqual(note.click, rules.CLICK_URL)
        for text in (note.title, note.body):
            self.assertTrue(text)
            text.encode("ascii")


SEND_TEST_CHILD = """
import sys
from notifyd import __main__ as m, ntfy

def sender_factory(cfg, sleep):
    # Many slow retries: without a prompt stop this would run for minutes.
    return ntfy.NtfySender(cfg.ntfy_topic.reveal(), server=sys.argv[2], timeout=60.0,
                           attempts=10, backoff=30.0, max_backoff=30.0, sleep=sleep,
                           allow_http=ntfy.ALLOW_HTTP_FOR_TESTS_ONLY)

sys.exit(m.main(["--config", sys.argv[1], "--send-test"], sender_factory=sender_factory))
"""


class SendTestSignalTest(unittest.TestCase):
    """--send-test in a child process stops promptly on SIGTERM/SIGINT, with no traceback."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.ntfy = FakeNtfy()
        self.addCleanup(self.ntfy.close)
        self.path = Path(self.tmp.name) / "notifyd.json"
        write_config(self.path, "http://127.0.0.1:%d" % free_port())

    def check(self, signum, hang):
        if hang:
            self.ntfy.hold = threading.Event()
        else:
            self.ntfy.statuses = [503] * 10
        env = subprocess_env(self.tmp.name)
        env["PYTHONPATH"] = str(HELPER)
        proc = subprocess.Popen(
            [sys.executable, "-c", SEND_TEST_CHILD, str(self.path), self.ntfy.url],
            env=env, cwd=self.tmp.name, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.addCleanup(SignalTest.reap, proc)
        self.assertTrue(wait_for(lambda: self.ntfy.count() >= 1))
        time.sleep(0.3)  # into the blocked read or the retry wait
        sent = time.monotonic()
        proc.send_signal(signum)
        _, err = proc.communicate(timeout=WAIT)
        text = err.decode("utf-8", "replace")
        self.assertLess(time.monotonic() - sent, 3.0)
        self.assertEqual(proc.returncode, notifyd_main.EXIT_INTERRUPTED, text)
        self.assertNotIn("Traceback", text)
        self.assertIn("test notification interrupted", text)
        self.assertNotIn(TOPIC, text)
        self.assertEqual(self.ntfy.count(), 1)

    def test_sigterm_during_a_hanging_send(self):
        self.check(signal.SIGTERM, hang=True)

    def test_sigint_during_a_hanging_send(self):
        self.check(signal.SIGINT, hang=True)

    def test_sigterm_during_the_retry_wait(self):
        self.check(signal.SIGTERM, hang=False)

    def test_sigint_during_the_retry_wait(self):
        self.check(signal.SIGINT, hang=False)


def subprocess_env(tmp):
    env = {k: v for k, v in os.environ.items()
           if k not in ("PYTHONPATH", "XDG_CONFIG_HOME", "HOME")}
    env["HOME"] = tmp
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    return env


class SignalTest(unittest.TestCase):
    """Runs the real entry point in a child process; the child gets no events, so it never sends."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.server = FakeOpencode()
        self.addCleanup(self.server.close)
        self.config_path = Path(self.tmp.name) / "notifyd.json"
        write_config(self.config_path, self.server.url)

    def spawn(self):
        env = subprocess_env(self.tmp.name)
        env["PYTHONPATH"] = str(HELPER)
        proc = subprocess.Popen(
            [sys.executable, "-m", "notifyd", "--config", str(self.config_path)],
            env=env, cwd=self.tmp.name, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.addCleanup(self.reap, proc)
        return proc

    @staticmethod
    def reap(proc):
        if proc.poll() is None:
            proc.kill()
        proc.communicate(timeout=WAIT)

    def check_signal(self, signum):
        proc = self.spawn()
        self.assertTrue(wait_for(lambda: self.server.connections() == [200]))
        sent = time.monotonic()
        proc.send_signal(signum)
        out, err = proc.communicate(timeout=WAIT)
        self.assertLess(time.monotonic() - sent, 3.0)
        self.assertEqual(proc.returncode, 0, err.decode("utf-8", "replace"))
        text = err.decode("utf-8", "replace")
        self.assertIn("stopped", text)
        for secret in (PASSWORD, TOPIC, self.server.url, "127.0.0.1:%d" % self.server.port):
            self.assertNotIn(secret, text)

    def test_sigterm_exits_0_promptly(self):
        self.check_signal(signal.SIGTERM)

    def test_sigint_exits_0_promptly(self):
        self.check_signal(signal.SIGINT)

    def test_sigterm_while_there_is_no_server(self):
        write_config(self.config_path, "http://127.0.0.1:%d" % free_port())
        proc = self.spawn()
        time.sleep(1.0)
        self.assertIsNone(proc.poll())
        proc.send_signal(signal.SIGTERM)
        proc.communicate(timeout=WAIT)
        self.assertEqual(proc.returncode, 0)


def is_stdlib(module):
    """True if a top-level module name is part of the standard library. Works on Python 3.9.

    sys.stdlib_module_names exists only from 3.10, so the module is looked up instead: it must be
    built in, or load from the interpreter's stdlib directory and not from site-packages.
    """
    if module in sys.builtin_module_names:
        return True
    spec = importlib.util.find_spec(module)
    if spec is None:
        return False
    if spec.origin in (None, "built-in", "frozen"):
        return spec.origin is not None
    origin = os.path.realpath(spec.origin)
    roots = {os.path.realpath(sysconfig.get_paths()[key]) for key in ("stdlib", "platstdlib")}
    if any(part in ("site-packages", "dist-packages") for part in Path(origin).parts):
        return False
    return any(origin.startswith(root + os.sep) for root in roots)


def build(output, *extra):
    return subprocess.run(
        [sys.executable, str(BUILD_SCRIPT), "--output", str(output)] + list(extra),
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=str(REPO), timeout=60)


class BuildTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.out = Path(self.tmp.name) / "out" / "notifyd.pyz"

    def built(self):
        result = build(self.out)
        self.assertEqual(result.returncode, 0, result.stderr.decode("utf-8", "replace"))
        return self.out.read_bytes()

    def test_builds_a_single_executable_file_with_the_shebang(self):
        data = self.built()
        self.assertTrue(data.startswith(b"#!/usr/bin/env python3\n"))
        self.assertTrue(os.access(str(self.out), os.X_OK))
        self.assertEqual(sorted(os.listdir(str(self.out.parent))), ["notifyd.pyz"])

    def test_contains_only_the_package_sources(self):
        self.built()
        with zipfile.ZipFile(str(self.out)) as archive:
            names = archive.namelist()
        expected = ["__main__.py"] + sorted(
            "notifyd/" + p.name for p in PACKAGE.glob("*.py"))
        self.assertEqual(names, sorted(expected))
        for name in names:
            self.assertNotIn("test", name)
            self.assertNotIn("__pycache__", name)
            self.assertTrue(name.endswith(".py"))

    def test_is_reproducible(self):
        first = self.built()
        os.utime(str(PACKAGE / "rules.py"))
        second = self.built()
        self.assertEqual(first, second)

    def test_only_stdlib_imports(self):
        self.built()
        own = {"notifyd"}
        with zipfile.ZipFile(str(self.out)) as archive:
            for name in archive.namelist():
                tree = ast.parse(archive.read(name).decode("utf-8"), filename=name,
                                 feature_version=(3, 9))
                for node in ast.walk(tree):
                    if isinstance(node, ast.Import):
                        modules = [alias.name for alias in node.names]
                    elif isinstance(node, ast.ImportFrom):
                        if node.level:
                            continue
                        modules = [node.module or ""]
                    else:
                        continue
                    for module in modules:
                        top = module.split(".")[0]
                        self.assertTrue(top in own or is_stdlib(top),
                                        "%s imports %s" % (name, module))

    def test_the_stdlib_check_works_without_stdlib_module_names(self):
        for module in ("json", "sys", "http", "zipapp", "_socket", "threading"):
            self.assertTrue(is_stdlib(module), module)
        for module in ("notifyd", "test_main", "no_such_module_x9"):
            self.assertFalse(is_stdlib(module), module)
        names = getattr(sys, "stdlib_module_names", None)
        if names is not None:
            for module in ("json", "socket", "ssl", "urllib", "argparse", "zipfile"):
                self.assertEqual(is_stdlib(module), module in names, module)

    def test_the_archive_runs(self):
        self.built()
        env = subprocess_env(self.tmp.name)
        result = subprocess.run(
            [sys.executable, "-I", str(self.out), "--help"], env=env, cwd=self.tmp.name,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr.decode("utf-8", "replace"))
        self.assertIn(b"--config", result.stdout)
        missing = subprocess.run(
            [sys.executable, "-I", str(self.out), "--config",
             str(Path(self.tmp.name) / "none.json")],
            env=env, cwd=self.tmp.name, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            timeout=30)
        self.assertEqual(missing.returncode, 2)

    def test_the_archive_runs_directly_through_its_shebang(self):
        self.built()
        result = subprocess.run(
            [str(self.out), "--help"], env=subprocess_env(self.tmp.name), cwd=self.tmp.name,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr.decode("utf-8", "replace"))

    def test_refuses_to_write_into_tracked_paths(self):
        for target in (REPO / "helper" / "notifyd.pyz", REPO / "notifyd.pyz",
                       REPO / "scripts" / "out" / "notifyd.pyz"):
            result = build(target)
            self.assertNotEqual(result.returncode, 0, str(target))
            self.assertFalse(target.exists(), str(target))

    def test_default_output_is_under_the_ignored_build_directory(self):
        spec = importlib.util.spec_from_file_location("build_helper_under_test", str(BUILD_SCRIPT))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        default = Path(module.default_output()).resolve()
        self.assertEqual(default.relative_to(REPO.resolve()).parts[0], "build")

    def test_leaves_no_bytecode_or_temp_files(self):
        self.built()
        self.assertEqual(sorted(os.listdir(str(self.out.parent))), ["notifyd.pyz"])


class BuildSymlinkTest(unittest.TestCase):
    """The script runs from a copy in a fake repository, so the real build/ is never touched."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = Path(self.tmp.name) / "repo"
        (self.repo / "scripts").mkdir(parents=True)
        shutil.copy(str(BUILD_SCRIPT), str(self.repo / "scripts" / "build_helper.py"))
        shutil.copytree(str(PACKAGE), str(self.repo / "helper" / "notifyd"),
                        ignore=shutil.ignore_patterns("__pycache__"))
        self.tracked = self.repo / "helper" / "notifyd" / "__init__.py"
        self.original = self.tracked.read_bytes()

    def run_build(self, output):
        return subprocess.run(
            [sys.executable, str(self.repo / "scripts" / "build_helper.py"),
             "--output", str(output)],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=str(self.repo), timeout=60)

    def assert_refused(self, output):
        result = self.run_build(output)
        self.assertEqual(result.returncode, 2, result.stderr.decode("utf-8", "replace"))
        self.assertEqual(self.tracked.read_bytes(), self.original)

    def test_a_real_build_directory_works(self):
        result = self.run_build(self.repo / "build" / "x" / "notifyd.pyz")
        self.assertEqual(result.returncode, 0, result.stderr.decode("utf-8", "replace"))

    def test_refuses_a_build_symlink_into_a_tracked_directory(self):
        os.symlink("helper", str(self.repo / "build"))
        self.assert_refused(self.repo / "build" / "notifyd" / "__init__.py")
        self.assert_refused(self.repo / "build" / "notifyd.pyz")
        self.assertFalse((self.repo / "helper" / "notifyd.pyz").exists())

    def test_refuses_a_build_symlink_that_leaves_the_repository(self):
        outside = Path(self.tmp.name) / "outside"
        outside.mkdir()
        os.symlink(str(outside), str(self.repo / "build"))
        self.assert_refused(self.repo / "build" / "notifyd.pyz")

    def test_refuses_a_symlink_below_build(self):
        (self.repo / "build").mkdir()
        os.symlink(os.path.join("..", "helper"), str(self.repo / "build" / "sub"))
        self.assert_refused(self.repo / "build" / "sub" / "notifyd" / "__init__.py")
        self.assert_refused(self.repo / "build" / "sub" / "notifyd.pyz")

    def test_refuses_an_outside_path_that_resolves_into_the_repository(self):
        link = Path(self.tmp.name) / "link"
        os.symlink(str(self.repo / "helper"), str(link))
        self.assert_refused(link / "notifyd" / "__init__.py")


class SourceTest(unittest.TestCase):
    def sources(self):
        files = sorted(PACKAGE.glob("*.py")) + [BUILD_SCRIPT]
        self.assertIn(PACKAGE / "__main__.py", files)
        return files

    def test_python_3_9_syntax(self):
        for path in self.sources():
            with self.subTest(path=path.name):
                ast.parse(path.read_text(encoding="utf-8"), filename=str(path),
                          feature_version=(3, 9))

    def test_production_code_never_enables_plain_http(self):
        text = (PACKAGE / "__main__.py").read_text(encoding="utf-8")
        self.assertNotIn("ALLOW_HTTP_FOR_TESTS_ONLY", text)
        self.assertNotIn("allow_http", text)

    def test_main_never_reaches_the_local_opencode_port(self):
        for path in (PACKAGE / "__main__.py", BUILD_SCRIPT):
            self.assertNotIn("4096", path.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
