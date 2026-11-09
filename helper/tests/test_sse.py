"""Tests for notifyd.sse against a local http.server on 127.0.0.1."""
import ast
import base64
import http.client
import http.server
import io
import json
import logging
import socket
import threading
import time
import unittest
from pathlib import Path
from urllib.parse import parse_qsl, urlsplit

from notifyd import sse

# Assembled at runtime so that no literal secret sits in the repository.
PASSWORD = "sentinel" + "-pw-" + "Q7x" + "\u00e7"
CONTENT = "SENTINEL" + "_CONTENT_" + "9f2"


def envelope(event_type, event_id="evt_1", data=None):
    body = {"id": event_id, "type": event_type, "data": data if data is not None else {}}
    if event_type != "server.connected":
        body["created"] = 1700000000000
        body["location"] = {"directory": "/workspace/project"}
    return body


def data_line(body):
    return "data: " + json.dumps(body) + "\n\n"


CONNECTED = data_line({"id": "evt_0", "type": "server.connected", "data": {}})


class Reply:
    """One scripted answer to one connection."""

    def __init__(self, chunks=(), status=200, content_type="text/event-stream", chunked=False,
                 hold=None, pause=0.0, eof=None):
        self.chunks = [c.encode("utf-8") if isinstance(c, str) else c for c in chunks]
        self.status = status
        self.content_type = content_type
        self.chunked = chunked
        self.hold = hold  # a threading.Event the handler waits on before closing
        self.eof = eof  # a threading.Event set once the client ends the connection
        self.pause = pause  # seconds to wait before writing the chunks


class Server:
    def __init__(self, replies):
        self.replies = list(replies)
        self.requests = []
        self.lock = threading.Lock()
        outer = self

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args):
                pass

            def do_GET(self):
                with outer.lock:
                    outer.requests.append((self.path, dict(self.headers.items())))
                    reply = outer.replies.pop(0) if outer.replies else Reply(status=503)
                self.send_response(reply.status)
                self.send_header("Content-Type", reply.content_type)
                if reply.chunked:
                    self.send_header("Transfer-Encoding", "chunked")
                else:
                    self.send_header("Connection", "close")
                self.end_headers()
                try:
                    if reply.pause:
                        time.sleep(reply.pause)
                    for chunk in reply.chunks:
                        if reply.chunked:
                            self.wfile.write(b"%x\r\n%s\r\n" % (len(chunk), chunk))
                        else:
                            self.wfile.write(chunk)
                        self.wfile.flush()
                    if reply.eof is not None:
                        while self.rfile.read(1):
                            pass
                        reply.eof.set()
                    if reply.hold is not None:
                        reply.hold.wait(10)
                    if reply.chunked:
                        self.wfile.write(b"0\r\n\r\n")
                except OSError:
                    pass
                self.close_connection = True

        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.httpd.daemon_threads = True
        self.url = "http://127.0.0.1:%d" % self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, args=(0.05,), daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *exc):
        self.httpd.shutdown()
        self.httpd.server_close()


class Stop(Exception):
    pass


class FakeSleep:
    """Records each delay; raises Stop once `limit` delays were asked for."""

    def __init__(self, limit, clock=None):
        self.delays = []
        self.limit = limit
        self.clock = clock

    def __call__(self, seconds):
        self.delays.append(seconds)
        if self.clock is not None:
            self.clock.now += seconds
        if len(self.delays) >= self.limit:
            raise Stop()


class FakeClock:
    def __init__(self):
        self.now = 1000.0

    def __call__(self):
        return self.now


def client(url, **kwargs):
    kwargs.setdefault("password", PASSWORD)
    kwargs.setdefault("timeout", 5.0)
    return sse.SseClient(url, **kwargs)


def collect(iterator, count):
    out = []
    for item in iterator:
        out.append(item)
        if len(out) == count:
            break
    return out


def free_port():
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()
    return port


