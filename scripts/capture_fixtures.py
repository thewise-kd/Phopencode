#!/usr/bin/env python3
"""Records opencode API samples without calling a real model. Standard library only.

Modes:
  capture_fixtures.py                 record every flow into the fixtures folder
  capture_fixtures.py --flows a,b     record only the named flows (for development)
  capture_fixtures.py --check         scan fixtures and docs/api for leaks and broken files
  capture_fixtures.py --self-test     run the masker and checker tests in this file

How a recording stays offline. The script re-runs itself inside an empty network
namespace (`unshare -rn`), where only loopback exists, and refuses to record if
any route is present. It starts `opencode serve` with OPENCODE_SIMULATE=1 and
OPENCODE_DRIVE=<instance>. In that mode the server replaces its HTTP client: a
request to the OpenAI chat URL goes to a loopback WebSocket that this script
answers, and every other destination is denied. Model output is therefore
scripted here, while the session loop, tools, forms, permissions, storage and
event stream are the real server code.

The server gets a temporary HOME, XDG folders and project, an environment built
from an allow-list so no provider key can be inherited, and a password generated
at runtime. It binds to 127.0.0.1 and is stopped and verified gone at the end.

Masking works on the raw text, not on parsed JSON, so key order and number
spelling stay exactly what the server sent. Numbering follows first appearance
in recording order, and masks keep the length and prefix of the original.
"""
import argparse
import base64
import contextlib
import datetime
import http.client
import io
import ipaddress
import json
import os
import pwd
import queue
import re
import secrets
import shutil
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.parse
from pathlib import Path
from types import SimpleNamespace as types_namespace
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = ROOT / "core/testing/src/main/resources/fixtures"
DOCS_DIR = ROOT / "docs/api"
NETNS_MARKER = "PHOPENCODE_CAPTURE_NETNS"

MASK_EPOCH_MS = 1_700_000_000_000
MASK_PROJECT = "/workspace/project"
MASK_HOME = "/home/fixture"
MASK_USER = "fixture"
MASK_HOST = "fixture-host"
MASK_PORT = 4096
MASK_PID = 4242
DAY_MS = 86_400_000
EXECUTION_ENDS = ("session.execution.succeeded", "session.execution.failed", "session.execution.interrupted")

ID_TOKEN = re.compile(r"(?<![A-Za-z0-9_])([a-z]{2,6})_([0-9A-Za-z]{14,})(?![0-9A-Za-z_])")
HASH_TOKEN = re.compile(r"(?<![0-9A-Za-z_])[0-9a-f]{32,64}(?![0-9A-Za-z_])")
CURSOR_TOKEN = re.compile(r"(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{8,}={0,2}")
EPOCH_MS_TOKEN = re.compile(r"(?<![0-9.])1[0-9]{12}(?![0-9])")
ISO_TOKEN = re.compile(r"\b20[0-9]{2}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,3})?Z\b")
IPV4_TOKEN = re.compile(r"(?<![0-9.])(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?![0-9.])")
MASKED_ID_BODY = re.compile(r"^0+[0-9]{1,6}$")
MASKED_HASH = re.compile(r"^0+[0-9a-f]{1,6}$")
AUTH_VALUE = re.compile(r'"[Aa]uthorization"\s*:\s*"|\bBasic [A-Za-z0-9+/]{4,}={0,2}')
TAILSCALE_NET = ipaddress.ip_network("100.64.0.0/10")
# Word masking would corrupt text if a machine name is one of these: "root session"
# is normal server output. Recording refuses such names, so no fixture holds them as identity.
COMMON_NAMES = frozenset({
    "admin", "administrator", "default", "guest", "host", "local", "localhost", "opencode",
    "pi", "root", "server", "session", "test", "ubuntu", "user",
})


def word(value):
    """Matches `value` as a whole word; a hyphen counts as part of a word."""
    return re.compile(r"(?<![\w-])" + re.escape(value) + r"(?![\w-])")


def decode_cursor(token):
    """Returns the JSON text inside a base64url cursor, or None if it is not one."""
    try:
        text = base64.urlsafe_b64decode(token + "=" * (-len(token) % 4)).decode("utf-8")
        json.loads(text)
    except (ValueError, UnicodeDecodeError):
        return None
    return text