class ParserTest(unittest.TestCase):
    def feed(self, text):
        parser = sse.SseParser()
        items = []
        for line in io.StringIO(text):
            item = parser.feed(line.rstrip("\n").rstrip("\r") if line.endswith("\n") else line)
            if item is not None:
                items.append(item)
        return items

    def test_comment_lines(self):
        items = self.feed(": heartbeat\n\n:\n")
        self.assertEqual([sse.Comment("heartbeat"), sse.Comment("")], items)

    def test_multi_line_data_is_joined(self):
        items = self.feed("data: a\ndata:b\n\n")
        self.assertEqual("a\nb", items[0].data)

    def test_bom_on_first_line_only(self):
        items = self.feed("\ufeffdata: x\n\n")
        self.assertEqual("x", items[0].data)

    def test_event_and_id_fields(self):
        items = self.feed("event: e\nid: 7\ndata: x\n\n")
        self.assertEqual(("e", "7", "x"), (items[0].type, items[0].id, items[0].data))

    def test_blank_line_without_data_dispatches_nothing(self):
        self.assertEqual([], self.feed("event: e\n\nretry: 5\n\n"))

    def test_message_repr_hides_data(self):
        message = sse.SseMessage(None, None, CONTENT)
        self.assertNotIn(CONTENT, repr(message))
        self.assertNotIn(CONTENT, str(message))


class DecodeTest(unittest.TestCase):
    def test_decodes_envelope(self):
        body = envelope("permission.asked", "evt_9", {"sessionID": "ses_1", "x": CONTENT})
        event = sse.decode_event(json.dumps(body))
        self.assertEqual("permission.asked", event.type)
        self.assertEqual("evt_9", event.id)
        self.assertEqual({"sessionID": "ses_1", "x": CONTENT}, event.data)
        self.assertEqual(body, event.envelope)
        self.assertNotIn(CONTENT, repr(event))
        self.assertNotIn(CONTENT, str(event))

    def test_rejects_non_events(self):
        for raw in ["not json " + CONTENT, "[1]", json.dumps({"data": CONTENT}),
                    json.dumps({"type": 5, "data": {}})]:
            self.assertIsNone(sse.decode_event(raw))

    def test_deeply_nested_json_is_skipped(self):
        deep = "[" * 200000 + "]" * 200000
        self.assertIsNone(sse.decode_event(deep))
        deep_object = '{"type": "form.created", "data": ' + "[" * 200000 + "]" * 200000 + "}"
        self.assertIsNone(sse.decode_event(deep_object))

    def test_missing_data_is_empty(self):
        event = sse.decode_event(json.dumps({"id": "e", "type": "server.connected"}))
        self.assertEqual({}, event.data)

    def test_reads_recorded_wire_sample(self):
        root = Path(__file__).resolve().parents[2]
        wire = root / "core/testing/src/main/resources/fixtures/startup/sse-wire.txt"
        if not wire.exists():
            self.skipTest("fixture not present")
        parser = sse.SseParser()
        events, comments = [], 0
        with wire.open(encoding="utf-8") as handle:
            for line in handle:
                item = parser.feed(line.rstrip("\n").rstrip("\r"))
                if isinstance(item, sse.Comment):
                    comments += 1
                elif item is not None:
                    events.append(sse.decode_event(item.data))
        self.assertGreater(comments, 0)
        self.assertEqual("server.connected", events[0].type)
        self.assertTrue(all(e is not None for e in events))


class ClientConnectionTest(unittest.TestCase):
    def test_basic_auth_path_and_headers(self):
        with Server([Reply([CONNECTED])]) as server:
            c = client(server.url)
            events = list(c.connect_once())
            path, headers = server.requests[0]
        self.assertEqual("/api/event", urlsplit(path).path)
        expected = "Basic " + base64.b64encode(("opencode:" + PASSWORD).encode("utf-8")).decode("ascii")
        self.assertEqual(expected, headers["Authorization"])
        self.assertEqual("text/event-stream", headers["Accept"])
        self.assertEqual(["server.connected"], [e.type for e in events])

    def test_custom_username_and_base_path(self):
        with Server([Reply([CONNECTED])]) as server:
            c = client(server.url + "/prefix/", username="u")
            list(c.connect_once())
            path, headers = server.requests[0]
        self.assertEqual("/prefix/api/event", urlsplit(path).path)
        self.assertEqual("Basic " + base64.b64encode(("u:" + PASSWORD).encode("utf-8")).decode(),
                         headers["Authorization"])

    def test_comments_are_skipped_and_events_parsed(self):
        stream = [CONNECTED, ": heartbeat\n\n",
                  "data: " + json.dumps(envelope("session.created", "evt_2")) + "\r\n\r\n",
                  ": heartbeat\n\n",
                  data_line(envelope("form.created", "evt_3"))]
        with Server([Reply(stream)]) as server:
            events = list(client(server.url).connect_once())
        self.assertEqual(["server.connected", "session.created", "form.created"], [e.type for e in events])
        self.assertEqual(["evt_0", "evt_2", "evt_3"], [e.id for e in events])

    def test_chunked_stream_with_split_lines(self):
        line = data_line(envelope("permission.asked", "evt_5"))
        stream = [CONNECTED[:7], CONNECTED[7:], line[:10], line[10:-1], line[-1:]]
        with Server([Reply(stream, chunked=True)]) as server:
            events = list(client(server.url).connect_once())
        self.assertEqual(["server.connected", "permission.asked"], [e.type for e in events])

    def test_cut_off_last_event_is_dropped(self):
        stream = [CONNECTED, "data: " + json.dumps(envelope("form.created"))]
        with Server([Reply(stream)]) as server:
            events = list(client(server.url).connect_once())
        self.assertEqual(["server.connected"], [e.type for e in events])

    def test_malformed_event_is_skipped_without_logging_it(self):
        stream = [CONNECTED, "data: {broken " + CONTENT + "\n\n", data_line(envelope("form.created"))]
        with Server([Reply(stream)]) as server, self.assertLogs("notifyd.sse", logging.DEBUG) as logs:
            events = list(client(server.url).connect_once())
        self.assertEqual(["server.connected", "form.created"], [e.type for e in events])
        self.assertNotIn(CONTENT, "\n".join(logs.output))

    def test_deeply_nested_payload_is_skipped_in_stream(self):
        deep = "data: " + "[" * 200000 + "]" * 200000 + "\n\n"
        stream = [CONNECTED, deep, data_line(envelope("form.created", "evt_after"))]
        with Server([Reply(stream)]) as server:
            events = list(client(server.url).connect_once())
        self.assertEqual(["evt_0", "evt_after"], [e.id for e in events])

    def test_cr_only_line_endings(self):
        body = json.dumps(envelope("form.created", "evt_cr"))
        stream = ["data: " + json.dumps({"id": "evt_0", "type": "server.connected"}) + "\r\r",
                  ": heartbeat\r\r", "data: " + body + "\r\r"]
        with Server([Reply(stream)]) as server:
            events = list(client(server.url).connect_once())
        self.assertEqual(["evt_0", "evt_cr"], [e.id for e in events])

    def test_mixed_line_endings(self):
        stream = ["data: " + json.dumps(envelope("a.b", "evt_1")) + "\r\n\n",
                  "data: " + json.dumps(envelope("a.b", "evt_2")) + "\n\r",
                  "data: " + json.dumps(envelope("a.b", "evt_3")) + "\r\r\n"]
        with Server([Reply(stream)]) as server:
            events = list(client(server.url).connect_once())
        self.assertEqual(["evt_1", "evt_2", "evt_3"], [e.id for e in events])

    def test_crlf_split_across_chunks_is_one_line_end(self):
        # Read as CR then LF, the LF would be a blank line and split the event in two halves
        # that are not valid JSON alone.
        stream = ['data: {"id": "evt_s", "type": "form.created",\r',
                  '\ndata: "data": {}}\r', '\n\r', '\n']
        with Server([Reply(stream, chunked=True)]) as server:
            events = list(client(server.url).connect_once())
        self.assertEqual(["evt_s"], [e.id for e in events])

    def test_oversized_cr_line_fails_the_connection(self):
        big = "data: " + "x" * 300 + "\r\r"
        with Server([Reply([CONNECTED, big])]) as server:
            with self.assertRaises(sse.SseError) as ctx:
                list(client(server.url, max_event_bytes=100).connect_once())
        self.assertTrue(ctx.exception.retryable)

    def test_unterminated_long_line_fails_without_waiting_for_end(self):
        hold = threading.Event()
        with Server([Reply([CONNECTED, "data: " + "x" * 5000], hold=hold)]) as server:
            try:
                with self.assertRaises(sse.SseError) as ctx:
                    list(client(server.url, max_event_bytes=100, timeout=5.0).connect_once())
            finally:
                hold.set()
        self.assertEqual("event too large", ctx.exception.reason)

    def boundary_stream(self, size, bom):
        """A first data line whose payload is exactly `size` UTF-8 bytes."""
        base = envelope("form.created", "evt_b", {"p": ""})
        overhead = len(json.dumps(base).encode("utf-8"))
        # Two-byte characters and one ASCII pad so that byte and character counts differ.
        pad = "\u00e7" * ((size - overhead) // 2) + "x" * ((size - overhead) % 2)
        body = json.dumps(envelope("form.created", "evt_b", {"p": pad}), ensure_ascii=False)
        self.assertEqual(size, len(body.encode("utf-8")))
        return [("\ufeff" if bom else "") + "data: " + body + "\n\n"]

    def test_event_of_exactly_max_bytes_passes(self):
        for bom in (False, True):
            with self.subTest(bom=bom):
                with Server([Reply(self.boundary_stream(400, bom))]) as server:
                    events = list(client(server.url, max_event_bytes=400).connect_once())
                self.assertEqual(["evt_b"], [e.id for e in events])

    def test_event_one_byte_over_max_fails(self):
        for bom in (False, True):
            with self.subTest(bom=bom):
                with Server([Reply(self.boundary_stream(401, bom))]) as server:
                    with self.assertRaises(sse.SseError) as ctx:
                        list(client(server.url, max_event_bytes=400).connect_once())
                self.assertEqual("event too large", ctx.exception.reason)

    def test_multi_line_event_of_exactly_max_bytes_passes(self):
        # One JSON object over two data lines; with the joining newline it is exactly 400 bytes.
        head = '{"id": "evt_m", "type": "form.created", "data":'
        pad = 400 - len(head) - 1 - len('{"p": ""}}')
        tail = '{"p": "' + "y" * pad + '"}}'
        self.assertEqual(400, len(head) + 1 + len(tail))
        stream = ["\ufeffdata: " + head + "\r\ndata: " + tail + "\r\n\r\n"]
        with Server([Reply(stream)]) as server:
            events = list(client(server.url, max_event_bytes=400).connect_once())
        self.assertEqual(["evt_m"], [e.id for e in events])


        stream = [CONNECTED,
                  data_line(envelope("session.text.delta", "evt_1")),
                  data_line(envelope("permission.asked", "evt_2")),
                  data_line(envelope("session.execution.failed", "evt_3")),
                  data_line(envelope("form.created", "evt_4"))]
        wanted = ["permission.asked", "form.created", "session.execution.failed"]
        with Server([Reply(stream)]) as server:
            events = list(client(server.url, types=wanted).connect_once())
            path, _ = server.requests[0]
        query = parse_qsl(urlsplit(path).query)
        self.assertEqual([("types", t) for t in sorted(wanted)], query)
        self.assertEqual(["evt_2", "evt_3", "evt_4"], [e.id for e in events])

    def test_no_types_sends_no_query(self):
        with Server([Reply([CONNECTED])]) as server:
            list(client(server.url).connect_once())
            path, _ = server.requests[0]
        self.assertEqual("/api/event", path)

    def test_401_raises_auth_error_without_secret(self):
        with Server([Reply(status=401, content_type="text/plain")]) as server:
            with self.assertRaises(sse.AuthError) as ctx:
                list(client(server.url).connect_once())
        self.assertEqual(401, ctx.exception.status)
        self.assertFalse(ctx.exception.retryable)
        self.assertNotIn(PASSWORD, str(ctx.exception))
        self.assertNotIn(PASSWORD, repr(ctx.exception))

    def test_server_error_is_retryable(self):
        with Server([Reply(status=503, content_type="text/plain")]) as server:
            with self.assertRaises(sse.SseError) as ctx:
                list(client(server.url).connect_once())
        self.assertTrue(ctx.exception.retryable)
        self.assertEqual(503, ctx.exception.status)

    def test_wrong_content_type_is_retryable(self):
        with Server([Reply(["{}"], content_type="application/json")]) as server:
            with self.assertRaises(sse.SseError) as ctx:
                list(client(server.url).connect_once())
        self.assertTrue(ctx.exception.retryable)

    def test_oversized_event_fails_the_connection(self):
        big = "data: " + "x" * 300 + "\n\n"
        with Server([Reply([CONNECTED, big])]) as server:
            with self.assertRaises(sse.SseError) as ctx:
                list(client(server.url, max_event_bytes=100).connect_once())
        self.assertTrue(ctx.exception.retryable)

    def test_oversized_multi_line_event_fails_the_connection(self):
        big = ("data: " + "x" * 60 + "\n") * 3 + "\n"
        with Server([Reply([CONNECTED, big])]) as server:
            with self.assertRaises(sse.SseError):
                list(client(server.url, max_event_bytes=100).connect_once())

    def test_read_timeout_is_retryable(self):
        hold = threading.Event()
        with Server([Reply([CONNECTED], hold=hold)]) as server:
            try:
                with self.assertRaises(sse.SseError) as ctx:
                    list(client(server.url, timeout=0.3).connect_once())
            finally:
                hold.set()
        self.assertTrue(ctx.exception.retryable)

    def test_connection_refused_is_retryable(self):
        with self.assertRaises(sse.SseError) as ctx:
            list(client("http://127.0.0.1:%d" % free_port()).connect_once())
        self.assertTrue(ctx.exception.retryable)
        self.assertIsNone(ctx.exception.status)


class ConfigValidationTest(unittest.TestCase):
    def test_rejects_credentials_in_url(self):
        with self.assertRaises(ValueError) as ctx:
            sse.SseClient("http://opencode:" + PASSWORD + "@127.0.0.1:1", password=PASSWORD)
        self.assertNotIn(PASSWORD, str(ctx.exception))

    def test_rejects_other_schemes(self):
        for url in ["ftp://127.0.0.1", "127.0.0.1:4", "file:///tmp/x", "http://"]:
            with self.assertRaises(ValueError):
                sse.SseClient(url, password=PASSWORD)

    def test_rejects_query_or_fragment(self):
        for url in ["http://127.0.0.1:1/?a=b", "http://127.0.0.1:1/#x"]:
            with self.assertRaises(ValueError):
                sse.SseClient(url, password=PASSWORD)

    def test_rejects_bad_backoff(self):
        with self.assertRaises(ValueError):
            sse.SseClient("http://127.0.0.1:1", password=PASSWORD, initial_delay=0)
        with self.assertRaises(ValueError):
            sse.SseClient("http://127.0.0.1:1", password=PASSWORD, initial_delay=5, max_delay=1)
        with self.assertRaises(ValueError):
            sse.SseClient("http://127.0.0.1:1", password=PASSWORD, multiplier=0.5)

    def test_repr_hides_password(self):
        c = sse.SseClient("http://127.0.0.1:1", password=PASSWORD)
        self.assertNotIn(PASSWORD, repr(c))
        self.assertNotIn(PASSWORD, str(c))
        encoded = base64.b64encode(("opencode:" + PASSWORD).encode("utf-8")).decode()
        self.assertNotIn(encoded, repr(c))
        self.assertNotIn(encoded, repr(vars(c)) if hasattr(c, "__dict__") else "")


class BackoffTest(unittest.TestCase):
    def test_delays_grow_and_cap(self):
        policy = sse.Backoff(initial_delay=1.0, max_delay=30.0, multiplier=2.0)
        self.assertEqual([1, 2, 4, 8, 16, 30, 30], [policy.delay_for(n) for n in range(1, 8)])

    def test_huge_attempt_stays_capped(self):
        policy = sse.Backoff(initial_delay=1.0, max_delay=30.0, multiplier=2.0)
        self.assertEqual(30.0, policy.delay_for(100000))


class ReconnectTest(unittest.TestCase):
    def test_exponential_reconnect_with_cap(self):
        port = free_port()
        sleep = FakeSleep(limit=7)
        c = client("http://127.0.0.1:%d" % port, sleep=sleep, initial_delay=1.0,
                   max_delay=10.0, multiplier=2.0)
        with self.assertRaises(Stop):
            list(c.events())
        self.assertEqual([1.0, 2.0, 4.0, 8.0, 10.0, 10.0, 10.0], sleep.delays)

    def test_reconnects_after_stream_ends_and_keeps_yielding(self):
        replies = [Reply([CONNECTED, data_line(envelope("form.created", "evt_a"))]),
                   Reply(status=503, content_type="text/plain"),
                   Reply([CONNECTED, data_line(envelope("form.created", "evt_b"))])]
        clock = FakeClock()
        sleep = FakeSleep(limit=100, clock=clock)
        with Server(replies) as server:
            c = client(server.url, types=["form.created"], sleep=sleep, clock=clock)
            events = collect(c.events(), 2)
        self.assertEqual(["evt_a", "evt_b"], [e.id for e in events])
        # Stream ended at once (not healthy): attempt 1, then the 503: attempt 2.
        self.assertEqual([1.0, 2.0], sleep.delays)
        self.assertEqual(3, len(server.requests))

    def test_backoff_resets_after_a_healthy_connection(self):
        clock = FakeClock()

        replies = [Reply(status=503, content_type="text/plain"),
                   Reply(status=503, content_type="text/plain"),
                   Reply([CONNECTED]),
                   Reply(status=503, content_type="text/plain")]
        sleep = FakeSleep(limit=4, clock=clock)
        with Server(replies) as server:
            c = client(server.url, sleep=sleep, clock=clock, healthy_after=10.0)
            original = c.connect_once

            def connect_once():
                for event in original():
                    clock.now += 11.0  # the connection lived long enough
                    yield event

            c.connect_once = connect_once
            with self.assertRaises(Stop):
                list(c.events())
        self.assertEqual([1.0, 2.0, 1.0, 2.0], sleep.delays)

    def test_auth_error_stops_the_loop(self):
        sleep = FakeSleep(limit=5)
        with Server([Reply(status=401, content_type="text/plain")]) as server:
            with self.assertRaises(sse.AuthError):
                list(client(server.url, sleep=sleep).events())
        self.assertEqual([], sleep.delays)

    def test_404_stops_the_loop(self):
        sleep = FakeSleep(limit=5)
        with Server([Reply(status=404, content_type="text/plain")]) as server:
            with self.assertRaises(sse.SseError) as ctx:
                list(client(server.url, sleep=sleep).events())
        self.assertFalse(ctx.exception.retryable)
        self.assertEqual([], sleep.delays)

    def test_429_is_retried(self):
        sleep = FakeSleep(limit=1)
        with Server([Reply(status=429, content_type="text/plain")]) as server:
            with self.assertRaises(Stop):
                list(client(server.url, sleep=sleep).events())
        self.assertEqual([1.0], sleep.delays)

    def test_logs_hold_no_password_or_content(self):
        stream = [CONNECTED, "data: {bad " + CONTENT + "\n\n",
                  data_line(envelope("form.created", "evt_x", {"text": CONTENT}))]
        sleep = FakeSleep(limit=2)
        with Server([Reply(stream), Reply(status=500, content_type="text/plain")]) as server:
            with self.assertLogs("notifyd.sse", logging.DEBUG) as logs:
                with self.assertRaises(Stop):
                    list(client(server.url, sleep=sleep).events())
        joined = "\n".join(logs.output)
        self.assertNotIn(PASSWORD, joined)
        self.assertNotIn(CONTENT, joined)
        self.assertNotIn(base64.b64encode(("opencode:" + PASSWORD).encode("utf-8")).decode(), joined)


class CloseTest(unittest.TestCase):
    def test_close_ends_the_loop_during_a_read(self):
        hold = threading.Event()
        with Server([Reply([CONNECTED], hold=hold)]) as server:
            c = client(server.url, timeout=30.0)
            got = []
            done = threading.Event()

            def run():
                for event in c.events():
                    got.append(event)
                done.set()

            thread = threading.Thread(target=run, daemon=True)
            thread.start()
            deadline = time.monotonic() + 5
            while not got and time.monotonic() < deadline:
                time.sleep(0.01)
            c.close()
            finished = done.wait(5)
            hold.set()
        self.assertTrue(finished)
        self.assertEqual(["server.connected"], [e.type for e in got])

    def test_close_stops_buffered_events(self):
        hold = threading.Event()
        stream = [CONNECTED + data_line(envelope("form.created", "evt_1"))
                  + data_line(envelope("form.created", "evt_2"))]
        with Server([Reply(stream, hold=hold)]) as server:
            try:
                c = client(server.url, timeout=5.0)
                iterator = c.events()
                first = next(iterator)
                c.close()
                rest = list(iterator)
            finally:
                hold.set()
        self.assertEqual("evt_0", first.id)
        self.assertEqual([], rest)

    def test_close_stops_buffered_events_of_one_connection(self):
        hold = threading.Event()
        stream = [CONNECTED + data_line(envelope("form.created", "evt_1"))]
        with Server([Reply(stream, hold=hold)]) as server:
            try:
                c = client(server.url, timeout=5.0)
                iterator = c.connect_once()
                next(iterator)
                c.close()
                rest = list(iterator)
            finally:
                hold.set()
        self.assertEqual([], rest)

    def test_close_releases_a_suspended_iterator_connection(self):
        for chunked in (False, True):
            with self.subTest(chunked=chunked):
                hold, eof = threading.Event(), threading.Event()
                stream = [CONNECTED + data_line(envelope("form.created", "evt_1"))]
                with Server([Reply(stream, hold=hold, eof=eof, chunked=chunked)]) as server:
                    try:
                        c = client(server.url, timeout=5.0)
                        iterator = c.events()
                        next(iterator)  # suspended at its yield from here on
                        sock = c._sock
                        self.assertIsNotNone(sock)
                        c.close()
                        self.assertTrue(eof.wait(5), "the server did not see the connection end")
                        # The descriptor is released at once, not when the iterator resumes.
                        self.assertEqual(-1, sock.fileno())
                        self.assertEqual([], list(iterator))
                    finally:
                        hold.set()


        c = client("http://127.0.0.1:%d" % free_port(), initial_delay=30.0, max_delay=30.0)
        done = threading.Event()

        def run():
            list(c.events())
            done.set()

        thread = threading.Thread(target=run, daemon=True)
        thread.start()
        time.sleep(0.2)
        start = time.monotonic()
        c.close()
        self.assertTrue(done.wait(5))
        self.assertLess(time.monotonic() - start, 5)

    def test_events_after_close_yields_nothing(self):
        c = client("http://127.0.0.1:%d" % free_port())
        c.close()
        self.assertEqual([], list(c.events()))


RACE_STEPS = ("connect", "request", "getresponse", "after_getresponse", "read")
RACE_ERRORS = (None, AttributeError, OSError, ValueError, http.client.HTTPException)


def racing_connection(c, url, step, error):
    """A connection that calls c.close() at `step`, then raises `error` (or carries on).

    It stands in for close() running in another thread at that exact point, deterministically.
    """
    parts = urlsplit(url)

    def race():
        c.close()
        if error is not None:
            raise error("simulated race")

    class RacingResponse(http.client.HTTPResponse):
        def read1(self, n=-1):
            if step == "read":
                race()
            return super().read1(n)

    class RacingConnection(http.client.HTTPConnection):
        response_class = RacingResponse

        def connect(self):
            super().connect()
            if step == "connect":
                race()

        def request(self, *args, **kwargs):
            if step == "request":
                race()
            return super().request(*args, **kwargs)

        def getresponse(self):
            if step == "getresponse":
                race()
            response = super().getresponse()
            if step == "after_getresponse":
                race()
            return response

    return RacingConnection(parts.hostname, parts.port, timeout=5.0)


class CloseRaceTest(unittest.TestCase):
    def run_race(self, step, error, reply):
        sleep = FakeSleep(limit=1)
        with Server([reply]) as server:
            c = client(server.url, sleep=sleep)
            c._new_connection = lambda: racing_connection(c, server.url, step, error)
            events = list(c.events())  # must neither raise nor wait to retry
        self.assertEqual([], sleep.delays)
        self.assertTrue(c.closed)
        return events

    def test_close_at_any_step_ends_events_quietly(self):
        for step in RACE_STEPS:
            for error in RACE_ERRORS:
                with self.subTest(step=step, error=error):
                    events = self.run_race(step, error, Reply([CONNECTED]))
                    self.assertEqual([], events)

    def test_close_during_header_checks_ends_quietly(self):
        for status, content_type in ((401, "text/plain"), (404, "text/plain"), (503, "text/plain"),
                                     (200, "application/json")):
            for error in RACE_ERRORS:
                with self.subTest(status=status, content_type=content_type, error=error):
                    reply = Reply(["{}"], status=status, content_type=content_type)
                    self.assertEqual([], self.run_race("after_getresponse", error, reply))

    def test_close_at_any_step_ends_connect_once_quietly(self):
        for step in RACE_STEPS:
            for error in RACE_ERRORS:
                with self.subTest(step=step, error=error):
                    with Server([Reply([CONNECTED])]) as server:
                        c = client(server.url)
                        c._new_connection = lambda: racing_connection(c, server.url, step, error)
                        self.assertEqual([], list(c.connect_once()))

    def test_errors_while_open_keep_their_handling(self):
        # Without close(), an unexpected error type is not swallowed.
        with Server([Reply([CONNECTED])]) as server:
            c = client(server.url)
            parts = urlsplit(server.url)

            class Broken(http.client.HTTPConnection):
                def request(self, *args, **kwargs):
                    raise AttributeError("simulated bug")

            c._new_connection = lambda: Broken(parts.hostname, parts.port, timeout=5.0)
            with self.assertRaises(AttributeError):
                list(c.connect_once())


class SyntaxTest(unittest.TestCase):
    def test_parses_as_python_3_9(self):
        source = Path(sse.__file__).read_text(encoding="utf-8")
        ast.parse(source, feature_version=(3, 9))


if __name__ == "__main__":
    unittest.main()