class Masker:
    """Replaces what identifies this machine or run. Consistent for one instance."""

    def __init__(self, paths, secrets, run_start_ms, run_end_ms, username, hostname, ports=()):
        table = dict(paths)
        table.setdefault("/home/" + username, MASK_HOME)
        for real, fake in list(table.items()):
            table[urllib.parse.quote(real, safe="")] = urllib.parse.quote(fake, safe="")
        self.paths = table
        # One pass, longest first: a mask must never be matched again, and `/work`
        # must not match inside `/workspace`.
        self.path_token = re.compile(
            "(?:" + "|".join(re.escape(p) for p in sorted(table, key=len, reverse=True)) + r")(?![\w-])")
        self.secrets = []
        for value in secrets:
            self.secrets.append(value)
            self.secrets.append(base64.b64encode(("opencode:" + value).encode()).decode())
        self.window = (run_start_ms - DAY_MS, run_end_ms + DAY_MS)
        self.shift = MASK_EPOCH_MS - run_start_ms
        self.username = word(username)
        self.hostname = word(hostname)
        self.hostname_field = '"hostname":"%s"' % hostname
        self.ports = [re.compile(r"127\.0\.0\.1:%d\b" % port) for port in ports]
        self.ids = {}
        self.hashes = {}
        self.slugs = {}
        self.counters = {}

    def _next(self, key):
        self.counters[key] = self.counters.get(key, 0) + 1
        return self.counters[key]

    def _id(self, match):
        prefix, body = match.group(1), match.group(2)
        if not any(c.isdigit() for c in body) or MASKED_ID_BODY.match(body):
            return match.group(0)
        token = match.group(0)
        if token not in self.ids:
            self.ids[token] = "%s_%s" % (prefix, str(self._next(prefix)).zfill(len(body)))
        return self.ids[token]

    def _hash(self, match):
        token = match.group(0)
        if MASKED_HASH.match(token):
            return token
        if token not in self.hashes:
            self.hashes[token] = format(self._next("hash"), "x").zfill(len(token))
        return self.hashes[token]

    def _slug(self, match):
        value = match.group(1)
        if value.startswith("slug-"):
            return match.group(0)
        if value not in self.slugs:
            self.slugs[value] = "slug-%04d" % self._next("slug")
        return '"slug":"%s"' % self.slugs[value]

    def _epoch(self, match):
        value = int(match.group(0))
        if self.window[0] <= value <= self.window[1]:
            return str(value + self.shift)
        return match.group(0)

    def _iso(self, match):
        text = match.group(0)
        moment = datetime.datetime.fromisoformat(text.replace("Z", "+00:00"))
        value = int(moment.timestamp() * 1000)
        if not self.window[0] <= value <= self.window[1]:
            return text
        shifted = datetime.datetime.fromtimestamp((value + self.shift) / 1000, datetime.timezone.utc)
        return shifted.strftime("%Y-%m-%dT%H:%M:%S.") + "%03dZ" % (shifted.microsecond // 1000)

    def _cursor(self, match):
        inner = decode_cursor(match.group(0))
        if inner is None:
            return match.group(0)
        return base64.urlsafe_b64encode(self.mask(inner).encode()).rstrip(b"=").decode()

    def mask_static(self, text):
        """For the spec: a document from the binary, whose words are vocabulary, not identity."""
        for value in self.secrets:
            text = text.replace(value, "<redacted>")
        return self.path_token.sub(lambda match: self.paths[match.group(0)], text)

    def mask(self, text):
        for value in self.secrets:
            text = text.replace(value, "<redacted>")
        text = CURSOR_TOKEN.sub(self._cursor, text)
        text = self.path_token.sub(lambda match: self.paths[match.group(0)], text)
        text = text.replace(self.hostname_field, '"hostname":"%s"' % MASK_HOST)
        text = self.username.sub(MASK_USER, text)
        text = self.hostname.sub(MASK_HOST, text)
        text = ID_TOKEN.sub(self._id, text)
        text = HASH_TOKEN.sub(self._hash, text)
        text = EPOCH_MS_TOKEN.sub(self._epoch, text)
        text = ISO_TOKEN.sub(self._iso, text)
        for port in self.ports:
            text = port.sub("127.0.0.1:%d" % MASK_PORT, text)
        text = re.sub(r'"pid":[0-9]+', '"pid":%d' % MASK_PID, text)
        text = re.sub(r'"apiKey":"[^"]*"', '"apiKey":"placeholder"', text)
        return re.sub(r'"slug":"([^"]+)"', self._slug, text)


def machine_identity():
    """What must not appear in a fixture, read from this machine at runtime."""
    uid = int(os.environ.get(NETNS_MARKER, os.getuid()))
    entry = pwd.getpwuid(uid)
    return {
        "username": entry.pw_name,
        "hostname": socket.gethostname(),
        "home": entry.pw_dir,
        "extra_paths": [str(ROOT), "/tmp/claude-%d" % uid, "/run/user/%d" % uid],
        "now_ms": int(time.time() * 1000),
    }


def _private_address(text):
    for token in IPV4_TOKEN.findall(text):
        try:
            address = ipaddress.ip_address(token)
        except ValueError:
            continue
        if address in TAILSCALE_NET or (address.is_private and not address.is_loopback and token != "0.0.0.0"):
            return True
    return False


def _has_path(path, line):
    return re.search(re.escape(path) + r"(?![\w-])", line) is not None


def _has_name(name, line):
    return name not in COMMON_NAMES and word(name).search(line) is not None


def _line_problems(line, machine, words=True):
    found = []
    if _has_path(machine["home"], line):
        found.append("real home path")
    if any(_has_path(path, line) for path in machine["extra_paths"]):
        found.append("machine path")
    if words and _has_name(machine["username"], line.replace(machine["home"], "")):
        found.append("username")
    if words and _has_name(machine["hostname"], line):
        found.append("hostname")
    if any(not MASKED_ID_BODY.match(m.group(2)) and any(c.isdigit() for c in m.group(2))
           for m in ID_TOKEN.finditer(line)):
        found.append("unmasked ID")
    if any(not MASKED_HASH.match(m.group(0)) for m in HASH_TOKEN.finditer(line)):
        found.append("unmasked hash")
    for match in EPOCH_MS_TOKEN.finditer(line):
        if line[:match.start()].endswith('"released":'):
            continue
        if abs(int(match.group(0)) - machine["now_ms"]) < 180 * DAY_MS:
            found.append("recent timestamp")
            break
    if AUTH_VALUE.search(line):
        found.append("authorization value")
    if _private_address(line):
        found.append("private address")
    for match in CURSOR_TOKEN.finditer(line):
        inner = decode_cursor(match.group(0))
        if inner is not None:
            found.extend("cursor: " + problem for problem in _line_problems(inner, machine, words))
    return sorted(set(found))


def check_text(text, machine, words=True):
    """Returns `line N: problem` strings. They never repeat the matched value."""
    return ["line %d: %s" % (number, problem)
            for number, line in enumerate(text.splitlines(), 1)
            for problem in _line_problems(line, machine, words)]


def _shape_problems(path, text):
    if path.suffix == ".json":
        try:
            json.loads(text)
        except ValueError:
            return ["invalid JSON"]
    if path.suffix == ".jsonl":
        for number, line in enumerate(text.splitlines(), 1):
            try:
                json.loads(line)
            except ValueError:
                return ["line %d: invalid JSON" % number]
    return []


def _tree_files(root):
    """Returns the files below `root` and a problem for everything that cannot be walked."""
    files, problems = [], []

    def failed(error):
        problems.append("%s: folder is missing or unreadable" % error.filename)

    for folder, folders, names in os.walk(root, onerror=failed):
        for name in folders + names:
            path = Path(folder) / name
            if path.is_file():
                files.append(path)
            elif path.is_symlink() or not path.is_dir():
                problems.append("%s: not a regular file or folder, so it was not checked" % path)
    return sorted(files), problems


def check_tree(root, machine):
    """Returns `path: problem` strings for every file below `root`.

    A tree that could not be read in full, or that holds no files, is a problem too:
    a clean result has to mean that everything was looked at.
    """
    files, found = _tree_files(root)
    if not files and not found:
        found.append("%s: no files to check" % root)
    for path in files:
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            found.append("%s: unreadable, so it was not checked" % path)
            continue
        words = path.relative_to(root).parts[0] != "spec"
        for problem in check_text(text, machine, words) + _shape_problems(path, text):
            found.append("%s: %s" % (path, problem))
    return found


class WebSocket:
    """Minimal RFC 6455 text client for the server's loopback control socket."""

    def __init__(self, port):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=10)
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall(("GET / HTTP/1.1\r\nHost: 127.0.0.1:%d\r\nUpgrade: websocket\r\n"
                           "Connection: Upgrade\r\nSec-WebSocket-Key: %s\r\n"
                           "Sec-WebSocket-Version: 13\r\n\r\n" % (port, key)).encode())
        data = b""
        while b"\r\n\r\n" not in data:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("control socket closed during upgrade")
            data += chunk
        head, self.buffer = data.split(b"\r\n\r\n", 1)
        if b" 101 " not in head.split(b"\r\n")[0]:
            raise ConnectionError("control socket refused the upgrade")
        self.sock.settimeout(None)

    def _read(self, size):
        while len(self.buffer) < size:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise EOFError
            self.buffer += chunk
        data, self.buffer = self.buffer[:size], self.buffer[size:]
        return data

    def send(self, payload, opcode=1):
        data = payload.encode() if isinstance(payload, str) else payload
        mask = os.urandom(4)
        size = len(data)
        if size < 126:
            header = bytes([0x80 | opcode, 0x80 | size])
        elif size < 65536:
            header = bytes([0x80 | opcode, 0x80 | 126]) + struct.pack(">H", size)
        else:
            header = bytes([0x80 | opcode, 0x80 | 127]) + struct.pack(">Q", size)
        self.sock.sendall(header + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

    def recv(self):
        message = b""
        while True:
            first, second = self._read(2)
            opcode = first & 0x0F
            size = second & 0x7F
            if size == 126:
                size = struct.unpack(">H", self._read(2))[0]
            elif size == 127:
                size = struct.unpack(">Q", self._read(8))[0]
            payload = self._read(size)
            if opcode == 9:
                self.send(payload, 10)
            elif opcode == 8:
                raise EOFError
            elif opcode != 10:
                message += payload
                if first & 0x80:
                    return message.decode()

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


class Simulator:
    """Plays the model: receives each provider request and scripts the answer."""

    def __init__(self, port):
        self.ws = WebSocket(port)
        self.next_id = 0
        self.waiting = {}
        self.requests = queue.Queue()
        threading.Thread(target=self._read_loop, daemon=True).start()
        self.rpc("simulation.handshake", {
            "client": {"name": "capture_fixtures", "version": "1"}, "expectedRole": "backend",
            "offeredVersions": [1], "requiredCapabilities": ["llm.attach"], "optionalCapabilities": []})
        self.rpc("llm.attach")

    def _read_loop(self):
        while True:
            try:
                message = json.loads(self.ws.recv())
            except (EOFError, OSError, ValueError):
                return
            if message.get("method") == "llm.request":
                self.requests.put(message["params"])
            elif "id" in message and message["id"] in self.waiting:
                self.waiting[message["id"]].put(message)

    def rpc(self, method, params=None):
        self.next_id += 1
        reply = queue.Queue()
        self.waiting[self.next_id] = reply
        message = {"jsonrpc": "2.0", "id": self.next_id, "method": method}
        if params is not None:
            message["params"] = params
        self.ws.send(json.dumps(message))
        answer = reply.get(timeout=20)
        if "error" in answer:
            raise RuntimeError("%s failed: %s" % (method, answer["error"]))
        return answer.get("result")

    def next(self, timeout=45):
        try:
            return self.requests.get(timeout=timeout)
        except queue.Empty:
            raise RuntimeError("the server sent no model request within %d s" % timeout) from None

    def stream(self, request, chunks, reason="stop", pause=0.06):
        """Sends chunks apart. The server merged deltas 60 ms apart; 400 ms kept them apart."""
        for items in chunks:
            self.rpc("llm.chunk", {"id": request["id"], "items": items})
            time.sleep(pause)
        self.rpc("llm.finish", {"id": request["id"], "reason": reason})

    def answer(self, chunks, reason="stop", timeout=45, pause=0.06):
        request = self.next(timeout)
        self.stream(request, chunks, reason, pause)
        return request

    def drain(self):
        """Ends whatever a failed flow left waiting, so it cannot leak into the next."""
        while True:
            try:
                request = self.requests.get_nowait()
            except queue.Empty:
                return
            try:
                self.stream(request, [[text("Flow aborted.")]])
            except RuntimeError:
                pass


def text(value):
    return {"type": "textDelta", "text": value}


def reasoning(value):
    return {"type": "reasoningDelta", "text": value}


def tool_call(index, call_id, name, arguments):
    return {"type": "toolCall", "index": index, "id": call_id, "name": name, "input": arguments}


def usage(prompt, completion, reasoning_tokens=0, cached=0):
    return {"type": "raw", "chunk": {"choices": [], "usage": {
        "prompt_tokens": prompt, "completion_tokens": completion, "total_tokens": prompt + completion,
        "prompt_tokens_details": {"cached_tokens": cached},
        "completion_tokens_details": {"reasoning_tokens": reasoning_tokens}}}}


class EventStream:
    """Keeps every `data:` line of /api/event raw, in arrival order."""

    def __init__(self, port, auth):
        self.raw = []
        self.parsed = []
        self.wire = []
        self.changed = threading.Condition()
        self.conn = http.client.HTTPConnection("127.0.0.1", port, timeout=None)
        self.conn.request("GET", "/api/event", headers={"Authorization": auth, "Accept": "text/event-stream"})
        self.response = self.conn.getresponse()
        if self.response.status != 200:
            raise RuntimeError("event stream returned %d" % self.response.status)
        threading.Thread(target=self._read_loop, daemon=True).start()

    def _read_loop(self):
        try:
            for line in self.response:
                value = line.decode("utf-8").rstrip("\r\n")
                with self.changed:
                    self.wire.append(value)
                    if value.startswith("data:"):
                        payload = value[5:].lstrip()
                        self.raw.append(payload)
                        self.parsed.append(json.loads(payload))
                        self.changed.notify_all()
        except (OSError, ValueError, http.client.HTTPException):
            return


class Server:
    """One `opencode serve`, isolated in a temporary tree, bound to 127.0.0.1."""

    def __init__(self, binary, base):
        self.base = base
        self.project = base / "project"
        self.password = secrets.token_urlsafe(32)
        self.auth = "Basic " + base64.b64encode(("opencode:" + self.password).encode()).decode()
        self.port = free_port()
        self.backend_port = free_port()
        for name in ("home", "config/opencode", "data", "state/opencode-drive/instances", "cache", "tmp", "project"):
            (base / name).mkdir(parents=True)
        (base / "state/opencode-drive/instances/capture.json").write_text(json.dumps({"endpoints": {
            "ui": "ws://127.0.0.1:%d" % free_port(), "backend": "ws://127.0.0.1:%d" % self.backend_port}}))
        (base / "config/opencode/opencode.json").write_text(json.dumps(SERVER_CONFIG))
        (base / "home/.gitconfig").write_text(
            "[user]\n\tname = Fixture\n\temail = fixture@example.invalid\n[init]\n\tdefaultBranch = main\n")
        (self.project / "notes.txt").write_text("first line\nsecond line\n")
        (self.project / "docs").mkdir()
        (self.project / "docs/guide.md").write_text("# Guide\n")
        (self.project / ".env").write_text("FIXTURE_ONLY=1\n")
        (self.project / ".env.local").write_text("FIXTURE_ONLY=2\n")
        (self.project / "sample.env").write_text("FIXTURE_ONLY=3\n")
        (self.project / ".gitignore").write_text("*.env\n.env*\n")
        environment = {
            "PATH": "/usr/bin:/bin", "LANG": "C.UTF-8", "TMPDIR": str(base / "tmp"),
            "HOME": str(base / "home"), "XDG_CONFIG_HOME": str(base / "config"),
            "XDG_DATA_HOME": str(base / "data"), "XDG_STATE_HOME": str(base / "state"),
            "XDG_CACHE_HOME": str(base / "cache"), "OPENCODE_PASSWORD": self.password,
            "OPENCODE_SIMULATE": "1", "OPENCODE_DRIVE": "capture",
            "OPENCODE_DISABLE_MODELS_FETCH": "1", "OPENCODE_DISABLE_CLAUDE_CODE": "1",
        }
        with open(base / "server.log", "wb") as self.log:
            self.process = subprocess.Popen(
                [str(binary), "serve", "--hostname", "127.0.0.1", "--port", str(self.port)],
                env=environment, cwd=self.project, stdin=subprocess.DEVNULL, stdout=self.log,
                stderr=subprocess.STDOUT, start_new_session=True)
        self.location = urllib.parse.urlencode({"location[directory]": str(self.project)})

    def request(self, method, path, body=None, timeout=30):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=timeout)
        headers = {"Authorization": self.auth}
        payload = None
        if body is not None:
            payload = json.dumps(body, ensure_ascii=False).encode()
            headers["Content-Type"] = "application/json"
        try:
            conn.request(method, path, body=payload, headers=headers)
            response = conn.getresponse()
            return response.status, response.read().decode("utf-8", "replace")
        finally:
            conn.close()

    def wait_listening(self, timeout=60):
        """Answers before the location boots, so startup events can still be recorded."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise RuntimeError("the server exited during startup")
            try:
                if self.request("GET", "/api/info")[0] == 200:
                    return
            except OSError:
                pass
            time.sleep(0.1)
        raise RuntimeError("the server did not listen within %d s" % timeout)

    def wait_ready(self, timeout=90):
        deadline = time.monotonic() + timeout
        path = "/api/provider?" + self.location
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise RuntimeError("the server exited during startup")
            try:
                if self.request("GET", path)[0] == 200:
                    return
            except OSError:
                pass
            time.sleep(0.25)
        raise RuntimeError("the server was not ready within %d s" % timeout)

    def stop(self):
        """Stops only this process and its group, then proves that both are gone."""
        pid = self.process.pid
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(15)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(15)
        try:
            os.killpg(pid, signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            pass
        time.sleep(0.2)
        with socket.socket() as probe:
            probe.settimeout(1)
            port_open = probe.connect_ex(("127.0.0.1", self.port)) == 0
        try:
            os.kill(pid, 0)
            process_alive = True
        except ProcessLookupError:
            process_alive = False
        if port_open or process_alive:
            raise RuntimeError("server still present after stop: port %s, process %s" % (port_open, process_alive))
        print("server stopped: process and port verified gone", flush=True)


def free_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


class Flow:
    """Records one flow: its slice of the event stream and every HTTP exchange."""

    def __init__(self, run, name, first_event=None):
        self.run = run
        self.name = name
        self.server = run.server
        self.sim = run.simulator
        self.stream = run.stream
        self.first = len(self.stream.raw) if first_event is None else first_event
        self.wire_first = 0 if first_event == 0 else len(self.stream.wire)
        self.last = None
        self.consumed = set()
        self.exchanges = []
        self.sessions = []

    def http(self, label, method, path, body=None, location=False, expect=(200, 204), timeout=30):
        if location:
            path += ("&" if "?" in path else "?") + self.server.location
        status, response = self.server.request(method, path, body, timeout)
        self.exchanges.append({"label": label, "method": method, "path": path, "request": body,
                               "status": status, "body": response,
                               "after_event": len(self.stream.raw) - self.first})
        if expect is not None and status not in expect:
            raise RuntimeError("%s %s returned %d" % (method, label, status))
        try:
            return json.loads(response) if response else None
        except ValueError:
            return None

    def session(self, title, **extra):
        body = {"title": title, "location": {"directory": str(self.server.project)}}
        body.update(extra)
        sid = self.http("session-create", "POST", "/api/session", body)["data"]["id"]
        self.sessions.append(sid)
        return sid

    def prompt(self, sid, value, **extra):
        body = {"text": value}
        body.update(extra)
        return self.http("prompt", "POST", "/api/session/%s/prompt" % sid, body)

    def event(self, types, sid=None, timeout=45):
        """Waits for the next unconsumed event of a type, optionally for one session."""
        wanted = (types,) if isinstance(types, str) else tuple(types)
        deadline = time.monotonic() + timeout
        with self.stream.changed:
            while True:
                for index in range(self.first, len(self.stream.parsed)):
                    current = self.stream.parsed[index]
                    if index in self.consumed or current.get("type") not in wanted:
                        continue
                    if sid is not None and event_session(current) != sid:
                        continue
                    self.consumed.add(index)
                    return current
                left = deadline - time.monotonic()
                if left <= 0:
                    raise RuntimeError("no %s event within %d s" % ("/".join(wanted), timeout))
                self.stream.changed.wait(left)

    def child_of(self, sid, timeout=20):
        """Waits for the session that the subagent tool opened under `sid`."""
        deadline = time.monotonic() + timeout
        with self.stream.changed:
            while True:
                for current in self.stream.parsed[self.first:]:
                    data = current.get("data", {})
                    if current.get("type") == "session.created" and data.get("parentID") == sid:
                        self.sessions.append(data["sessionID"])
                        return data["sessionID"]
                left = deadline - time.monotonic()
                if left <= 0:
                    raise RuntimeError("no child session within %d s" % timeout)
                self.stream.changed.wait(left)

    def idle(self, sid, timeout=45):
        return self.event(EXECUTION_ENDS, sid, timeout)

    def settled(self, sid, timeout=45, quiet=0.3):
        """Waits until no execution of `sid` is open and its events have stopped.

        The quiet period covers events that follow a finished request, such as
        `session.revert.committed` after the 204, or the shell result enqueued
        after `session.shell.ended`.
        """
        deadline = time.monotonic() + timeout
        count, since = -1, 0.0
        with self.stream.changed:
            while True:
                kinds = [event.get("type") for event in self.stream.parsed[self.first:]
                         if event_session(event) == sid]
                now = time.monotonic()
                if len(kinds) != count:
                    count, since = len(kinds), now
                open_runs = kinds.count("session.execution.started") - sum(k in EXECUTION_ENDS for k in kinds)
                if open_runs <= 0 and now - since >= quiet:
                    return
                if now >= deadline:
                    raise RuntimeError("session did not settle within %s s" % timeout)
                self.stream.changed.wait(min(0.05, deadline - now))

    def snapshot(self, sid, label="final", timeout=45):
        self.settled(sid, timeout)
        self.http(label + "-session", "GET", "/api/session/%s" % sid)
        return self.http(label + "-messages", "GET", "/api/session/%s/message" % sid)

    def close(self):
        time.sleep(0.4)
        self.last = len(self.stream.raw)
        self.wire_last = len(self.stream.wire)

    def event_types(self):
        return [event.get("type") for event in self.stream.parsed[self.first:self.last]]


def event_session(event):
    data = event.get("data", {})
    return data.get("sessionID") or data.get("form", {}).get("sessionID")


SLOW = 0.4  # Long enough for the server to flush each delta as its own event.


def flow_startup(f):
    f.http("info", "GET", "/api/info")
    f.http("location", "GET", "/api/location", location=True)
    f.http("unknown-session", "GET", "/api/session/ses_doesnotexist0000000000000", expect=None)


def flow_git_setup(f):
    sid = f.session("Git setup")
    f.http("vcs-init", "POST", "/api/vcs/init", {}, location=True)
    f.http("vcs-after-init", "GET", "/api/vcs", location=True)
    f.prompt(sid, "Commit the project.")
    f.sim.answer([[tool_call(0, "call_git", "shell", {"command": "git add -A && git commit -qm initial"})]],
                 "tool-calls")
    f.sim.answer([[text("Committed.")]])
    f.idle(sid)


def flow_text(f):
    sid = f.session("Text flow")
    f.prompt(sid, "Say hello.")
    f.sim.answer([[text("Hello")], [text(" from the")], [text(" simulated model. Türkçe: ığüşöç.")],
                  [usage(120, 16)]], pause=SLOW)
    f.idle(sid)
    f.http("session-after-turn", "GET", "/api/session/%s" % sid)
    first = f.http("messages-page-1", "GET", "/api/session/%s/message?limit=1" % sid)
    cursor = first.get("cursor", {}).get("next")
    if cursor:
        f.http("messages-page-2", "GET", "/api/session/%s/message?limit=1&cursor=%s" % (sid, cursor))
    f.http("messages-asc", "GET", "/api/session/%s/message?order=asc" % sid)
    f.snapshot(sid)


def flow_reasoning(f):
    sid = f.session("Reasoning flow")
    f.prompt(sid, "Think, then answer.")
    f.sim.answer([[reasoning("Weighing")], [reasoning(" the options")], [reasoning(".")],
                  [text("The answer")], [text(" is 42.")], [usage(200, 40, reasoning_tokens=25, cached=50)]],
                 pause=SLOW)
    f.idle(sid)
    f.prompt(sid, "Answer with hidden thinking.")
    f.sim.answer([[reasoning("")], [text("Done.")], [usage(210, 6)]])
    f.idle(sid)
    f.snapshot(sid)


def flow_tool(f):
    sid = f.session("Tool flow")
    f.prompt(sid, "Write a file, read it, then run a command.")
    request = f.sim.next()
    arguments = json.dumps({"path": "created.txt", "content": "made by the write tool\n"})
    f.sim.stream(request, [
        [text("Writing the file.")],
        [{"type": "toolInputStart", "index": 0, "id": "call_write", "name": "write"}],
        [{"type": "toolInputDelta", "index": 0, "text": arguments[:12]}],
        [{"type": "toolInputDelta", "index": 0, "text": arguments[12:]}],
        [usage(300, 30)]], "tool-calls", SLOW)
    f.sim.answer([[tool_call(0, "call_read_ok", "read", {"path": "created.txt"}),
                   tool_call(1, "call_read_missing", "read", {"path": "missing.txt"})], [usage(340, 20)]],
                 "tool-calls")
    f.sim.answer([[tool_call(0, "call_shell", "shell", {"command": "printf 'one\\ntwo\\n'"})]], "tool-calls")
    f.sim.answer([[text("All tools ran.")], [usage(400, 8)]])
    f.idle(sid)
    f.snapshot(sid)


def flow_subagent(f):
    sid = f.session("Subagent flow")
    f.prompt(sid, "Delegate reading the notes.")
    f.sim.answer([[tool_call(0, "call_sub", "subagent", {
        "agent": "general", "description": "Read the notes", "prompt": "Read notes.txt and summarize it."})],
        [usage(500, 30)]], "tool-calls")
    child = f.child_of(sid)
    f.sim.answer([[reasoning("Reading first.")],
                  [tool_call(0, "call_child_read", "read", {"path": "notes.txt"})], [usage(80, 12)]], "tool-calls")
    f.http("active-during-subagent", "GET", "/api/session/active")
    f.sim.answer([[text("The notes have two lines.")], [usage(100, 9)]])
    f.idle(child)
    f.sim.answer([[text("The subagent finished.")], [usage(530, 7)]])
    f.idle(sid)
    f.http("children", "GET", "/api/session?parentID=%s" % sid)
    f.snapshot(child, "child")
    f.snapshot(sid)


def flow_subagent_permission(f):
    sid = f.session("Subagent permission flow")
    f.prompt(sid, "Delegate reading the secrets file.")
    f.sim.answer([[tool_call(0, "call_sub2", "subagent", {
        "agent": "general", "description": "Read env", "prompt": "Read .env."})]], "tool-calls")
    child = f.child_of(sid)
    f.sim.answer([[tool_call(0, "call_child_env", "read", {"path": ".env"})]], "tool-calls")
    asked = f.event("permission.asked", child)
    f.http("pending-all", "GET", "/api/permission/request", location=True)
    f.http("reply-child", "POST", "/api/session/%s/permission/%s/reply" % (child, asked["data"]["id"]),
           {"decision": "once"})
    f.sim.answer([[text("Read it.")]])
    f.idle(child)
    f.sim.answer([[text("Done.")]])
    f.idle(sid)
    f.snapshot(child, "child")


def flow_form_question(f):
    sid = f.session("Question form flow")
    f.prompt(sid, "Ask me two questions.")
    f.sim.answer([[tool_call(0, "call_question", "question", {"questions": [
        {"question": "Which colour?", "header": "Colour", "options": [
            {"label": "Red", "description": "Warm"}, {"label": "Blue", "description": "Cool"}]},
        {"question": "Which sizes?", "header": "Sizes", "multiple": True, "options": [
            {"label": "S", "description": "Small"}, {"label": "M", "description": "Medium"},
            {"label": "L", "description": "Large"}]}]})]], "tool-calls")
    created = f.event("form.created", sid)
    form = created["data"]["form"]["id"]
    path = "/api/session/%s/form" % sid
    f.http("pending-all", "GET", "/api/form", location=True)
    f.http("pending-session", "GET", path)
    f.http("detail-pending", "GET", "%s/%s" % (path, form))
    f.http("reply-invalid", "POST", "%s/%s/reply" % (path, form), {"answer": {"q0": 7}}, expect=None)
    f.http("reply", "POST", "%s/%s/reply" % (path, form), {"answer": {"q0": "Red", "q1": ["S", "L"]}})
    f.event("form.replied", sid)
    f.http("detail-answered", "GET", "%s/%s" % (path, form))
    f.http("reply-again", "POST", "%s/%s/reply" % (path, form), {"answer": {"q0": "Blue", "q1": []}}, expect=None)
    f.sim.answer([[tool_call(0, "call_question2", "question", {"questions": [
        {"question": "Keep going?", "header": "Continue", "options": [
            {"label": "Yes", "description": "Go on"}, {"label": "No", "description": "Stop"}]}]})]], "tool-calls")
    second = f.event("form.created", sid)["data"]["form"]["id"]
    f.http("cancel", "DELETE", "%s/%s" % (path, second))
    f.event("form.cancelled", sid)
    f.http("detail-cancelled", "GET", "%s/%s" % (path, second))
    # Dismissing a question ends the whole execution: no further model request comes.
    f.idle(sid)
    f.prompt(sid, "Carry on without an answer.")
    f.sim.answer([[text("Carrying on.")]])
    f.idle(sid)
    f.snapshot(sid)


def flow_form_fields(f):
    sid = f.session("Form fields flow")
    path = "/api/session/%s/form" % sid
    created = f.http("create-all-types", "POST", path, {
        "title": "All field types", "metadata": {"kind": "fixture"}, "fields": [
            {"key": "name", "type": "string", "title": "Name", "description": "Your name", "required": True,
             "minLength": 1, "maxLength": 20, "pattern": "^[A-Za-z]+$", "placeholder": "Ada", "default": "Ada"},
            {"key": "mail", "type": "string", "format": "email", "title": "Mail"},
            {"key": "plan", "type": "string", "title": "Plan", "custom": True, "options": [
                {"value": "free", "label": "Free"}, {"value": "pro", "label": "Pro"}]},
            {"key": "seats", "type": "integer", "title": "Seats", "minimum": 1, "maximum": 10, "default": 2,
             "when": [{"key": "plan", "op": "eq", "value": "pro"}]},
            {"key": "ratio", "type": "number", "title": "Ratio", "minimum": 0, "maximum": 1, "default": 0.5,
             "when": [{"key": "plan", "op": "neq", "value": "free"}]},
            {"key": "agree", "type": "boolean", "title": "Agree", "default": False},
            {"key": "extras", "type": "multiselect", "title": "Extras", "minItems": 1, "maxItems": 2,
             "options": [{"value": "a", "label": "A"}, {"value": "b", "label": "B"}, {"value": "c", "label": "C"}]},
            {"key": "hidden", "type": "string", "hidden": True, "default": "secretless"}]})
    form = created["data"]["id"]
    f.http("detail", "GET", "%s/%s" % (path, form))
    f.http("reply-out-of-range", "POST", "%s/%s/reply" % (path, form),
           {"answer": {"name": "Ada", "plan": "pro", "seats": 99, "agree": True, "extras": ["a"]}}, expect=None)
    f.http("reply", "POST", "%s/%s/reply" % (path, form), {"answer": {
        "name": "Ada", "mail": "ada@example.invalid", "plan": "pro", "seats": 3, "ratio": 0.25,
        "agree": True, "extras": ["a", "c"]}})
    external = f.http("create-external", "POST", path, {"title": "External step", "fields": [
        {"key": "login", "type": "external", "url": "https://example.invalid/login",
         "title": "Sign in", "description": "Opens a page"}]})
    f.http("detail-external", "GET", "%s/%s" % (path, external["data"]["id"]))
    f.http("reply-external-empty", "POST", "%s/%s/reply" % (path, external["data"]["id"]), {"answer": {}}, expect=None)
    f.http("reply-external", "POST", "%s/%s/reply" % (path, external["data"]["id"]), {"answer": {"login": True}},
           expect=None)
    f.http("detail-external-after", "GET", "%s/%s" % (path, external["data"]["id"]))
    cancelled = f.http("create-to-cancel", "POST", path, {"title": "Dismissed", "fields": [
        {"key": "note", "type": "string"}]})
    f.http("cancel", "DELETE", "%s/%s" % (path, cancelled["data"]["id"]))


def flow_permission(f):
    sid = f.session("Permission flow")
    path = "/api/session/%s/permission" % sid
    f.prompt(sid, "Read the secret files.")
    f.sim.answer([[tool_call(0, "call_env1", "read", {"path": ".env"})]], "tool-calls")
    asked = f.event("permission.asked", sid)["data"]
    f.http("pending-all", "GET", "/api/permission/request", location=True)
    f.http("pending-session", "GET", path)
    f.http("detail", "GET", "%s/%s" % (path, asked["id"]))
    f.http("reply-once", "POST", "%s/%s/reply" % (path, asked["id"]), {"decision": "once"})
    f.event("permission.replied", sid)
    f.sim.answer([[tool_call(0, "call_env2", "read", {"path": "sample.env"})]], "tool-calls")
    asked = f.event("permission.asked", sid)["data"]
    f.http("reply-reject", "POST", "%s/%s/reply" % (path, asked["id"]),
           {"decision": "reject", "message": "Not this file."})
    f.event("permission.replied", sid)
    f.http("reply-again", "POST", "%s/%s/reply" % (path, asked["id"]), {"decision": "once"}, expect=None)
    # Last on purpose: `always` saves a rule that stops later reads from asking.
    f.sim.answer([[tool_call(0, "call_env3", "read", {"path": ".env.local"})]], "tool-calls")
    asked = f.event("permission.asked", sid)["data"]
    f.http("reply-always", "POST", "%s/%s/reply" % (path, asked["id"]), {"decision": "always"})
    f.event("permission.replied", sid)
    saved = f.http("saved", "GET", "/api/permission/saved", location=True)
    f.sim.answer([[text("Understood.")]])
    f.idle(sid)
    for rule in saved["data"]:
        f.http("saved-delete", "DELETE", "/api/permission/saved/%s" % rule["id"], location=True)
    f.snapshot(sid)


def flow_lifecycle(f):
    sid = f.session("Lifecycle flow", metadata={"origin": "fixture"})
    f.prompt(sid, "First turn.")
    f.sim.answer([[text("First answer.")]])
    f.idle(sid)
    messages = f.http("messages", "GET", "/api/session/%s/message" % sid)
    users = [m["id"] for m in messages["data"] if m["type"] == "user"]
    path = "/api/session/%s" % sid
    f.http("rename", "PATCH", path, {"title": "Renamed flow"})
    f.http("metadata", "PATCH", path, {"metadata": {"origin": "fixture", "pinned": True}})
    f.http("permissions", "PATCH", path, {"permissions": [{"action": "shell", "resource": "*", "effect": "ask"}]})
    f.http("view", "POST", path + "/view", {"idle": 0}, expect=None)
    f.http("archive", "PATCH", path, {"archived": True})
    f.http("archived-get", "GET", path)
    f.http("list-archived", "GET", "/api/session?archived=true&limit=5", location=True)
    f.http("list-default", "GET", "/api/session?limit=5", location=True)
    f.http("unarchive", "PATCH", path, {"archived": False})
    f.http("unarchived-get", "GET", path)
    fork = f.http("fork", "POST", path + "/fork", {})["data"]
    f.http("fork-before", "POST", path + "/fork", {"before": users[0]})
    f.http("list-search", "GET", "/api/session?search=Renamed&limit=5", location=True)
    f.http("delete-fork", "DELETE", "/api/session/%s" % fork["id"])
    f.http("delete-again", "DELETE", "/api/session/%s" % fork["id"], expect=None)


def flow_interrupt(f):
    sid = f.session("Interrupt flow")
    f.http("active-idle", "GET", "/api/session/active")
    f.prompt(sid, "Start something long.")
    request = f.sim.next()
    f.sim.rpc("llm.chunk", {"id": request["id"], "items": [text("Working on")]})
    f.event("session.text.delta", sid)
    f.http("active-running", "GET", "/api/session/active")
    f.http("session-running", "GET", "/api/session/%s" % sid)
    f.http("interrupt", "POST", "/api/session/%s/interrupt" % sid)
    f.idle(sid)
    f.http("active-after", "GET", "/api/session/active")
    f.snapshot(sid)


def flow_retry(f):
    sid = f.session("Retry flow")
    f.prompt(sid, "Survive a dropped provider stream.")
    request = f.sim.next()
    f.sim.rpc("llm.chunk", {"id": request["id"], "items": [text("Partial")]})
    time.sleep(0.2)
    f.sim.rpc("llm.disconnect", {"id": request["id"]})
    f.event("session.retry.scheduled", sid)
    f.sim.answer([[text("Recovered.")]], timeout=60)
    f.idle(sid)
    f.snapshot(sid)


def flow_error(f):
    sid = f.session("Error flow")
    f.prompt(sid, "Hit a provider error.")
    request = f.sim.next()
    # The server drops the stream at the error chunk, so there is nothing to finish.
    f.sim.rpc("llm.chunk", {"id": request["id"], "items": [{"type": "raw", "chunk": {"error": {
        "message": "Simulated invalid request", "type": "invalid_request_error", "code": "invalid"}}}]})
    f.idle(sid)
    f.snapshot(sid)
    missing = f.session("Unavailable model")
    f.http("switch-unknown-model", "POST", "/api/session/%s/model" % missing,
           {"model": {"id": "not-installed", "providerID": "sim"}})
    f.prompt(missing, "Use a model that does not exist.")
    f.idle(missing)
    f.snapshot(missing, "unavailable")


def flow_queue(f):
    sid = f.session("Delivery flow")
    f.prompt(sid, "Begin.")
    request = f.sim.next()
    f.sim.rpc("llm.chunk", {"id": request["id"], "items": [text("Working")]})
    f.prompt(sid, "Also do this now.", delivery="steer")
    f.prompt(sid, "Afterwards do this.", delivery="queue")
    dropped = f.prompt(sid, "Never mind this.", delivery="queue")["data"]["id"]
    moved = f.prompt(sid, "Make this one urgent.", delivery="queue")["data"]["id"]
    f.http("inbox", "GET", "/api/session/%s/inbox" % sid)
    f.http("inbox-cancel", "DELETE", "/api/session/%s/inbox/%s" % (sid, dropped), expect=None)
    f.http("inbox-steer", "PATCH", "/api/session/%s/inbox/%s" % (sid, moved), {"delivery": "steer"}, expect=None)
    f.http("inbox-after", "GET", "/api/session/%s/inbox" % sid)
    f.sim.stream(request, [[text(" still.")]])
    answer_until_quiet(f, "Handled the next input.")
    f.snapshot(sid)


def answer_until_quiet(f, value, limit=20):
    """Answers every model request until no session is running."""
    for _ in range(limit):
        try:
            f.sim.stream(f.sim.requests.get(timeout=1), [[text(value)]])
        except queue.Empty:
            if json.loads(f.server.request("GET", "/api/session/active")[1]).get("data") == {}:
                return


def flow_auto_title(f):
    body = {"location": {"directory": str(f.server.project)}}
    sid = f.http("session-create", "POST", "/api/session", body)["data"]["id"]
    f.sessions.append(sid)
    f.prompt(sid, "A session with no title.")
    time.sleep(1)
    answer_until_quiet(f, "Generated title")
    time.sleep(1)
    f.snapshot(sid)


def flow_diff_revert(f):
    sid = f.session("Diff and revert flow")
    f.prompt(sid, "Create a file.")
    f.sim.answer([[tool_call(0, "call_w1", "write", {"path": "tracked.txt", "content": "alpha\n"})]], "tool-calls")
    f.sim.answer([[text("Created.")]])
    f.idle(sid)
    f.prompt(sid, "Change the file.")
    f.sim.answer([[tool_call(0, "call_e1", "edit", {
        "path": "tracked.txt", "oldString": "alpha", "newString": "beta"})]], "tool-calls")
    f.sim.answer([[text("Changed.")]])
    f.idle(sid)
    f.prompt(sid, "Add one more line.")
    f.sim.answer([[tool_call(0, "call_w3", "write", {"path": "tracked.txt", "content": "beta\ngamma\n"})]], "tool-calls")
    f.sim.answer([[text("Added.")]])
    f.idle(sid)
    messages = f.http("messages-asc", "GET", "/api/session/%s/message?order=asc" % sid)
    users = [m["id"] for m in messages["data"] if m["type"] == "user"]
    path = "/api/session/%s" % sid
    f.http("diff-latest", "GET", path + "/diff")
    f.http("diff-from", "GET", "%s/diff?from=%s" % (path, users[0]))
    f.http("diff-from-to", "GET", "%s/diff?from=%s&to=%s" % (path, users[0], users[1]))
    f.http("diff-context", "GET", "%s/diff?from=%s&context=0" % (path, users[1]))
    f.http("revert-stage", "POST", path + "/revert/stage", {"messageID": users[1]})
    f.event("session.revert.staged", sid)
    f.settled(sid)
    f.http("session-staged", "GET", path)
    f.http("messages-staged", "GET", path + "/message")
    f.http("revert-clear", "DELETE", path + "/revert")
    f.event("session.revert.cleared", sid)
    # Clearing a revert starts a short execution of its own, with no model request.
    f.settled(sid)
    f.http("revert-stage-files", "POST", path + "/revert/stage", {"messageID": users[2], "files": True})
    f.event("session.revert.staged", sid)
    f.settled(sid)
    f.http("revert-commit", "POST", path + "/revert/commit")
    f.event("session.revert.committed", sid)
    f.snapshot(sid)


def flow_compaction(f):
    sid = f.session("Compaction flow")
    f.prompt(sid, "Give me something to compact.")
    f.sim.answer([[text("Some long answer to compact.")], [usage(5000, 200)]])
    f.idle(sid)
    f.http("compact-rejected", "POST", "/api/session/%s/compact" % sid, {})
    # A summary without the template headings is retried once, then fails.
    f.sim.answer([[text("A summary without headings.")]])
    f.sim.answer([[text("Still no headings.")]])
    f.event("session.compaction.failed", sid)
    f.idle(sid)
    f.http("compact", "POST", "/api/session/%s/compact" % sid, {})
    request = f.sim.next()
    prompt = request["body"]["messages"][-1]["content"]
    prompt = prompt if isinstance(prompt, str) else " ".join(part.get("text", "") for part in prompt)
    headings = [line for line in prompt.splitlines() if line.startswith("##")]
    f.sim.stream(request, [[text("\n".join(heading + "\n- Simulated summary line." for heading in headings))],
                           [usage(5200, 40)]])
    f.event("session.compaction.ended", sid)
    f.idle(sid)
    f.snapshot(sid)


def flow_commands(f):
    sid = f.session("Command, shell and switching flow")
    path = "/api/session/%s" % sid
    f.http("switch-agent", "POST", path + "/agent", {"agent": "plan"})
    f.http("switch-model", "POST", path + "/model", {"model": {"id": "sim-2", "providerID": "sim"}})
    f.http("switch-agent-back", "POST", path + "/agent", {"agent": "build"})
    f.http("command", "POST", path + "/command", {"name": "review", "text": "uncommitted"})
    f.sim.answer([[text("Nothing to review.")]])
    f.idle(sid)
    f.http("shell", "POST", path + "/shell", {"command": "printf 'from shell\\n'"})
    f.event("session.shell.ended", sid)
    # The shell result is enqueued after this event and starts no execution, so settling
    # is enough; an execution would have to end before the snapshot below.
    f.settled(sid)
    f.http("context", "GET", path + "/context", expect=None)
    f.snapshot(sid)


def flow_catalog(f):
    calls = [
        ("agents", "/api/agent", True), ("agent-build", "/api/agent/build", True),
        ("models", "/api/model", True), ("model-default", "/api/model/default", True),
        ("providers", "/api/provider", True), ("provider-sim", "/api/provider/sim", True),
        ("plugins", "/api/plugin", True), ("mcp", "/api/mcp", True),
        ("skills", "/api/skill", True), ("commands", "/api/command", True),
        ("projects", "/api/project", False),
        ("vcs", "/api/vcs", True), ("vcs-base", "/api/vcs/base", True), ("vcs-status", "/api/vcs/status", True),
        ("vcs-branch", "/api/vcs/branch?limit=10", True), ("vcs-diff-working", "/api/vcs/diff?mode=working", True),
        ("vcs-diff-committed", "/api/vcs/diff?mode=committed", True),
        ("vcs-diff-branch", "/api/vcs/diff?mode=branch&base=main", True),
        ("fs-list", "/api/fs/list?path=.", True),
        ("fs-find-file", "/api/fs/find?query=notes&type=file&limit=5", True),
        ("fs-find-directory", "/api/fs/find?query=docs&type=directory&limit=5", True),
        ("fs-read", "/api/fs/read/notes.txt", True), ("forms-none", "/api/form", True),
        ("permissions-none", "/api/permission/request", True), ("sessions", "/api/session?limit=3&order=desc", True),
        ("session-active", "/api/session/active", False), ("config", "/api/config", True),
    ]
    for label, path, located in calls:
        f.http(label, "GET", path, location=located, expect=None)


FLOWS = [
    ("startup", flow_startup, ("server.connected",)),
    ("git-setup", flow_git_setup, ("session.tool.success",)),
    ("text", flow_text, ("session.text.started", "session.text.delta", "session.text.ended",
                         "session.step.ended", "session.usage.updated", "session.execution.succeeded")),
    ("reasoning", flow_reasoning, ("session.reasoning.started", "session.reasoning.delta",
                                   "session.reasoning.ended", "session.text.delta")),
    ("tool", flow_tool, ("session.tool.input.started", "session.tool.input.ended",
                         "session.tool.called", "session.tool.success", "session.tool.failed")),
    ("subagent", flow_subagent, ("session.created", "session.tool.progress", "session.tool.success")),
    ("subagent-permission", flow_subagent_permission, ("permission.asked", "permission.replied")),
    ("form-question", flow_form_question, ("form.created", "form.replied", "form.cancelled")),
    ("form-fields", flow_form_fields, ("form.created", "form.replied")),
    ("permission", flow_permission, ("permission.asked", "permission.replied")),
    ("lifecycle", flow_lifecycle, ("session.renamed", "session.archived", "session.unarchived",
                                   "session.metadata.updated", "session.permissions", "session.forked",
                                   "session.deleted")),
    ("interrupt", flow_interrupt, ("session.execution.interrupted",)),
    ("retry", flow_retry, ("session.retry.scheduled",)),
    ("error", flow_error, ("session.execution.failed",)),
    ("delivery", flow_queue, ("session.inbox.enqueued", "session.inbox.delivered", "session.inbox.cancelled",
                              "session.inbox.delivery.changed")),
    ("auto-title", flow_auto_title, ("session.renamed",)),
    ("diff-revert", flow_diff_revert, ("session.revert.staged",)),
    ("compaction", flow_compaction, ("session.compaction.started", "session.compaction.delta",
                                     "session.compaction.ended", "session.compaction.failed")),
    ("commands", flow_commands, ("session.shell.started", "session.shell.ended")),
    ("catalog", flow_catalog, ()),
]

SERVER_CONFIG = {
    "model": "sim/sim-1",
    "providers": {"sim": {
        "name": "Simulated", "package": "@opencode/ai/providers/openai-compatible",
        # The URL the simulator intercepts. The key is a placeholder: nothing leaves loopback.
        "settings": {"apiKey": "placeholder", "baseURL": "https://api.openai.com/v1"},
        "models": {"sim-1": {
            "name": "Sim One", "compatibility": {"reasoningField": "reasoning_content"},
            "capabilities": {"tools": True, "input": ["text"], "output": ["text"]},
            "limit": {"context": 100000, "output": 8000},
            "cost": [{"input": 1.5, "output": 6, "cache": {"read": 0.15, "write": 1.875}}]},
            "sim-2": {
            "name": "Sim Two", "capabilities": {"tools": True, "input": ["text"], "output": ["text"]},
            "limit": {"context": 200000, "input": 150000, "output": 16000},
            "cost": {"input": 3, "output": 15}}}}},
    "mcp": {"servers": {
        "fixture-broken": {"type": "local", "command": ["/bin/false"]},
        "fixture-off": {"type": "local", "command": ["/bin/false"], "disabled": True},
        "fixture-remote": {"type": "remote", "url": "http://127.0.0.1:9/mcp", "oauth": False},
    }},
}


class Run:
    def __init__(self, server, simulator, stream):
        self.server = server
        self.simulator = simulator
        self.stream = stream


def assert_offline():
    """Refuses to record if this process can reach anything beyond loopback."""
    routes = Path("/proc/net/route").read_text().splitlines()[1:]
    devices = [line.split(":")[0].strip() for line in Path("/proc/net/dev").read_text().splitlines()[2:]]
    if routes or devices != ["lo"]:
        raise SystemExit("refusing to record: this network namespace is not empty")


class BinaryError(OSError):
    """The opencode binary is missing or cannot be run."""


def find_binary(explicit):
    """Returns an absolute path: the server starts in the temporary project folder.

    Symlinks stay as they are, because a program can depend on the name it was started by.
    """
    entry = pwd.getpwuid(int(os.environ.get(NETNS_MARKER, os.getuid())))
    found = explicit or shutil.which("opencode") or str(Path(entry.pw_dir) / ".opencode/bin/opencode")
    path = Path(os.path.abspath(found))
    if not path.is_file() or not os.access(path, os.X_OK):
        raise BinaryError("--binary: not an executable file: %s" % path)
    return path


def refuse_common_names(identity, text=None):
    """Fails closed when masking a machine name would rewrite ordinary words."""
    for name in (identity["username"], identity["hostname"]):
        if name in COMMON_NAMES or (text is not None and word(name).search(text)):
            raise SystemExit("refusing to record: this machine's username or hostname is an ordinary "
                             "word of the API, and masking it would corrupt the fixtures")


def run_flows(run, selected):
    results = {}
    recorded = []
    for name, function, expected in FLOWS:
        if selected and name not in selected:
            continue
        flow = Flow(run, name, 0 if name == "startup" else None)
        status, reason = "recorded", None
        try:
            function(flow)
        except (RuntimeError, OSError, KeyError, TypeError, IndexError, AttributeError) as error:
            status, reason = "failed", "%s: %s" % (type(error).__name__, error)
            for sid in flow.sessions:
                try:
                    run.server.request("POST", "/api/session/%s/interrupt" % sid)
                except OSError:
                    pass
            time.sleep(0.5)
            run.simulator.drain()
        flow.close()
        types = flow.event_types()
        missing = [kind for kind in expected if kind not in types]
        if missing and status == "recorded":
            status, reason = "partial", "missing events: " + ", ".join(missing)
        results[name] = {"status": status, "reason": reason, "events": len(types),
                         "event_types": sorted(set(types))}
        recorded.append(flow)
        print("%-22s %-9s %s" % (name, status, reason or ""), flush=True)
    return results, recorded


def write_flow(flow, output, masker):
    folder = output / flow.name
    if folder.exists():
        shutil.rmtree(folder)
    (folder / "http").mkdir(parents=True)
    events = [masker.mask(line) for line in flow.stream.raw[flow.first:flow.last]]
    (folder / "events.jsonl").write_text("".join(line + "\n" for line in events), encoding="utf-8")
    if flow.name in ("startup", "text"):
        wire = flow.stream.wire[flow.wire_first:flow.wire_last]
        (folder / "sse-wire.txt").write_text("".join(masker.mask(line) + "\n" for line in wire), encoding="utf-8")
    lines = []
    for number, exchange in enumerate(flow.exchanges, 1):
        record = {"label": exchange["label"], "method": exchange["method"],
                  "path": exchange["path"], "request": exchange["request"],
                  "status": exchange["status"], "after_event": exchange["after_event"], "response": None}
        body = exchange["body"]
        if body:
            try:
                json.loads(body)
                suffix = ".json"
            except ValueError:
                suffix = ".txt"
            name = "%02d-%s%s" % (number, exchange["label"], suffix)
            (folder / "http" / name).write_text(masker.mask(body), encoding="utf-8")
            record["response"] = "http/" + name
        lines.append(masker.mask(json.dumps(record, ensure_ascii=False)))
    (folder / "exchanges.jsonl").write_text("".join(line + "\n" for line in lines), encoding="utf-8")


def capture(args):
    assert_offline()
    return record(args)


@contextlib.contextmanager
def signals_end_the_run():
    """Turns SIGTERM and SIGHUP into SystemExit, so `finally` blocks run. SIGINT does already."""
    def stop(number, _frame):
        # A second signal must not interrupt the cleanup that this one starts.
        for name in (signal.SIGTERM, signal.SIGHUP, signal.SIGINT):
            signal.signal(name, signal.SIG_IGN)
        raise SystemExit(128 + number)

    saved = {name: signal.signal(name, stop) for name in (signal.SIGTERM, signal.SIGHUP, signal.SIGINT)}
    try:
        yield
    finally:
        for name, handler in saved.items():
            signal.signal(name, handler)


def remove_run_folder(base):
    """Deletes the temporary tree. Its server log and database may hold the password."""
    shutil.rmtree(base, ignore_errors=True)
    if base.exists():
        raise RuntimeError("could not remove the temporary run folder: " + str(base))


def record(args):
    refuse_common_names(machine_identity())
    binary = find_binary(args.binary)
    selected = set(args.flows.split(",")) if args.flows else None
    unknown = (selected or set()) - {name for name, _, _ in FLOWS}
    if unknown:
        raise SystemExit("unknown flows: " + ", ".join(sorted(unknown)))
    work_root = Path(args.work_dir).resolve() if args.work_dir else Path(tempfile.gettempdir())
    with signals_end_the_run():
        base = Path(tempfile.mkdtemp(prefix="capture-", dir=work_root))
        try:
            return record_into(base, work_root, binary, Path(args.output), selected)
        finally:
            remove_run_folder(base)


def record_into(base, work_root, binary, output, selected):
    started = int(time.time() * 1000)
    server = Server(binary, base)
    simulator = None
    try:
        server.wait_listening()
        stream = EventStream(server.port, server.auth)
        simulator = Simulator(server.backend_port)
        server.wait_ready()
        status, spec = server.request("GET", "/openapi.json")
        if status != 200:
            raise RuntimeError("spec returned %d" % status)
        refuse_common_names(machine_identity(), spec)
        version = json.loads(server.request("GET", "/api/info")[1])["version"]
        results, flows = run_flows(Run(server, simulator, stream), selected)
    finally:
        if simulator is not None:
            simulator.ws.close()
        server.stop()
    if server.password in (base / "server.log").read_text(errors="replace"):
        print("note: the password appeared in the temporary server log, which is deleted", flush=True)

    identity = machine_identity()
    masker = Masker(
        paths={str(base / "project"): MASK_PROJECT, str(base / "home"): MASK_HOME,
               str(base): "/tmp/capture", str(work_root): "/tmp", identity["home"]: MASK_HOME},
        secrets=[server.password], run_start_ms=started, run_end_ms=int(time.time() * 1000),
        username=identity["username"], hostname=identity["hostname"],
        ports=[server.port, server.backend_port])
    output.mkdir(parents=True, exist_ok=True)
    (output / "spec").mkdir(exist_ok=True)
    (output / "spec/openapi.json").write_text(masker.mask_static(spec), encoding="utf-8")
    for flow in flows:
        write_flow(flow, output, masker)
    manifest = {"opencode_version": version, "source": "OPENCODE_SIMULATE with scripted model output",
                "flows": results}
    if selected is None:
        (output / "manifest.json").write_text(json.dumps(manifest, indent=1, sort_keys=True) + "\n", encoding="utf-8")

    leaked = [str(p) for p in output.rglob("*") if p.is_file() and server.password in p.read_text(encoding="utf-8")]
    problems = check_tree(output, identity)
    for line in problems:
        print(line, flush=True)
    bad = [name for name, entry in results.items() if entry["status"] != "recorded"]
    print("flows: %d recorded, %d not; leak check: %d problem(s); password found in %d file(s)"
          % (len(results) - len(bad), len(bad), len(problems), len(leaked)), flush=True)
    return 1 if bad or problems or leaked else 0


class MaskerTests(unittest.TestCase):
    START = 1_793_632_922_000
    PASSWORD = "Xy" + "7Qw" * 10

    def masker(self):
        return Masker(
            paths={"/work/run-1/proj": "/workspace/project", "/work/run-1/home": "/home/fixture",
                   "/work/run-1": "/tmp/capture", "/work": "/tmp"},
            secrets=[self.PASSWORD],
            run_start_ms=self.START,
            run_end_ms=self.START + 3_600_000,
            username="tester",
            hostname="pibox",
            ports=[48197],
        )

    def test_same_id_gets_same_mask_and_keeps_relationships(self):
        m = self.masker()
        out = m.mask('{"id":"ses_e632860d9ffeffA5bKMmFKWY7i","parentID":"ses_e632596b2ffenz9f5ycZ5XtiLd"}'
                     '{"sessionID":"ses_e632860d9ffeffA5bKMmFKWY7i"}')
        doc, _, tail = out.partition("}{")
        parent = json.loads(doc + "}")
        child = json.loads("{" + tail)
        self.assertEqual(parent["id"], child["sessionID"])
        self.assertNotEqual(parent["id"], parent["parentID"])
        self.assertNotIn("e632860d", out)
        self.assertTrue(parent["id"].startswith("ses_"))
        self.assertEqual(len(parent["id"]), len("ses_e632860d9ffeffA5bKMmFKWY7i"))

    def test_masks_are_stable_across_calls_and_idempotent(self):
        m = self.masker()
        first = m.mask("msg_19cda866e001BMCJr1ewcETjcr")
        self.assertEqual(first, m.mask("msg_19cda866e001BMCJr1ewcETjcr"))
        self.assertEqual(first, m.mask(first))

    def test_mask_numbers_follow_first_appearance(self):
        m = self.masker()
        a = m.mask("msg_19cda866e001BMCJr1ewcETjcr")
        b = m.mask("msg_19cda9800001MIp04B3qcApyDA")
        self.assertLess(a, b)

    def test_paths_longest_first_and_url_encoded(self):
        m = self.masker()
        self.assertEqual(m.mask('"/work/run-1/proj/src/a.txt"'), '"/workspace/project/src/a.txt"')
        self.assertEqual(m.mask('"/work/run-1/home/.x"'), '"/home/fixture/.x"')
        self.assertEqual(m.mask('"/work/run-1/config"'), '"/tmp/capture/config"')
        self.assertEqual(m.mask('"/work/other"'), '"/tmp/other"')
        self.assertEqual(m.mask("directory=%2Fwork%2Frun-1%2Fproj&x=1"), "directory=%2Fworkspace%2Fproject&x=1")

    def test_real_home_and_hostname(self):
        m = self.masker()
        self.assertEqual(m.mask('"/home/tester/.opencode/bin"'), '"/home/fixture/.opencode/bin"')
        self.assertEqual(m.mask('{"hostname":"pibox"}'), '{"hostname":"fixture-host"}')
        self.assertEqual(m.mask("tester@pibox"), "fixture@fixture-host")
        self.assertEqual(m.mask("Host: pibox.local"), "Host: fixture-host.local")

    def test_timestamps_keep_deltas_and_leave_old_ones(self):
        m = self.masker()
        out = json.loads(m.mask('{"a":%d,"b":%d,"released":1760659200000}' % (self.START, self.START + 250)))
        self.assertEqual(out["a"], MASK_EPOCH_MS)
        self.assertEqual(out["b"], MASK_EPOCH_MS + 250)
        self.assertEqual(out["released"], 1760659200000)

    def test_iso_timestamps(self):
        m = self.masker()
        out = m.mask('"2026-11-02T15:20:45.069Z"')
        self.assertIn("2023-11-1", out)

    def test_cursor_is_decoded_masked_and_reencoded(self):
        m = self.masker()
        raw = json.dumps({"id": "msg_19cda866e001BMCJr1ewcETjcr", "order": "desc"}, separators=(",", ":"))
        cursor = base64.urlsafe_b64encode(raw.encode()).rstrip(b"=").decode()
        mask = m.mask("msg_19cda866e001BMCJr1ewcETjcr")
        out = m.mask('{"cursor":{"next":"%s"}}' % cursor)
        inner = json.loads(out)["cursor"]["next"]
        decoded = json.loads(base64.urlsafe_b64decode(inner + "=" * (-len(inner) % 4)))
        self.assertEqual(decoded["id"], mask)
        self.assertEqual(decoded["order"], "desc")

    def test_secrets_and_their_basic_form_never_survive(self):
        m = self.masker()
        basic = base64.b64encode(("opencode:" + self.PASSWORD).encode()).decode()
        out = m.mask("x %s y Basic %s z" % (self.PASSWORD, basic))
        self.assertNotIn(self.PASSWORD, out)
        self.assertNotIn(basic, out)

    def test_project_and_commit_hashes(self):
        m = self.masker()
        h = "913a07d0be54a76d9a5db6b926f5ae709dd314da"
        out = m.mask('{"projectID":"%s","again":"%s"}' % (h, h))
        doc = json.loads(out)
        self.assertEqual(doc["projectID"], doc["again"])
        self.assertNotEqual(doc["projectID"], h)
        self.assertRegex(doc["projectID"], "^[0-9a-f]{40}$")

    def test_slug_port_and_pid(self):
        m = self.masker()
        out = json.loads(m.mask('{"slug":"hidden-orchid","pid":1281802,"urls":["http://127.0.0.1:48197"]}'))
        self.assertEqual(out["pid"], 4242)
        self.assertEqual(out["urls"], ["http://127.0.0.1:4096"])
        self.assertNotEqual(out["slug"], "hidden-orchid")

    def test_number_text_and_key_order_stay_raw(self):
        m = self.masker()
        text = '{"z":1,"cost":0.000045,"a":4.5e-05,"title":"t","empty":null}'
        self.assertEqual(m.mask(text), text)


    def test_same_name_for_user_and_host_keeps_the_hostname_field(self):
        m = Masker(paths={}, secrets=[], run_start_ms=self.START, run_end_ms=self.START,
                   username="same", hostname="same")
        self.assertEqual(m.mask('{"hostname":"same","user":"same"}'),
                         '{"hostname":"%s","user":"%s"}' % (MASK_HOST, MASK_USER))

    def test_every_api_key_value_is_replaced(self):
        m = self.masker()
        value = "pub" + "lic"
        self.assertEqual(m.mask('{"settings":{"apiKey":"%s"}}' % value), '{"settings":{"apiKey":"placeholder"}}')

    def test_a_masked_path_is_not_matched_again(self):
        m = self.masker()
        self.assertEqual(m.mask(m.mask('"/work/run-1/proj/a"')), '"/workspace/project/a"')


class FixtureTests(unittest.TestCase):
    """Runs against the recorded tree; skipped until a recording exists."""

    REQUIRED = ("text", "reasoning", "tool", "subagent", "form-question", "form-fields", "permission")

    def setUp(self):
        if not (DEFAULT_OUTPUT / "manifest.json").exists():
            self.skipTest("no recording yet")
        self.manifest = json.loads((DEFAULT_OUTPUT / "manifest.json").read_text(encoding="utf-8"))

    def events(self, flow):
        lines = (DEFAULT_OUTPUT / flow / "events.jsonl").read_text(encoding="utf-8").splitlines()
        return [json.loads(line) for line in lines]

    def test_spec_and_required_flows_are_recorded(self):
        self.assertIn("paths", json.loads((DEFAULT_OUTPUT / "spec/openapi.json").read_text(encoding="utf-8")))
        for flow in self.REQUIRED:
            self.assertEqual(self.manifest["flows"][flow]["status"], "recorded", flow)

    def test_every_flow_is_listed_and_every_response_file_exists(self):
        for name, _, _ in FLOWS:
            self.assertIn(name, self.manifest["flows"])
            folder = DEFAULT_OUTPUT / name
            lines = (folder / "exchanges.jsonl").read_text(encoding="utf-8").splitlines()
            for record in map(json.loads, lines):
                if record["response"]:
                    self.assertTrue((folder / record["response"]).is_file(), record["response"])

    def test_event_ids_stay_unique_and_in_recording_order(self):
        for name, _, _ in FLOWS:
            numbers = [int(event["id"].split("_")[1]) for event in self.events(name)]
            self.assertEqual(numbers, sorted(set(numbers)), name)

    def test_parent_links_survive_masking(self):
        created = [e["data"] for e in self.events("subagent") if e["type"] == "session.created"]
        parent, child = created[0], created[1]
        self.assertEqual(child["parentID"], parent["sessionID"])
        children = sorted((DEFAULT_OUTPUT / "subagent/http").glob("*-children.json"))
        self.assertEqual(json.loads(children[0].read_text(encoding="utf-8"))["data"][0]["id"], child["sessionID"])

    def test_replayed_text_equals_the_message_list(self):
        text_value = "".join(e["data"]["delta"] for e in self.events("text") if e["type"] == "session.text.delta")
        finals = sorted((DEFAULT_OUTPUT / "text/http").glob("*-final-messages.json"))
        messages = json.loads(finals[0].read_text(encoding="utf-8"))["data"]
        blocks = [block["text"] for m in messages if m["type"] == "assistant" for block in m["content"]]
        self.assertEqual(blocks, [text_value])

    def test_default_check_passes_from_another_working_directory(self):
        self.addCleanup(os.chdir, os.getcwd())
        with tempfile.TemporaryDirectory() as elsewhere, contextlib.redirect_stdout(io.StringIO()):
            os.chdir(elsewhere)
            code = main(["--check"])
            os.chdir(ROOT)
        self.assertEqual(code, 0)

    def test_fixture_tree_is_clean(self):
        self.assertEqual(check_tree(DEFAULT_OUTPUT, machine_identity()), [])

    def test_final_snapshots_follow_every_event_of_their_session(self):
        for name, _, _ in FLOWS:
            events = self.events(name)
            lines = (DEFAULT_OUTPUT / name / "exchanges.jsonl").read_text(encoding="utf-8").splitlines()
            for record_line in map(json.loads, lines):
                match = re.fullmatch(r"/api/session/(ses_[0-9]+)(?:/message)?", record_line["path"])
                if not match or not re.match(r"(final|child|unavailable)-", record_line["label"]):
                    continue
                sid = match.group(1)
                seen = [e["type"] for e in events[:record_line["after_event"]] if event_session(e) == sid]
                late = [e["type"] for e in events[record_line["after_event"]:] if event_session(e) == sid]
                with self.subTest(flow=name, label=record_line["label"]):
                    self.assertEqual(late, [])
                    self.assertEqual(seen.count("session.execution.started"),
                                     sum(kind in EXECUTION_ENDS for kind in seen))


class CheckTests(unittest.TestCase):
    MACHINE = {"username": "tester", "hostname": "pibox", "home": "/home/tester",
               "extra_paths": ["/work/tmp-root"], "now_ms": 1_793_632_922_000}

    def problems(self, text):
        return check_text(text, self.MACHINE)

    def test_clean_masked_text_passes(self):
        text = ('{"id":"ses_00000000000000000000000001","time":{"created":1700000000000},'
                '"location":{"directory":"/workspace/project"},"hostname":"fixture-host"}')
        self.assertEqual(self.problems(text), [])

    def test_each_leak_is_found(self):
        cases = {
            "home path": '"/home/tester/x"',
            "extra path": '"/work/tmp-root/y"',
            "username": '"user":"tester"',
            "hostname": '"hostname":"pibox"',
            "unmasked id": '"ses_e632860d9ffeffA5bKMmFKWY7i"',
            "recent timestamp": '"created":1793632922027',
            "unmasked hash": '"913a07d0be54a76d9a5db6b926f5ae709dd314da"',
            "auth header": '"Authorization":"Basic b3Bl"',
            "private address": '"http://%s:4096"' % ".".join(("192", "168", "1", "5")),
        }
        for name, text in cases.items():
            with self.subTest(name):
                self.assertNotEqual(self.problems(text), [])

    def test_cursor_contents_are_checked(self):
        raw = json.dumps({"id": "msg_19cda866e001BMCJr1ewcETjcr"}).encode()
        cursor = base64.urlsafe_b64encode(raw).rstrip(b"=").decode()
        self.assertNotEqual(self.problems('"next":"%s"' % cursor), [])

    def test_check_tree_reports_file_and_passes_clean(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "good.json").write_text('{"id":"ses_00000000000000000000000001"}')
            self.assertEqual(check_tree(root, self.MACHINE), [])
            (root / "bad.json").write_text('{"x":"/home/tester/y"}')
            found = check_tree(root, self.MACHINE)
            self.assertEqual(len(found), 1)
            self.assertIn("bad.json", found[0])

    def test_reports_never_repeat_the_value(self):
        for line in self.problems('"/home/tester/x"'):
            self.assertNotIn("tester", line)


class IdentityWordTests(unittest.TestCase):
    """A username or hostname can also be an ordinary word of the API."""

    ROOT_MACHINE = {"username": "root", "hostname": "pibox", "home": "/root", "extra_paths": [],
                    "now_ms": 1_793_632_922_000}

    def masker(self):
        return Masker(paths={"/work/run": "/tmp/capture"}, secrets=["pw" + "x7Qw" * 5], run_start_ms=0,
                      run_end_ms=0, username="root", hostname="pibox")

    def test_static_mask_keeps_vocabulary_and_masks_paths_and_secrets(self):
        secret = "pw" + "x7Qw" * 5
        text = '{"enum":["root","pibox"],"a":"/work/run/x","b":"%s","pid":7}' % secret
        self.assertEqual(self.masker().mask_static(text),
                         '{"enum":["root","pibox"],"a":"/tmp/capture/x","b":"<redacted>","pid":7}')

    def tree(self, relative, content):
        holder = tempfile.TemporaryDirectory()
        self.addCleanup(holder.cleanup)
        path = Path(holder.name) / relative
        path.parent.mkdir(parents=True)
        path.write_text(content)
        return Path(holder.name)

    def test_spec_words_are_not_leaks_but_its_paths_are(self):
        machine = dict(CheckTests.MACHINE)
        clean = self.tree("spec/openapi.json", '{"enum":["tester","pibox"]}')
        self.assertEqual(check_tree(clean, machine), [])
        leaked = self.tree("spec/openapi.json", '{"a":"/home/tester/x"}')
        self.assertEqual(len(check_tree(leaked, machine)), 1)

    def test_flow_files_still_report_the_words(self):
        tree = self.tree("text/events.jsonl", '{"user":"tester"}')
        self.assertEqual(len(check_tree(tree, CheckTests.MACHINE)), 1)

    def test_a_common_name_is_no_word_leak_but_its_home_is(self):
        self.assertEqual(check_text('{"text":"the root session"}', self.ROOT_MACHINE), [])
        self.assertEqual(check_text('{"path":"/rootfs/x"}', self.ROOT_MACHINE), [])
        self.assertNotEqual(check_text('{"path":"/root/x"}', self.ROOT_MACHINE), [])

    def test_capture_refuses_a_common_name_before_starting(self):
        for machine in ({"username": "root", "hostname": "pibox"}, {"username": "tester", "hostname": "localhost"}):
            with self.subTest(machine):
                with tempfile.TemporaryDirectory() as tmp:
                    args = types_namespace(flows=None, output=tmp + "/out", work_dir=tmp, binary=sys.executable)
                    with mock.patch.object(sys.modules[__name__], "machine_identity", return_value=machine):
                        with self.assertRaises(SystemExit) as caught:
                            record(args)
                    self.assertIn("ordinary word", str(caught.exception))
                    self.assertEqual(list(Path(tmp).iterdir()), [])

    def test_capture_refuses_a_name_found_in_the_spec(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = types_namespace(flows=None, output=tmp + "/out", work_dir=tmp, binary=sys.executable)
            module = sys.modules[__name__]
            identity = dict(CheckTests.MACHINE)
            with mock.patch.object(module, "machine_identity", return_value=identity), \
                    mock.patch.object(module, "Server", FakeServer), \
                    mock.patch.object(module, "Simulator", mock.MagicMock()), \
                    mock.patch.object(module, "EventStream", mock.MagicMock()), \
                    mock.patch.object(FakeServer, "request",
                                      lambda s, m, p, b=None, timeout=30: (200, '{"version":"0","x":"pibox"}')):
                with self.assertRaises(SystemExit) as caught:
                    record(args)
            self.assertIn("ordinary word", str(caught.exception))
            self.assertEqual(list(Path(tmp).iterdir()), [])


class BinaryTests(unittest.TestCase):
    def setUp(self):
        holder = tempfile.TemporaryDirectory()
        self.addCleanup(holder.cleanup)
        self.addCleanup(os.chdir, os.getcwd())
        self.folder = Path(holder.name).resolve()
        os.chdir(self.folder)
        self.tool = self.folder / "tool"
        self.tool.write_text("#!/bin/sh\nexit 1\n")
        self.tool.chmod(0o755)

    def test_relative_path_becomes_absolute(self):
        self.assertEqual(find_binary("./tool"), self.tool)

    def test_symlink_is_kept_so_the_program_name_survives(self):
        (self.folder / "link").symlink_to(self.tool)
        self.assertEqual(find_binary("link"), self.folder / "link")

    def test_bad_paths_are_reported(self):
        plain = self.folder / "plain"
        plain.write_text("x")
        (self.folder / "dir").mkdir()
        for value in ("./missing", "./plain", "./dir"):
            with self.subTest(value):
                with self.assertRaises(BinaryError) as caught:
                    find_binary(value)
                self.assertIn(value.lstrip("./"), str(caught.exception))

    def test_relative_binary_starts_from_the_temporary_project(self):
        args = types_namespace(flows=None, output=str(self.folder / "out"), work_dir=str(self.folder),
                               binary="./tool")
        with self.assertRaisesRegex(RuntimeError, "exited during startup"):
            with mock.patch.object(sys.modules[__name__], "machine_identity",
                                   return_value=dict(CheckTests.MACHINE)):
                record(args)

    def test_main_reports_a_bad_binary_without_a_traceback(self):
        module = sys.modules[__name__]
        with mock.patch.dict(os.environ, {NETNS_MARKER: str(os.getuid())}), \
                mock.patch.object(module, "assert_offline"), \
                mock.patch.object(module, "machine_identity", return_value=dict(CheckTests.MACHINE)), \
                mock.patch("sys.stderr", new=io.StringIO()) as errors:
            self.assertEqual(main(["--binary", "./missing"]), 2)
        self.assertIn("--binary", errors.getvalue())


class FakeStream:
    """Stands in for EventStream: tests push events by hand."""

    def __init__(self):
        self.raw = []
        self.parsed = []
        self.wire = []
        self.changed = threading.Condition()

    def push(self, kind, sid="ses_fake", delay=0):
        def add():
            with self.changed:
                event = {"type": kind, "data": {"sessionID": sid}}
                self.raw.append(json.dumps(event))
                self.parsed.append(event)
                self.changed.notify_all()
        if delay:
            threading.Timer(delay, add).start()
        else:
            add()


class FakeApi:
    location = "x=1"
    project = Path("/nonexistent")

    def request(self, method, path, body=None, timeout=30):
        return 200, '{"data":{"id":"ses_fake"}}'


class SnapshotTests(unittest.TestCase):
    def flow(self, simulator=None):
        self.stream = FakeStream()
        return Flow(types_namespace(server=FakeApi(), simulator=simulator, stream=self.stream), "t", 0)

    def test_snapshot_waits_for_the_open_execution(self):
        flow = self.flow()
        self.stream.push("session.execution.started")
        self.stream.push("session.execution.succeeded", delay=0.2)
        flow.snapshot("ses_fake")
        self.assertEqual(flow.exchanges[0]["after_event"], 2)

    def test_snapshot_fails_if_the_execution_never_ends(self):
        flow = self.flow()
        self.stream.push("session.execution.started")
        with self.assertRaises(RuntimeError):
            flow.snapshot("ses_fake", timeout=0.3)
        self.assertEqual(flow.exchanges, [])

    def test_snapshot_waits_for_events_that_follow_a_request(self):
        flow = self.flow()
        self.stream.push("session.revert.staged")
        self.stream.push("session.revert.committed", delay=0.1)
        flow.snapshot("ses_fake")
        self.assertEqual(flow.exchanges[0]["after_event"], 2)

    def test_another_sessions_execution_does_not_block(self):
        flow = self.flow()
        self.stream.push("session.execution.started", "ses_other")
        flow.snapshot("ses_fake", timeout=2)
        self.assertEqual(len(flow.exchanges), 2)

    def test_compaction_snapshot_follows_the_last_execution(self):
        stream = FakeStream()

        class Model:
            answers = 0

            def answer(self, chunks, reason="stop"):
                self.answers += 1
                if self.answers == 1:
                    stream.push("session.execution.started")
                    stream.push("session.execution.succeeded")
                elif self.answers == 2:
                    stream.push("session.execution.started")
                else:
                    stream.push("session.compaction.failed")
                    stream.push("session.execution.succeeded")

            def next(self):
                return {"body": {"messages": [{"content": "## Heading"}]}}

            def stream(self, request, chunks):
                stream.push("session.execution.started")
                stream.push("session.compaction.ended")
                stream.push("session.execution.succeeded", delay=0.5)

        flow = Flow(types_namespace(server=FakeApi(), simulator=Model(), stream=stream), "t", 0)
        flow_compaction(flow)
        final = [x for x in flow.exchanges if x["label"].startswith("final-")]
        # All eight scripted events, the last of which arrives after compaction ended.
        self.assertEqual([x["after_event"] for x in final], [8, 8])


class FakeServer:
    instances = []
    stop_error = None

    def __init__(self, binary, base):
        self.base = base
        self.password = "fake-" + "run-value"
        self.port = 1
        self.backend_port = 2
        self.auth = "x"
        (base / "server.log").write_text("server password " + self.password)
        FakeServer.instances.append(self)

    def wait_listening(self):
        pass

    def wait_ready(self):
        pass

    def request(self, method, path, body=None, timeout=30):
        return 200, '{"version":"0"}'

    def stop(self):
        if FakeServer.stop_error:
            raise FakeServer.stop_error


class CleanupTests(unittest.TestCase):
    """Whatever ends a recording, the run folder with its password log must go."""

    def setUp(self):
        holder = tempfile.TemporaryDirectory()
        self.addCleanup(holder.cleanup)
        self.work = Path(holder.name) / "work"
        self.work.mkdir()
        fake_binary = Path(holder.name) / "fake-binary"
        fake_binary.write_text("#!/bin/sh\nexit 1\n")
        fake_binary.chmod(0o755)
        self.args = types_namespace(flows=None, output=str(Path(holder.name) / "out"),
                                    work_dir=str(self.work), binary=str(fake_binary))
        FakeServer.stop_error = None
        self.addCleanup(lambda: setattr(FakeServer, "stop_error", None))
        patch = mock.patch.object(sys.modules[__name__], "machine_identity", return_value=dict(CheckTests.MACHINE))
        patch.start()
        self.addCleanup(patch.stop)

    def assert_empty(self):
        self.assertEqual(list(self.work.iterdir()), [])

    def fake_run(self, **replaced):
        module = sys.modules[__name__]
        names = {"Server": FakeServer, "Simulator": mock.MagicMock(), "EventStream": mock.MagicMock(),
                 "run_flows": mock.MagicMock(return_value=({}, [])), **replaced}
        patches = [mock.patch.object(module, name, value) for name, value in names.items()]
        for patch in patches:
            patch.start()
            self.addCleanup(patch.stop)

    def test_missing_binary(self):
        self.args.binary = str(self.work.parent / "no-such-binary")
        with self.assertRaises(OSError):
            record(self.args)
        self.assert_empty()

    def test_server_that_exits_during_startup(self):
        self.args.binary = "/bin/false"
        with self.assertRaises(RuntimeError):
            record(self.args)
        self.assert_empty()

    def test_success(self):
        self.fake_run()
        self.assertEqual(record(self.args), 0)
        self.assert_empty()

    def test_failure_at_each_stage(self):
        stages = {"wait_ready": mock.MagicMock(side_effect=RuntimeError("boot")),
                  "run_flows": mock.MagicMock(side_effect=RuntimeError("flows")),
                  "Masker": mock.MagicMock(side_effect=RuntimeError("mask")),
                  "write_flow": mock.MagicMock(side_effect=OSError("disk"))}
        flow = types_namespace(name="t")
        for stage, failure in stages.items():
            with self.subTest(stage):
                if stage == "wait_ready":
                    self.fake_run()
                    with mock.patch.object(FakeServer, "wait_ready", failure):
                        with self.assertRaises(RuntimeError):
                            record(self.args)
                else:
                    replaced = {"run_flows": mock.MagicMock(return_value=({}, [flow]))}
                    replaced[stage] = failure
                    self.fake_run(**replaced)
                    with self.assertRaises((RuntimeError, OSError)):
                        record(self.args)
                self.assert_empty()

    def test_failed_stop_still_removes_the_folder(self):
        self.fake_run()
        FakeServer.stop_error = RuntimeError("still running")
        with self.assertRaises(RuntimeError):
            record(self.args)
        self.assert_empty()

    def test_signals_end_the_run_and_clean_up(self):
        for number in (signal.SIGTERM, signal.SIGINT):
            with self.subTest(number):
                before = signal.getsignal(number)
                self.fake_run()
                with mock.patch.object(FakeServer, "wait_ready", lambda s: os.kill(os.getpid(), number)):
                    with self.assertRaises((SystemExit, KeyboardInterrupt)):
                        record(self.args)
                self.assert_empty()
                self.assertEqual(signal.getsignal(number), before)


class CheckRunTests(unittest.TestCase):
    """`--check` must never be green without having looked at the fixtures."""

    def setUp(self):
        holder = tempfile.TemporaryDirectory()
        self.addCleanup(holder.cleanup)
        self.addCleanup(os.chdir, os.getcwd())
        self.folder = Path(holder.name).resolve()
        self.output = self.folder / "fixtures"
        (self.output / "text").mkdir(parents=True)
        (self.output / "text/events.jsonl").write_text('{"type":"server.connected"}\n')
        self.docs = self.folder / "docs"
        self.docs.mkdir()
        (self.docs / "README.md").write_text("Notes.\n")
        module = sys.modules[__name__]
        for name, value in (("DOCS_DIR", self.docs), ("machine_identity", lambda: dict(CheckTests.MACHINE))):
            patch = mock.patch.object(module, name, value)
            patch.start()
            self.addCleanup(patch.stop)

    def run_check(self, output=None):
        printed = io.StringIO()
        with contextlib.redirect_stdout(printed):
            code = run_check(self.output if output is None else output)
        return code, printed.getvalue()

    def assert_fails(self, needle, output=None):
        code, printed = self.run_check(output)
        self.assertEqual(code, 1, printed)
        self.assertIn(needle, printed)

    def test_clean_tree_passes(self):
        self.assertEqual(self.run_check()[0], 0)

    def test_missing_output_fails(self):
        shutil.rmtree(self.output)
        self.assert_fails("fixtures")

    def test_missing_docs_fails(self):
        shutil.rmtree(self.docs)
        self.assert_fails("docs")

    def test_empty_output_fails(self):
        shutil.rmtree(self.output / "text")
        self.assert_fails("fixtures")

    def test_empty_docs_fails(self):
        (self.docs / "README.md").unlink()
        self.assert_fails("docs")

    def test_output_with_no_recorded_flow_fails(self):
        shutil.rmtree(self.output / "text")
        (self.output / ".gitkeep").write_text("")
        self.assert_fails("no recorded flows")

    def test_output_that_is_a_file_fails(self):
        target = self.folder / "plain"
        target.write_text("{}")
        self.assert_fails("plain", target)

    def test_relative_output_follows_the_working_directory(self):
        os.chdir(self.folder)
        self.assertEqual(self.run_check("fixtures")[0], 0)
        os.chdir(self.docs)
        self.assert_fails("fixtures", "fixtures")

    def test_undecodable_file_is_a_problem(self):
        (self.output / "text/blob.bin").write_bytes(b"\xff\xfe")
        self.assert_fails("blob.bin")

    def test_unreadable_file_is_a_problem(self):
        with mock.patch.object(Path, "read_text", side_effect=PermissionError("denied")):
            found = check_tree(self.output, CheckTests.MACHINE)
        self.assertEqual(len(found), 1)
        self.assertIn("events.jsonl", found[0])

    @unittest.skipIf(os.getuid() == 0, "root reads every folder")
    def test_unreadable_folder_is_a_problem(self):
        hidden = self.output / "hidden"
        hidden.mkdir()
        (hidden / "leak.json").write_text('{"x":"/home/tester/y"}')
        hidden.chmod(0)
        self.addCleanup(hidden.chmod, 0o755)
        self.assert_fails("hidden")

    def test_symlink_to_a_folder_is_a_problem(self):
        outside = self.folder / "outside"
        outside.mkdir()
        (outside / "leak.json").write_text('{"x":"/home/tester/y"}')
        (self.output / "linked").symlink_to(outside)
        self.assert_fails("linked")

    def test_broken_symlink_is_a_problem(self):
        (self.output / "gone").symlink_to(self.folder / "nowhere")
        self.assert_fails("gone")


SELF_TEST_CLASSES = (MaskerTests, CheckTests, CheckRunTests, SnapshotTests, CleanupTests, IdentityWordTests,
                     BinaryTests, FixtureTests)


class NoInstallTests(unittest.TestCase):
    """The self-test must pass on a machine with no opencode at all."""

    def test_suite_passes_with_no_binary_to_find(self):
        real = pwd.getpwuid(os.getuid())
        loader = unittest.TestLoader()
        suite = unittest.TestSuite(loader.loadTestsFromTestCase(case) for case in SELF_TEST_CLASSES)
        result = unittest.TestResult()
        with tempfile.TemporaryDirectory() as empty_home, \
                mock.patch("shutil.which", return_value=None), \
                mock.patch("pwd.getpwuid", return_value=types_namespace(pw_name=real.pw_name, pw_dir=empty_home)), \
                contextlib.redirect_stdout(io.StringIO()):
            suite.run(result)
        self.assertEqual([str(case) for case, _ in result.failures + result.errors], [])
        self.assertTrue(result.testsRun)

    def test_suite_passes_for_a_user_whose_name_recording_refuses(self):
        loader = unittest.TestLoader()
        suite = unittest.TestSuite(loader.loadTestsFromTestCase(case) for case in SELF_TEST_CLASSES)
        result = unittest.TestResult()
        with mock.patch("pwd.getpwuid", return_value=types_namespace(pw_name="root", pw_dir="/root")), \
                contextlib.redirect_stdout(io.StringIO()):
            suite.run(result)
        self.assertEqual([str(case) for case, _ in result.failures + result.errors], [])
        self.assertTrue(result.testsRun)


def self_test():
    loader = unittest.TestLoader()
    suite = unittest.TestSuite(loader.loadTestsFromTestCase(case) for case in SELF_TEST_CLASSES)
    suite.addTests(loader.loadTestsFromTestCase(NoInstallTests))
    return 0 if unittest.TextTestRunner(verbosity=1).run(suite).wasSuccessful() else 1


def run_check(output):
    machine = machine_identity()
    output = Path(os.path.abspath(output))
    found = check_tree(output, machine) + check_tree(DOCS_DIR, machine)
    if output.is_dir() and not any(output.rglob("events.jsonl")):
        found.append("%s: no recorded flows" % output)
    for line in found:
        print(line)
    print("check: %d problem(s)" % len(found))
    return 1 if found else 0


def in_empty_netns(argv):
    """Replaces this process with itself inside an empty network namespace."""
    if shutil.which("unshare") is None or shutil.which("ip") is None:
        raise SystemExit("unshare and ip are required: recording never runs with network access")
    env = dict(os.environ)
    env[NETNS_MARKER] = str(os.getuid())
    os.execvpe("unshare", ["unshare", "-rn", "sh", "-c", 'ip link set lo up && exec "$@"', "sh",
                           sys.executable, str(Path(__file__).resolve()), *argv], env)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="scan fixtures for leaks and broken files")
    parser.add_argument("--self-test", action="store_true", help="run the masker and checker tests")
    parser.add_argument("--flows", help="comma-separated flow names; default is all")
    parser.add_argument("--output", default=str(DEFAULT_OUTPUT), help="fixtures folder")
    parser.add_argument("--work-dir", default=None, help="parent of the temporary run folder")
    parser.add_argument("--binary", default=None, help="opencode binary")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    if args.check:
        return run_check(args.output)
    if NETNS_MARKER not in os.environ:
        in_empty_netns(argv)
    try:
        return capture(args)
    except BinaryError as error:
        print(error, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
