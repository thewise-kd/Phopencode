"""Tests for helper/install.sh and helper/uninstall.sh.

Safety: every script run gets a temporary HOME, TMPDIR and XDG_RUNTIME_DIR and a PATH made of
two directories only: a stub directory and a "toolbox" of symlinks to /usr/bin and /bin with
every command that touches system state or processes left out. The stub directory provides
fake `systemctl`, `crontab`, `loginctl`, `pgrep`, `nohup`, `setsid` (and refusing stubs for
`kill`, `pkill`, `sudo` and the like) that record their calls to a file. Before each run a
guard checks that none of those names resolves anywhere else. No test touches the real HOME,
the real crontab or the real systemd, and no test contacts an opencode server.

The end-to-end test builds the real notifyd.pyz into a temporary directory, installs it through
the cron path (real `nohup`, `setsid` and `pgrep`; fake crontab and systemd), and checks that
uninstall makes it exit by itself. Its config points at a closed local port. The only process it
may ever signal is the one it started, found by its temporary path, in the cleanup.
"""
import hashlib
import json
import os
import re
import shutil
import signal
import socket
import stat
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

HELPER = Path(__file__).resolve().parent.parent
REPO = HELPER.parent
INSTALL = HELPER / "install.sh"
UNINSTALL = HELPER / "uninstall.sh"
BUILD_SCRIPT = REPO / "scripts" / "build_helper.py"

SHELL = "/bin/sh"
DASH = shutil.which("dash")

# Assembled at runtime so that no literal secret sits in the repository.
PASSWORD = "sentinel" + "-pw-" + "Inst4ll"
TOPIC = "SENTINEL" + "_t0p1c_" + "inst4ll"
SENTINELS = (PASSWORD, TOPIC)

UNIT_NAME = "phopencode-notifyd.service"
MARKER = "phopencode-notifyd"

# Commands that touch system state or processes: provided only by the stub directory.
GUARDED = (
    "systemctl", "crontab", "loginctl", "pgrep", "pkill", "pidof", "kill", "killall", "skill",
    "nohup", "setsid", "sudo", "su", "doas", "runuser", "systemd-run", "busctl", "dbus-send",
    "at", "batch",
)
# Stubs that refuse and record: any call to them is a test failure.
REFUSING = ("pkill", "pidof", "kill", "killall", "skill", "sudo", "su", "doas", "runuser",
            "systemd-run", "busctl", "dbus-send", "at", "batch")

FORBIDDEN_SYSTEMCTL = {"stop", "kill", "restart", "try-restart", "reload-or-restart",
                       "try-reload-or-restart", "isolate", "--now", "mask", "enable-linger",
                       "condrestart", "force-reload"}

INSTALL_EXIT = {
    "ok": 0, "usage": 2, "no_python": 3, "old_python": 4, "no_config": 5, "bad_config": 6,
    "running": 7, "bad_path": 8, "no_manager": 9, "died": 10, "no_pgrep": 11, "busy": 12,
    "no_flock": 13, "unknown": 14, "lock_failed": 15,
}
UNINSTALL_EXIT = {"ok": 0, "usage": 2, "still_running": 3, "cron_unreadable": 4, "unknown": 5,
                  "bad_path": 8, "busy": 12, "no_flock": 13, "lock_failed": 15}

STUB_SOURCE = r'''
import json
import os
import sys
import time

STATE = @STATE@
NAME = os.path.basename(sys.argv[0])
ARGS = sys.argv[1:]


def _xdg(var, default):
    base = os.environ.get(var, "")
    if not os.path.isabs(base):
        base = os.path.join(os.environ.get("HOME", "/nonexistent"), default)
    return base


def config_path():
    return os.path.join(_xdg("XDG_CONFIG_HOME", ".config"), "phopencode", "notifyd.json")


def unit_dir():
    return os.path.join(_xdg("XDG_CONFIG_HOME", ".config"), "systemd", "user")


def read(name, default=None):
    try:
        with open(os.path.join(STATE, name)) as handle:
            return handle.read().strip()
    except FileNotFoundError:
        return default


def write(name, value):
    with open(os.path.join(STATE, name), "w") as handle:
        handle.write(value)


def running():
    mode = read("running")
    if mode == "sticky":
        return True
    if mode == "until-config-removed":
        return os.path.lexists(config_path())
    return False


def start():
    if read("start") != "dies":
        write("running", "until-config-removed")


def record():
    entry = {"cmd": NAME, "args": ARGS, "config": os.path.lexists(config_path())}
    with open(os.path.join(STATE, "calls.jsonl"), "a") as handle:
        handle.write(json.dumps(entry) + "\n")


def systemctl():
    if read("systemctl", "ok") != "ok":
        sys.stderr.write("Failed to connect to bus: No medium found\n")
        return 1
    if ARGS[:1] != ["--user"] or len(ARGS) < 2:
        return 1
    sub, rest = ARGS[1], ARGS[2:]
    if sub in ("show-environment", "daemon-reload"):
        return 0
    if sub == "enable":
        wants = os.path.join(unit_dir(), "default.target.wants")
        os.makedirs(wants, exist_ok=True)
        link = os.path.join(wants, rest[-1])
        if not os.path.lexists(link):
            os.symlink(os.path.join(unit_dir(), rest[-1]), link)
        return 0
    if sub == "disable":
        link = os.path.join(unit_dir(), "default.target.wants", rest[-1])
        if os.path.lexists(link):
            os.unlink(link)
        return 0
    if sub == "start":
        start()
        after = read("start_unit_state")
        if after:
            write("unit_state", after)
        return 0
    if sub == "show":
        # `show -p ActiveState -p SubState UNIT`; a set unit_state ("ActiveState SubState" or
        # "error") overrides the state derived from the fake process.
        state = read("unit_state")
        if state == "error" or read("is_active") == "error":
            sys.stderr.write("Failed to get properties\n")
            return 1
        if not state:
            state = "active running" if running() else "inactive dead"
        active, sub_state = state.split()
        print("ActiveState=" + active)
        print("SubState=" + sub_state)
        return 0
    if sub == "is-active":
        if read("is_active") == "error":
            sys.stderr.write("Failed to get unit state\n")
            return 1
        active = running()
        if "--quiet" not in rest:
            print("active" if active else "inactive")
        return 0 if active else 3
    return 1


def no_crontab():
    # Like cron's crontab: the message is translated unless the locale is C or POSIX.
    lang = os.environ.get("LC_ALL") or os.environ.get("LC_MESSAGES") or os.environ.get("LANG")
    if lang in ("C", "POSIX"):
        sys.stderr.write("no crontab for tester\n")
    else:
        sys.stderr.write("tester icin crontab yok\n")


def crontab():
    path = os.path.join(STATE, "crontab")
    mode = read("crontab_mode", "ok")
    if ARGS == ["-l"]:
        delay = read("crontab_delay")
        if delay:
            write("crontab_entered", "yes")
            time.sleep(float(delay))
        if mode == "broken":
            sys.stderr.write("crontab: cannot open the spool directory\n")
            return 1
        if not os.path.exists(path):
            no_crontab()
            return 1
        with open(path, "rb") as handle:
            sys.stdout.buffer.write(handle.read())
        return 0
    if ARGS == ["-r"]:
        if os.path.exists(path):
            os.unlink(path)
            return 0
        no_crontab()
        return 1
    if len(ARGS) == 1:
        if ARGS[0] == "-":
            data = sys.stdin.buffer.read()
        else:
            with open(ARGS[0], "rb") as handle:
                data = handle.read()
        with open(path, "wb") as handle:
            handle.write(data)
        return 0
    return 1


def main():
    record()
    if NAME == "systemctl":
        return systemctl()
    if NAME == "crontab":
        return crontab()
    if NAME == "loginctl":
        if ARGS[:1] == ["show-user"] and "Linger" in ARGS:
            print("Linger=" + read("linger", "no"))
            return 0
        return 1
    if NAME == "pgrep":
        if read("pgrep") == "error":
            sys.stderr.write("pgrep: cannot read /proc\n")
            return 3
        if running():
            print("4242")
            return 0
        return 1
    if NAME == "nohup":
        start()
        return 0
    if NAME == "setsid":
        os.execvp(ARGS[0], ARGS)
    if NAME == "python3":
        print(read("python_version", "3 8"))
        return 0
    return 1


sys.exit(main())
'''

_TOOLBOX = None


def toolbox():
    """A directory of symlinks to /usr/bin and /bin, without GUARDED commands and python."""
    global _TOOLBOX
    if _TOOLBOX is None:
        path = tempfile.mkdtemp(prefix="phopencode-toolbox-")
        for directory in ("/usr/bin", "/bin"):
            if not os.path.isdir(directory):
                continue
            for name in sorted(os.listdir(directory)):
                if name in GUARDED or name.startswith("python"):
                    continue
                target = os.path.join(directory, name)
                link = os.path.join(path, name)
                if os.path.lexists(link) or not os.access(target, os.X_OK):
                    continue
                os.symlink(target, link)
        _TOOLBOX = path
    return _TOOLBOX


def tearDownModule():
    if _TOOLBOX is not None:
        shutil.rmtree(_TOOLBOX, ignore_errors=True)


def snapshot(root):
    """Every path under root with its type, mode and content hash or link target."""
    result = {}
    root = str(root)
    for directory, dirs, files in os.walk(root):
        for name in dirs + files:
            full = os.path.join(directory, name)
            rel = os.path.relpath(full, root)
            info = os.lstat(full)
            if stat.S_ISLNK(info.st_mode):
                result[rel] = ("link", os.readlink(full))
            elif stat.S_ISDIR(info.st_mode):
                result[rel] = ("dir", stat.S_IMODE(info.st_mode))
            else:
                with open(full, "rb") as handle:
                    digest = hashlib.sha256(handle.read()).hexdigest()
                result[rel] = ("file", stat.S_IMODE(info.st_mode), digest)
    return result


class Sandbox(object):
    """A temporary HOME, stub directory and state directory for one test."""

    def __init__(self, test, systemctl="absent", linger="no", python="real", real=(),
                 xdg=False):
        self.test = test
        self.root = Path(tempfile.mkdtemp(prefix="phopencode-install-test-"))
        test.addCleanup(shutil.rmtree, str(self.root), True)
        self.home = self.root / "home"
        self.stubs = self.root / "stubs"
        self.state = self.root / "state"
        self.tmp = self.root / "tmp"
        self.runtime = self.root / "runtime"
        for directory in (self.home, self.stubs, self.state, self.tmp, self.runtime):
            directory.mkdir(mode=0o700)
        (self.home / ".config").mkdir(mode=0o755)
        self.xdg = xdg
        if xdg:
            self.config_home = self.root / "xdg-config"
            self.data_home = self.root / "xdg-data"
        else:
            self.config_home = self.home / ".config"
            self.data_home = self.home / ".local" / "share"
        self.config = self.config_home / "phopencode" / "notifyd.json"
        self.data = self.data_home / "phopencode"
        self.pyz = self.data / "notifyd.pyz"
        self.log = self.data / "notifyd.log"
        self.unit = self.config_home / "systemd" / "user" / UNIT_NAME
        self.source = self.root / "upload" / "notifyd.pyz"
        self.source.parent.mkdir()
        self.source.write_bytes(b"#!/usr/bin/env python3\nPK fake archive\n")
        self.real = set(real)
        self._write_stubs(systemctl, python)
        self.set("linger", linger)

    def _write_stubs(self, systemctl, python):
        script = self.stubs / "_stub.py"
        script.write_text("#!%s -I\n" % sys.executable
                          + STUB_SOURCE.replace("@STATE@", repr(str(self.state))))
        script.chmod(0o755)
        names = ["crontab", "loginctl", "pgrep", "nohup", "setsid"] + list(REFUSING)
        if systemctl != "absent":
            names.append("systemctl")
            self.set("systemctl", systemctl)
        for name in names:
            if name in self.real:
                os.symlink(shutil.which(name, path="/usr/bin:/bin"), str(self.stubs / name))
            else:
                os.symlink("_stub.py", str(self.stubs / name))
        if python == "real":
            os.symlink(shutil.which("python3", path="/usr/bin:/bin"), str(self.stubs / "python3"))
        elif python == "old":
            os.symlink("_stub.py", str(self.stubs / "python3"))
            self.set("python_version", "3 8")

    def set(self, name, value):
        (self.state / name).write_text(value)

    def clear(self, name):
        path = self.state / name
        if path.exists():
            path.unlink()

    def env(self, extra=None):
        env = {
            "HOME": str(self.home),
            "PATH": "%s:%s" % (self.stubs, toolbox()),
            "TMPDIR": str(self.tmp),
            "XDG_RUNTIME_DIR": str(self.runtime),
            "LANG": "C",
            "LC_ALL": "C",
            "USER": "tester",
        }
        if self.xdg:
            env["XDG_CONFIG_HOME"] = str(self.config_home)
            env["XDG_DATA_HOME"] = str(self.data_home)
        if extra:
            env.update(extra)
        return env

    def guard(self, env):
        """Fails the test if a guarded command would resolve outside the stub directory."""
        for name in GUARDED + ("python3",):
            found = shutil.which(name, path=env["PATH"])
            if found is None:
                continue
            self.test.assertEqual(
                os.path.dirname(found), str(self.stubs),
                "%s resolves outside the stub directory: %s" % (name, found))

    def write_config(self, mode=0o600, server_url="http://127.0.0.1:9"):
        self.config.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        body = json.dumps({
            "server_url": server_url,
            "password": PASSWORD,
            "ntfy_server": "https://127.0.0.1:9",
            "ntfy_topic": TOPIC,
        })
        fd = os.open(str(self.config), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as handle:
            handle.write(body)
        os.chmod(str(self.config), mode)

    def run(self, script, *args, shell=SHELL, extra=None, timeout=120):
        env = self.env(extra)
        self.guard(env)
        mark = self.calls_count()
        result = subprocess.run(
            [shell, str(script)] + [str(a) for a in args],
            env=env, cwd=str(self.root), capture_output=True, timeout=timeout,
        )
        output = result.stdout + result.stderr
        for sentinel in SENTINELS:
            self.test.assertNotIn(sentinel.encode(), output)
        result.calls = self.calls()[mark:]
        for call in result.calls:
            self.test.assertNotIn(call["cmd"], REFUSING, "refused command called: %r" % call)
            for arg in call["args"]:
                for sentinel in SENTINELS:
                    self.test.assertNotIn(sentinel, arg)
            if call["cmd"] == "systemctl":
                self.test.assertFalse(FORBIDDEN_SYSTEMCTL & set(call["args"]), call)
            if call["cmd"] == "loginctl":
                self.test.assertEqual(call["args"][:1], ["show-user"], call)
        result.out = result.stdout.decode()
        result.err = result.stderr.decode()
        return result

    def install(self, *args, **kwargs):
        if not args:
            args = (self.source,)
        return self.run(INSTALL, *args, **kwargs)

    def uninstall(self, *args, **kwargs):
        return self.run(UNINSTALL, *args, **kwargs)

    def calls(self):
        path = self.state / "calls.jsonl"
        if not path.exists():
            return []
        return [json.loads(line) for line in path.read_text().splitlines()]

    def calls_count(self):
        return len(self.calls())

    def crontab(self):
        path = self.state / "crontab"
        return path.read_bytes() if path.exists() else None

    def set_crontab(self, data):
        (self.state / "crontab").write_bytes(data)


def commands(calls, cmd):
    return [c["args"] for c in calls if c["cmd"] == cmd]


class ScriptSourceTest(unittest.TestCase):

    def test_scripts_are_posix_sh_with_strict_mode(self):
        for script in (INSTALL, UNINSTALL):
            text = script.read_text()
            self.assertTrue(text.startswith("#!/bin/sh\n"), script)
            self.assertIn("\nset -eu\n", text)
            check = subprocess.run([SHELL, "-n", str(script)], capture_output=True)
            self.assertEqual(check.returncode, 0, check.stderr)

    @unittest.skipIf(DASH is None, "dash is not installed")
    def test_scripts_parse_under_dash(self):
        for script in (INSTALL, UNINSTALL):
            check = subprocess.run([DASH, "-n", str(script)], capture_output=True)
            self.assertEqual(check.returncode, 0, check.stderr)

    def test_no_stop_or_kill_commands_in_source(self):
        import re
        # `auto-restart` is a systemd SubState the scripts read, not a command.
        forbidden = re.compile(
            r"\b(kill|pkill|killall|skill|stop|(?<!auto-)restart|try-restart|reload-or-restart|isolate|"
            r"enable-linger|sudo|doas|SIGTERM|SIGKILL|SIGHUP)\b|--now|\bsystemd-run\b"
        )
        for script in (INSTALL, UNINSTALL):
            for number, line in enumerate(script.read_text().splitlines(), 1):
                code = line.strip()
                if code.startswith("#"):
                    continue
                self.assertIsNone(forbidden.search(code),
                                  "%s:%d: %s" % (script.name, number, line))

    def test_scripts_never_read_the_config_contents(self):
        import re
        reads = re.compile(r"\b(cat|head|tail|less|more|grep|sed|awk|cut|od|xxd|base64|python3?)"
                           r"\b[^\n|;]*\$CONFIG\b|<\s*\"?\$CONFIG\b")
        for script in (INSTALL, UNINSTALL):
            for line in script.read_text().splitlines():
                if line.strip().startswith("#"):
                    continue
                self.assertIsNone(reads.search(line), line)


class InstallTest(unittest.TestCase):

    def assert_private_install(self, box):
        self.assertEqual(stat.S_IMODE(os.stat(str(box.data)).st_mode), 0o700)
        self.assertEqual(stat.S_IMODE(os.stat(str(box.pyz)).st_mode), 0o600)
        self.assertEqual(box.pyz.read_bytes(), box.source.read_bytes())
        leftovers = [p for p in os.listdir(str(box.data))
                     if p not in ("notifyd.pyz", "notifyd.log", "installed-dirs")]
        self.assertEqual(leftovers, [])

    def assert_cron_line(self, box):
        lines = [l for l in box.crontab().decode().splitlines()
                 if re.match(r"^@reboot /bin/sh -c '[^']*' " + MARKER + "$", l)]
        self.assertEqual(len(lines), 1, box.crontab())
        line = lines[0]
        self.assertRegex(line, r"^@reboot /bin/sh -c '[^']*' " + MARKER + "$")
        self.assertNotIn("#", line)
        self.assertIn(str(box.pyz), line)
        self.assertIn("--config " + str(box.config), line)
        self.assertIn(str(box.log), line)
        return line

    def test_systemd_path_when_user_manager_works_and_lingers(self):
        box = Sandbox(self, systemctl="ok", linger="yes")
        box.write_config()
        result = box.install()
        self.assertEqual(result.returncode, 0, result.err)
        self.assertEqual(result.out, "installed systemd\n")
        self.assert_private_install(box)
        unit = box.unit.read_text()
        self.assertIn("\nExecStart=%s %s --config %s\n" % (box.stubs / "python3", box.pyz,
                                                           box.config), unit)
        self.assertIn("\nRestart=on-failure\n", unit)
        self.assertIn("\nWantedBy=default.target\n", unit)
        for sentinel in SENTINELS:
            self.assertNotIn(sentinel, unit)
        systemctl = commands(result.calls, "systemctl")
        self.assertIn(["--user", "daemon-reload"], systemctl)
        self.assertIn(["--user", "enable", UNIT_NAME], systemctl)
        self.assertIn(["--user", "start", UNIT_NAME], systemctl)
        self.assertLess(systemctl.index(["--user", "enable", UNIT_NAME]),
                        systemctl.index(["--user", "start", UNIT_NAME]))
        self.assertNotIn("nohup", [c["cmd"] for c in result.calls])
        self.assertIsNone(box.crontab())

    def test_cron_path_without_systemctl(self):
        box = Sandbox(self, systemctl="absent")
        box.write_config()
        result = box.install()
        self.assertEqual(result.returncode, 0, result.err)
        self.assertEqual(result.out, "installed cron\n")
        self.assert_private_install(box)
        self.assert_cron_line(box)
        self.assertFalse(box.unit.exists())
        nohup = [c for c in result.calls if c["cmd"] == "nohup"]
        self.assertEqual(len(nohup), 1)
        self.assertEqual(nohup[0]["args"][1:], [str(box.pyz), "--config", str(box.config)])
        self.assertTrue(os.path.isabs(nohup[0]["args"][0]))
        self.assertEqual(stat.S_IMODE(os.stat(str(box.log)).st_mode), 0o600)

    def test_cron_path_when_systemctl_fails(self):
        box = Sandbox(self, systemctl="fail", linger="yes")
        box.write_config()
        result = box.install()
        self.assertEqual(result.returncode, 0, result.err)
        self.assertEqual(result.out, "installed cron\n")
        self.assert_cron_line(box)
        self.assertFalse(box.unit.exists())

    def test_cron_path_when_not_lingering(self):
        box = Sandbox(self, systemctl="ok", linger="no")
        box.write_config()
        result = box.install()
        self.assertEqual(result.returncode, 0, result.err)
        self.assertEqual(result.out, "installed cron\n")
        self.assert_cron_line(box)
        self.assertFalse(box.unit.exists())
        systemctl = commands(result.calls, "systemctl")
        self.assertNotIn(["--user", "enable", UNIT_NAME], systemctl)
        self.assertNotIn(["--user", "start", UNIT_NAME], systemctl)

    def test_xdg_directories_are_honoured(self):
        box = Sandbox(self, systemctl="ok", linger="yes", xdg=True)
        box.write_config()
        result = box.install()
        self.assertEqual(result.returncode, 0, result.err)
        self.assertTrue(box.pyz.exists())
        self.assertTrue(box.unit.exists())
        self.assertFalse((box.home / ".local").exists())

    def test_install_is_idempotent_cron(self):
        box = Sandbox(self)
        box.set_crontab(b"MAILTO=\"\"\n0 3 * * * /usr/bin/true\n")
        box.write_config()
        self.assertEqual(box.install().returncode, 0)
        first = box.crontab()
        box.clear("running")  # notifyd exited between the two runs
        result = box.install()
        self.assertEqual(result.returncode, 0, result.err)
        self.assertEqual(result.out, "installed cron\n")
        self.assertEqual(box.crontab(), first)
        self.assert_cron_line(box)
        self.assert_private_install(box)

    def test_install_is_idempotent_systemd(self):
        box = Sandbox(self, systemctl="ok", linger="yes")
        box.write_config()
        self.assertEqual(box.install().returncode, 0)
        unit = box.unit.read_bytes()
        tree = snapshot(box.home)
        box.clear("running")
        result = box.install()
        self.assertEqual(result.returncode, 0, result.err)
        self.assertEqual(result.out, "installed systemd\n")
        self.assertEqual(box.unit.read_bytes(), unit)
        self.assertEqual(snapshot(box.home), tree)

    def test_switching_from_cron_to_systemd_removes_the_cron_line(self):
        box = Sandbox(self, systemctl="ok", linger="no")
        box.set_crontab(b"0 3 * * * /usr/bin/true\n")
        box.write_config()
        self.assertEqual(box.install().out, "installed cron\n")
        box.clear("running")
        box.set("linger", "yes")
        result = box.install()
        self.assertEqual(result.out, "installed systemd\n", result.err)
        self.assertEqual(box.crontab(), b"0 3 * * * /usr/bin/true\n")

    def test_switching_from_systemd_to_cron_removes_the_unit(self):
        box = Sandbox(self, systemctl="ok", linger="yes")
        box.write_config()
        self.assertEqual(box.install().out, "installed systemd\n")
        box.clear("running")
        box.set("linger", "no")
        result = box.install()
        self.assertEqual(result.out, "installed cron\n", result.err)
        self.assertFalse(box.unit.exists())
        self.assertFalse(os.path.lexists(str(box.unit.parent / "default.target.wants" / UNIT_NAME)))
        self.assertIn(["--user", "disable", UNIT_NAME], commands(result.calls, "systemctl"))

    def test_refuses_when_already_running(self):
        for systemctl, linger in (("absent", "no"), ("ok", "yes")):
            with self.subTest(systemctl=systemctl):
                box = Sandbox(self, systemctl=systemctl, linger=linger)
                box.write_config()
                box.set("running", "sticky")
                before = snapshot(box.home)
                result = box.install()
                self.assertEqual(result.returncode, INSTALL_EXIT["running"], result.err)
                self.assertIn("uninstall", result.err)
                self.assertEqual(result.out, "")
                self.assertEqual(snapshot(box.home), before)
                self.assertIsNone(box.crontab())
                names = [c["cmd"] for c in result.calls]
                self.assertNotIn("nohup", names)
                for args in commands(result.calls, "systemctl"):
                    self.assertNotIn(args[1], ("enable", "start", "daemon-reload", "disable"))
                self.assertEqual([a for a in commands(result.calls, "crontab") if a != ["-l"]], [])

    def test_python_missing(self):
        box = Sandbox(self, python="missing")
        box.write_config()
        before = snapshot(box.home)
        result = box.install()
        self.assertEqual(result.returncode, INSTALL_EXIT["no_python"], result.err)
        self.assertIn("python3", result.err)
        self.assertEqual(snapshot(box.home), before)

    def test_python_too_old(self):
        box = Sandbox(self, python="old")
        box.write_config()
        before = snapshot(box.home)
        result = box.install()
        self.assertEqual(result.returncode, INSTALL_EXIT["old_python"], result.err)
        self.assertIn("3.9", result.err)
        self.assertEqual(snapshot(box.home), before)

    def test_config_missing(self):
        box = Sandbox(self)
        before = snapshot(box.home)
        result = box.install()
        self.assertEqual(result.returncode, INSTALL_EXIT["no_config"], result.err)
        self.assertIn(str(box.config), result.err)
        self.assertEqual(snapshot(box.home), before)
        self.assertEqual(result.calls, [])

    def test_config_wrong_mode(self):
        for mode in (0o644, 0o640, 0o700, 0o400):
            with self.subTest(mode=oct(mode)):
                box = Sandbox(self)
                box.write_config(mode=mode)
                before = snapshot(box.home)
                result = box.install()
                self.assertEqual(result.returncode, INSTALL_EXIT["bad_config"], result.err)
                self.assertIn("0600", result.err)
                self.assertEqual(snapshot(box.home), before)

    def test_config_symlink_or_directory_refused(self):
        box = Sandbox(self)
        box.write_config()
        real = box.root / "real.json"
        os.rename(str(box.config), str(real))
        os.symlink(str(real), str(box.config))
        result = box.install()
        self.assertEqual(result.returncode, INSTALL_EXIT["bad_config"], result.err)
        box2 = Sandbox(self)
        box2.config.mkdir(parents=True, mode=0o700)
        result = box2.install()
        self.assertEqual(result.returncode, INSTALL_EXIT["bad_config"], result.err)

    def test_usage_errors(self):
        box = Sandbox(self)
        box.write_config()
        for args in ((), (box.root / "missing.pyz",), (box.root,), ("a", "b"), ("--bogus",)):
            with self.subTest(args=args):
                result = box.run(INSTALL, *args)
                self.assertEqual(result.returncode, INSTALL_EXIT["usage"], result.err)
                self.assertFalse(box.pyz.exists())

    def test_unsafe_home_path_refused(self):
        box = Sandbox(self)
        weird = box.root / "my home"
        weird.mkdir()
        result = box.install(extra={"HOME": str(weird)})
        self.assertEqual(result.returncode, INSTALL_EXIT["bad_path"], result.err)
        result = box.install(extra={"HOME": "relative/home"})
        self.assertEqual(result.returncode, INSTALL_EXIT["bad_path"], result.err)

    def test_other_crontab_lines_are_kept_byte_for_byte(self):
        box = Sandbox(self)
        original = (b"# m h dom mon dow command\n"
                    b"SHELL=/bin/sh\n"
                    b"\n"
                    b"*/5 * * * *\t/usr/bin/printf '%%s\\n' x  >/dev/null   \n"
                    b"@reboot /usr/bin/true # phopencode-notifyd-other\n"
                    b"@reboot /usr/bin/true # phopencode-notifyd\n"
                    b"@reboot /bin/sh -c 'true' phopencode-notifyd-other\n"
                    b"0 0 * * * echo \"# phopencode\" \xc3\xa7\n")
        box.set_crontab(original)
        box.write_config()
        result = box.install()
        self.assertEqual(result.returncode, 0, result.err)
        line = self.assert_cron_line(box)
        self.assertEqual(box.crontab(), original + line.encode() + b"\n")
        box.clear("running")
        result = box.uninstall()
        self.assertEqual(result.returncode, 0, result.err)
        self.assertEqual(box.crontab(), original)

    def test_unreadable_crontab_is_not_overwritten(self):
        box = Sandbox(self)
        box.set_crontab(b"0 3 * * * /usr/bin/true\n")
        box.set("crontab_mode", "broken")
        box.write_config()
        result = box.install()
        self.assertEqual(result.returncode, INSTALL_EXIT["no_manager"], result.err)
        self.assertEqual(box.crontab(), b"0 3 * * * /usr/bin/true\n")
        self.assertEqual([a for a in commands(result.calls, "crontab") if a != ["-l"]], [])
        self.assertNotIn("nohup", [c["cmd"] for c in result.calls])

    def test_no_service_manager(self):
        box = Sandbox(self)
        os.unlink(str(box.stubs / "crontab"))
        box.write_config()
        result = box.install()
        self.assertEqual(result.returncode, INSTALL_EXIT["no_manager"], result.err)
        self.assertNotIn("nohup", [c["cmd"] for c in result.calls])

    def test_pgrep_missing(self):
        box = Sandbox(self)
        os.unlink(str(box.stubs / "pgrep"))
        box.write_config()
        result = box.install()
        self.assertEqual(result.returncode, INSTALL_EXIT["no_pgrep"], result.err)
        self.assertIn("pgrep", result.err)
        self.assertFalse(box.pyz.exists())

    def test_service_that_dies_at_once_is_reported(self):
        for systemctl, linger in (("absent", "no"), ("ok", "yes")):
            with self.subTest(systemctl=systemctl):
                box = Sandbox(self, systemctl=systemctl, linger=linger)
                box.set("start", "dies")
                box.write_config()
                result = box.install()
                self.assertEqual(result.returncode, INSTALL_EXIT["died"], result.err)
                self.assertIn(str(box.log) if systemctl == "absent" else "journalctl", result.err)

    def test_status_is_read_only(self):
        box = Sandbox(self, systemctl="ok", linger="yes")
        result = box.install("--status")
        self.assertEqual((result.returncode, result.out), (0, "status none stopped\n"), result.err)
        box.write_config()
        box.install()
        result = box.install("--status")
        self.assertEqual(result.out, "status systemd running\n", result.err)
        cron = Sandbox(self)
        cron.write_config()
        cron.install()
        before = (snapshot(cron.home), cron.crontab())
        result = cron.install("--status")
        self.assertEqual(result.out, "status cron running\n", result.err)
        self.assertEqual((snapshot(cron.home), cron.crontab()), before)
        for call in result.calls:
            if call["cmd"] == "crontab":
                self.assertEqual(call["args"], ["-l"])
            if call["cmd"] == "systemctl":
                self.assertIn(call["args"][1], ("is-active", "show", "show-environment"))
            self.assertNotEqual(call["cmd"], "nohup")


class UninstallTest(unittest.TestCase):

    def full_cycle(self, shell=SHELL, **sandbox):
        box = Sandbox(self, **sandbox)
        crontab = b"# keep me\n15 4 * * 1 /usr/bin/true\n"
        box.set_crontab(crontab)
        tree = snapshot(box.home)
        box.write_config()
        result = box.install(shell=shell)
        self.assertEqual(result.returncode, 0, result.err)
        result = box.uninstall(shell=shell)
        self.assertEqual(result.returncode, 0, result.err)
        self.assertEqual(result.out, "uninstalled\n")
        self.assertEqual(snapshot(box.home), tree)
        self.assertEqual(box.crontab(), crontab)
        self.assertEqual(os.listdir(str(box.tmp)), [])
        return box, result

    def test_cron_uninstall_leaves_no_trace(self):
        self.full_cycle()

    def test_systemd_uninstall_leaves_no_trace(self):
        box, result = self.full_cycle(systemctl="ok", linger="yes")
        systemctl = commands(result.calls, "systemctl")
        self.assertIn(["--user", "disable", UNIT_NAME], systemctl)
        self.assertIn(["--user", "daemon-reload"], systemctl)
        self.assertLess(systemctl.index(["--user", "disable", UNIT_NAME]),
                        len(systemctl) - 1 - systemctl[::-1].index(["--user", "daemon-reload"]))

    def test_xdg_uninstall_leaves_no_trace(self):
        box, _ = self.full_cycle(systemctl="ok", linger="yes", xdg=True)
        self.assertFalse(box.data.exists())
        self.assertFalse(box.unit.parent.exists())

    def test_empty_crontab_before_install_means_none_after(self):
        box = Sandbox(self)
        box.write_config()
        box.install()
        result = box.uninstall()
        self.assertEqual(result.returncode, 0, result.err)
        self.assertIsNone(box.crontab())

    @unittest.skipIf(DASH is None, "dash is not installed")
    def test_cycle_under_dash(self):
        self.full_cycle(shell=DASH)
        self.full_cycle(shell=DASH, systemctl="ok", linger="yes")

    def test_config_is_removed_before_anything_else(self):
        for sandbox in ({}, {"systemctl": "ok", "linger": "yes"}):
            with self.subTest(**sandbox):
                box = Sandbox(self, **sandbox)
                box.write_config()
                box.install()
                result = box.uninstall()
                self.assertTrue(result.calls)
                for call in result.calls:
                    self.assertFalse(call["config"], call)
                self.assertFalse(os.path.lexists(str(box.config)))

    def test_never_stops_or_kills(self):
        box = Sandbox(self, systemctl="ok", linger="yes")
        box.write_config()
        box.install()
        result = box.uninstall()
        for call in result.calls:
            self.assertIn(call["cmd"], ("systemctl", "crontab", "pgrep", "loginctl"), call)
            if call["cmd"] == "systemctl":
                self.assertIn(call["args"][1],
                              ("show-environment", "is-active", "show", "disable", "daemon-reload"),
                              call)

    def test_still_running_after_the_wait(self):
        for sandbox in ({}, {"systemctl": "ok", "linger": "yes"}):
            with self.subTest(**sandbox):
                box = Sandbox(self, **sandbox)
                box.set_crontab(b"0 3 * * * /usr/bin/true\n")
                box.write_config()
                box.install()
                box.set("running", "sticky")
                started = time.monotonic()
                result = box.uninstall(extra={"PHOPENCODE_UNINSTALL_WAIT": "2"})
                elapsed = time.monotonic() - started
                self.assertEqual(result.returncode, UNINSTALL_EXIT["still_running"], result.err)
                self.assertEqual(result.out, "uninstalled still-running\n")
                self.assertIn("still running", result.err)
                self.assertGreaterEqual(elapsed, 2)
                self.assertLess(elapsed, 30)
                self.assertFalse(box.pyz.exists())
                self.assertFalse(box.config.exists())
                self.assertFalse(box.unit.exists())
                self.assertEqual(box.crontab(), b"0 3 * * * /usr/bin/true\n")
                self.assertGreaterEqual(len(commands(result.calls, "pgrep")), 2)

    def test_waits_for_the_service_to_exit(self):
        box = Sandbox(self)
        box.write_config()
        box.install()
        # The stub's notifyd runs until the config is removed, like the real one.
        result = box.uninstall()
        self.assertEqual(result.returncode, 0, result.err)
        self.assertTrue(commands(result.calls, "pgrep"))

    def test_nothing_installed_is_ok_and_idempotent(self):
        for sandbox in ({}, {"systemctl": "ok", "linger": "yes"}, {"systemctl": "fail"}):
            with self.subTest(**sandbox):
                box = Sandbox(self, **sandbox)
                tree = snapshot(box.home)
                for _ in range(2):
                    result = box.uninstall()
                    self.assertEqual(result.returncode, 0, result.err)
                    self.assertEqual(result.out, "uninstalled\n")
                self.assertEqual(snapshot(box.home), tree)
                self.assertIsNone(box.crontab())

    def test_uninstall_twice_after_install(self):
        box = Sandbox(self, systemctl="ok", linger="yes")
        box.write_config()
        box.install()
        self.assertEqual(box.uninstall().returncode, 0)
        self.assertEqual(box.uninstall().returncode, 0)

    def test_systemd_unit_removed_even_when_systemctl_now_fails(self):
        box = Sandbox(self, systemctl="ok", linger="yes")
        tree = snapshot(box.home)
        box.write_config()
        box.install()
        box.set("systemctl", "fail")
        result = box.uninstall()
        # pgrep sees nothing, but systemd cannot be asked about its unit: unknown, not stopped.
        self.assertEqual(result.returncode, UNINSTALL_EXIT["unknown"], result.err)
        self.assertEqual(result.out, "uninstalled unknown\n")
        self.assertEqual(snapshot(box.home), tree)

    def test_install_refuses_when_a_unit_exists_but_systemctl_fails(self):
        box = Sandbox(self, systemctl="ok", linger="yes")
        box.write_config()
        self.assertEqual(box.install().returncode, 0)
        box.clear("running")
        box.set("systemctl", "fail")
        before = (snapshot(box.home), box.crontab())
        result = box.run(INSTALL, box.source)
        self.assertEqual(result.returncode, INSTALL_EXIT["unknown"], result.err)
        self.assertEqual((snapshot(box.home), box.crontab()), before)

    def test_keeps_files_it_did_not_create(self):
        box = Sandbox(self)
        box.write_config()
        box.install()
        other = box.data / "other.txt"
        other.write_text("user file")
        mine = box.config.parent / "other.json"
        mine.write_text("{}")
        result = box.uninstall()
        self.assertEqual(result.returncode, 0, result.err)
        self.assertEqual(other.read_text(), "user file")
        self.assertEqual(mine.read_text(), "{}")
        self.assertFalse(box.pyz.exists())

    def test_unreadable_crontab_is_reported_and_left_alone(self):
        box = Sandbox(self)
        box.write_config()
        box.install()
        cron = box.crontab()
        box.set("crontab_mode", "broken")
        result = box.uninstall()
        self.assertEqual(result.returncode, UNINSTALL_EXIT["cron_unreadable"], result.err)
        self.assertEqual(result.out, "uninstalled\n")
        self.assertIn(MARKER, result.err)
        self.assertEqual(box.crontab(), cron)
        self.assertEqual([a for a in commands(result.calls, "crontab") if a != ["-l"]], [])
        self.assertFalse(box.pyz.exists())

    def test_usage_error(self):
        box = Sandbox(self)
        result = box.uninstall("extra")
        self.assertEqual(result.returncode, UNINSTALL_EXIT["usage"], result.err)


class LocaleTest(unittest.TestCase):
    """crontab's "no crontab" message is translated; the scripts must read it in the C locale."""

    LOCALE = {"LC_ALL": "tr_TR.UTF-8", "LANG": "tr_TR.UTF-8", "LC_MESSAGES": "tr_TR.UTF-8"}

    def test_first_cron_install_and_uninstall_in_a_translated_locale(self):
        box = Sandbox(self)
        tree = snapshot(box.home)
        box.write_config()
        result = box.install(extra=self.LOCALE)
        self.assertEqual(result.returncode, 0, result.err)
        self.assertEqual(result.out, "installed cron\n")
        self.assertEqual(len(box.crontab().splitlines()), 1)
        result = box.uninstall(extra=self.LOCALE)
        self.assertEqual(result.returncode, 0, result.err)
        self.assertIsNone(box.crontab())
        self.assertEqual(snapshot(box.home), tree)

    def test_uninstall_with_nothing_installed_in_a_translated_locale(self):
        box = Sandbox(self)
        result = box.uninstall(extra=self.LOCALE)
        self.assertEqual(result.returncode, 0, result.err)
        self.assertEqual(result.out, "uninstalled\n")

    def test_status_in_a_translated_locale(self):
        box = Sandbox(self)
        result = box.install("--status", extra=self.LOCALE)
        self.assertEqual(result.out, "status none stopped\n", result.err)


class CronLineTest(unittest.TestCase):
    """The @reboot line must not depend on the crontab's SHELL (for example SHELL=/bin/csh)."""

    def test_line_runs_through_sh_whatever_the_crontab_shell(self):
        box = Sandbox(self)
        box.set_crontab(b"SHELL=/bin/csh\n")
        mark = box.root / "started"
        # A plain Python script: `python3 notifyd.pyz --config PATH` runs it like the archive.
        box.source.write_text(
            "import os, sys\n"
            "with open(os.environ['PHOPENCODE_TEST_MARK'], 'w') as f:\n"
            "    f.write(' '.join(sys.argv[1:]))\n"
            "print('stub notifyd ran')\n")
        box.write_config()
        result = box.install()
        self.assertEqual(result.returncode, 0, result.err)
        lines = box.crontab().decode().splitlines()
        self.assertEqual(lines[0], "SHELL=/bin/csh")
        line = lines[1]
        self.assertRegex(line, r"^@reboot /bin/sh -c '[^']*' " + MARKER + "$")
        command = line[len("@reboot "):]
        env = box.env({"SHELL": "/bin/csh", "PHOPENCODE_TEST_MARK": str(mark)})
        shells = [SHELL] + [s for s in (DASH, shutil.which("bash"), shutil.which("csh"),
                                        shutil.which("tcsh")) if s]
        for shell in shells:
            with self.subTest(shell=shell):
                if mark.exists():
                    mark.unlink()
                # cron runs `$SHELL -c "<command>"`.
                run = subprocess.run([shell, "-c", command], env=env, cwd=str(box.root),
                                     capture_output=True, timeout=60)
                self.assertEqual(run.returncode, 0, run.stderr)
                self.assertEqual(mark.read_text(), "--config %s" % box.config)
                self.assertIn(b"stub notifyd ran", box.log.read_bytes())
                self.assertEqual(stat.S_IMODE(os.stat(str(box.log)).st_mode), 0o600)


class LockTest(unittest.TestCase):
    """install.sh and uninstall.sh exclude each other with `flock -n` on the HOME directory.

    HOME always exists and the scripts never create, remove or recreate it, so there is no lock
    file and no path race.
    """

    def hold_lock(self, box):
        import fcntl
        fd = os.open(str(box.home), os.O_RDONLY | os.O_DIRECTORY)
        self.addCleanup(os.close, fd)
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return fd

    def start_slow_install(self, box):
        box.set("crontab_delay", "4")
        env = box.env()
        box.guard(env)
        proc = subprocess.Popen([SHELL, str(INSTALL), str(box.source)], env=env,
                                cwd=str(box.root), stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE)
        self.addCleanup(proc.wait)
        deadline = time.monotonic() + 30
        while not (box.state / "crontab_entered").exists():
            self.assertLess(time.monotonic(), deadline, "install never read the crontab")
            time.sleep(0.05)
        box.clear("crontab_delay")
        return proc

    def test_install_refuses_while_the_lock_is_held(self):
        box = Sandbox(self)
        box.write_config()
        self.hold_lock(box)
        before = snapshot(box.home)
        result = box.install()
        self.assertEqual(result.returncode, INSTALL_EXIT["busy"], result.err)
        self.assertIn("another", result.err)
        self.assertEqual(snapshot(box.home), before)
        self.assertNotIn("nohup", [c["cmd"] for c in result.calls])
        self.assertIsNone(box.crontab())

    def test_uninstall_refuses_while_the_lock_is_held_and_changes_nothing(self):
        box = Sandbox(self)
        box.write_config()
        self.assertEqual(box.install().returncode, 0)
        box.clear("running")
        self.hold_lock(box)
        before = (snapshot(box.home), box.crontab())
        result = box.uninstall()
        self.assertEqual(result.returncode, UNINSTALL_EXIT["busy"], result.err)
        self.assertEqual((snapshot(box.home), box.crontab()), before)
        self.assertTrue(box.config.exists())
        self.assertEqual(result.calls, [])

    def test_the_lock_creates_no_file(self):
        box = Sandbox(self)
        tree = snapshot(box.home)
        root = sorted(os.listdir(str(box.root)))
        box.write_config()
        self.assertEqual(box.install().returncode, 0)
        result = box.uninstall()
        self.assertEqual(result.returncode, 0, result.err)
        self.assertEqual(snapshot(box.home), tree)
        self.assertEqual(sorted(os.listdir(str(box.root))), root)
        for text in (INSTALL.read_text(), UNINSTALL.read_text()):
            self.assertNotIn(".lock", text)

    def test_two_concurrent_installs_start_exactly_one(self):
        box = Sandbox(self)
        box.write_config()
        first = self.start_slow_install(box)
        second = box.install()
        out, err = first.communicate(timeout=60)
        self.assertEqual(first.returncode, 0, err)
        self.assertEqual(out, b"installed cron\n")
        self.assertEqual(second.returncode, INSTALL_EXIT["busy"], second.err)
        self.assertEqual(second.out, "")
        self.assertEqual(len([c for c in box.calls() if c["cmd"] == "nohup"]), 1)
        self.assertEqual(len([l for l in box.crontab().splitlines()
                              if l.endswith(b" " + MARKER.encode())]), 1)

    def test_runs_that_finish_while_the_lock_is_held_never_let_a_third_in(self):
        box = Sandbox(self)
        box.write_config()
        holder = self.start_slow_install(box)
        # Each refused run goes through its whole exit path, traps included ...
        for script, args in ((UNINSTALL, ()), (INSTALL, (box.source,)), (UNINSTALL, ())):
            result = box.run(script, *args)
            self.assertEqual(result.returncode, 12, (script.name, result.err))
        # ... and the holder still excludes the next one.
        third = box.install()
        self.assertEqual(third.returncode, INSTALL_EXIT["busy"], third.err)
        self.assertTrue(box.config.exists())
        out, err = holder.communicate(timeout=60)
        self.assertEqual(holder.returncode, 0, err)
        self.assertEqual(len([c for c in box.calls() if c["cmd"] == "nohup"]), 1)
        # Once the holder has exited the lock is free; the started notifyd did not inherit it.
        box.clear("running")
        result = box.uninstall()
        self.assertEqual(result.returncode, 0, result.err)


class UnknownStateTest(unittest.TestCase):
    """A check that fails or cannot run is never taken as "not running" or "nothing there"."""

    def test_systemd_install_refuses_when_the_crontab_cannot_be_read(self):
        box = Sandbox(self, systemctl="ok", linger="yes")
        box.set_crontab(b"0 3 * * * /usr/bin/true\n")
        box.set("crontab_mode", "broken")
        box.write_config()
        before = snapshot(box.home)
        result = box.install()
        self.assertEqual(result.returncode, INSTALL_EXIT["no_manager"], result.err)
        self.assertIn("crontab", result.err)
        self.assertEqual(snapshot(box.home), before)
        self.assertEqual(result.out, "")
        for args in commands(result.calls, "systemctl"):
            self.assertNotIn(args[1], ("enable", "start", "daemon-reload", "disable"))

    def test_migration_to_systemd_refuses_when_the_crontab_cannot_be_read(self):
        box = Sandbox(self, systemctl="ok", linger="no")
        box.write_config()
        self.assertEqual(box.install().out, "installed cron\n")
        cron = box.crontab()
        box.clear("running")
        box.set("linger", "yes")
        box.set("crontab_mode", "broken")
        result = box.install()
        self.assertEqual(result.returncode, INSTALL_EXIT["no_manager"], result.err)
        self.assertFalse(box.unit.exists())
        self.assertEqual(box.crontab(), cron)

    def test_install_refuses_when_pgrep_fails(self):
        box = Sandbox(self)
        box.write_config()
        box.set("pgrep", "error")
        before = snapshot(box.home)
        result = box.install()
        self.assertEqual(result.returncode, INSTALL_EXIT["unknown"], result.err)
        self.assertEqual(snapshot(box.home), before)
        self.assertNotIn("nohup", [c["cmd"] for c in result.calls])

    def test_install_refuses_when_the_unit_state_is_unknown(self):
        box = Sandbox(self, systemctl="ok", linger="yes")
        box.write_config()
        self.assertEqual(box.install().returncode, 0)
        box.clear("running")
        box.set("is_active", "error")
        result = box.install()
        self.assertEqual(result.returncode, INSTALL_EXIT["unknown"], result.err)
        self.assertNotIn(["--user", "start", UNIT_NAME], commands(result.calls, "systemctl"))

    def test_status_reports_unknown(self):
        box = Sandbox(self)
        box.set("pgrep", "error")
        result = box.install("--status")
        self.assertEqual((result.returncode, result.out), (0, "status none unknown\n"),
                         result.err)

    def test_uninstall_with_failing_pgrep_reports_unknown(self):
        box = Sandbox(self)
        tree = snapshot(box.home)
        box.write_config()
        self.assertEqual(box.install().returncode, 0)
        box.set("pgrep", "error")
        result = box.uninstall()
        self.assertEqual(result.returncode, UNINSTALL_EXIT["unknown"], result.err)
        self.assertEqual(result.out, "uninstalled unknown\n")
        self.assertIn("cannot tell", result.err)
        self.assertEqual(snapshot(box.home), tree)
        self.assertIsNone(box.crontab())

    def test_uninstall_without_pgrep_reports_unknown(self):
        for sandbox in ({}, {"systemctl": "ok", "linger": "yes"}):
            with self.subTest(**sandbox):
                box = Sandbox(self, **sandbox)
                tree = snapshot(box.home)
                box.write_config()
                self.assertEqual(box.install().returncode, 0)
                box.clear("running")
                os.unlink(str(box.stubs / "pgrep"))
                result = box.uninstall()
                self.assertEqual(result.returncode, UNINSTALL_EXIT["unknown"], result.err)
                self.assertEqual(result.out, "uninstalled unknown\n")
                self.assertEqual(snapshot(box.home), tree)

    def test_uninstall_with_unknown_unit_state_reports_unknown(self):
        box = Sandbox(self, systemctl="ok", linger="yes")
        box.write_config()
        self.assertEqual(box.install().returncode, 0)
        box.clear("running")
        box.set("is_active", "error")
        result = box.uninstall()
        self.assertEqual(result.returncode, UNINSTALL_EXIT["unknown"], result.err)
        self.assertEqual(result.out, "uninstalled unknown\n")
        self.assertFalse(box.unit.exists())

    def test_uninstall_waits_when_one_check_fails_but_another_sees_it_running(self):
        box = Sandbox(self, systemctl="ok", linger="yes")
        box.write_config()
        self.assertEqual(box.install().returncode, 0)
        box.set("running", "sticky")
        box.set("pgrep", "error")
        result = box.uninstall(extra={"PHOPENCODE_UNINSTALL_WAIT": "2"})
        self.assertEqual(result.returncode, UNINSTALL_EXIT["still_running"], result.err)
        self.assertEqual(result.out, "uninstalled still-running\n")


class UnitStateTest(unittest.TestCase):
    """systemd's ActiveState/SubState: strict after the start, cautious before and on uninstall."""

    def systemd_box(self):
        box = Sandbox(self, systemctl="ok", linger="yes")
        box.write_config()
        return box

    def test_start_confirmed_only_by_active_running(self):
        box = self.systemd_box()
        box.set("start_unit_state", "active running")
        result = box.install()
        self.assertEqual(result.returncode, 0, result.err)
        self.assertEqual(result.out, "installed systemd\n")
        self.assertIn(["--user", "show", "-p", "ActiveState", "-p", "SubState", UNIT_NAME],
                      commands(result.calls, "systemctl"))

    def test_auto_restart_after_the_start_means_it_exited(self):
        box = self.systemd_box()
        box.set("start", "dies")
        box.set("start_unit_state", "activating auto-restart")
        result = box.install()
        self.assertEqual(result.returncode, INSTALL_EXIT["died"], result.err)
        self.assertEqual(result.out, "")
        self.assertIn("journalctl", result.err)

    def test_auto_restart_after_the_start_is_not_hidden_by_a_live_process(self):
        # Even if a process matches for a moment, systemd's own answer decides after the start.
        box = self.systemd_box()
        box.set("start_unit_state", "activating auto-restart")
        result = box.install()
        self.assertEqual(result.returncode, INSTALL_EXIT["died"], result.err)

    def test_other_states_after_the_start_are_unknown(self):
        for state in ("error", "activating start", "reloading running", "active exited"):
            with self.subTest(state=state):
                box = self.systemd_box()
                box.set("start_unit_state", state)
                result = box.install()
                self.assertEqual(result.returncode, INSTALL_EXIT["unknown"], result.err)
                self.assertEqual(result.out, "")

    def test_auto_restart_before_install_counts_as_running(self):
        box = self.systemd_box()
        self.assertEqual(box.install().returncode, 0)
        box.clear("running")
        box.set("unit_state", "activating auto-restart")
        result = box.install()
        self.assertEqual(result.returncode, INSTALL_EXIT["running"], result.err)

    def test_uninstall_waits_on_auto_restart(self):
        box = self.systemd_box()
        self.assertEqual(box.install().returncode, 0)
        box.clear("running")  # no process right now: it is between restarts
        box.set("unit_state", "activating auto-restart")
        result = box.uninstall(extra={"PHOPENCODE_UNINSTALL_WAIT": "2"})
        self.assertEqual(result.returncode, UNINSTALL_EXIT["still_running"], result.err)
        self.assertEqual(result.out, "uninstalled still-running\n")
        self.assertFalse(box.unit.exists())

    def test_uninstall_with_unreadable_unit_state_reports_unknown(self):
        box = self.systemd_box()
        self.assertEqual(box.install().returncode, 0)
        box.clear("running")
        box.set("unit_state", "error")
        result = box.uninstall()
        self.assertEqual(result.returncode, UNINSTALL_EXIT["unknown"], result.err)
        self.assertEqual(result.out, "uninstalled unknown\n")


class LockFailureTest(unittest.TestCase):
    """A lock that cannot be taken for a reason other than contention is not "busy"."""

    def broken_flock(self, box):
        # Like flock(1) on an NFS home whose lock emulation needs a writable descriptor.
        path = box.stubs / "flock"
        path.write_text("#!/bin/sh\necho 'flock: 9: Bad file descriptor' >&2\nexit 65\n")
        path.chmod(0o755)

    def test_install_reports_a_lock_failure(self):
        box = Sandbox(self)
        box.write_config()
        self.broken_flock(box)
        before = snapshot(box.home)
        result = box.install()
        self.assertEqual(result.returncode, INSTALL_EXIT["lock_failed"], result.err)
        self.assertNotIn("another", result.err)
        self.assertIn("lock", result.err)
        self.assertEqual(result.out, "")
        self.assertEqual(snapshot(box.home), before)
        self.assertEqual(result.calls, [])

    def test_uninstall_reports_a_lock_failure_and_changes_nothing(self):
        box = Sandbox(self)
        box.write_config()
        self.assertEqual(box.install().returncode, 0)
        box.clear("running")
        self.broken_flock(box)
        before = (snapshot(box.home), box.crontab())
        result = box.uninstall()
        self.assertEqual(result.returncode, UNINSTALL_EXIT["lock_failed"], result.err)
        self.assertNotIn("another", result.err)
        self.assertEqual(result.out, "")
        self.assertEqual((snapshot(box.home), box.crontab()), before)
        self.assertEqual(result.calls, [])

    def test_contention_is_still_busy(self):
        import fcntl
        box = Sandbox(self)
        box.write_config()
        fd = os.open(str(box.home), os.O_RDONLY | os.O_DIRECTORY)
        self.addCleanup(os.close, fd)
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        self.assertEqual(box.install().returncode, INSTALL_EXIT["busy"])
        self.assertEqual(box.uninstall().returncode, UNINSTALL_EXIT["busy"])


class MissingCrontabUninstallTest(unittest.TestCase):
    """Without crontab, uninstall cannot rule out a leftover @reboot line: never plain success."""

    def test_cron_install_then_crontab_missing(self):
        box = Sandbox(self)
        box.write_config()
        self.assertEqual(box.install().returncode, 0)
        cron = box.crontab()
        box.clear("running")
        os.unlink(str(box.stubs / "crontab"))
        result = box.uninstall()
        self.assertEqual(result.returncode, UNINSTALL_EXIT["cron_unreadable"], result.err)
        self.assertEqual(result.out, "uninstalled\n")
        self.assertIn("crontab", result.err)
        self.assertIn(MARKER, result.err)
        self.assertEqual(box.crontab(), cron)
        self.assertFalse(box.pyz.exists())

    def test_nothing_installed_and_no_crontab_is_not_certain(self):
        box = Sandbox(self, systemctl="ok", linger="yes")
        os.unlink(str(box.stubs / "crontab"))
        result = box.uninstall()
        self.assertEqual(result.returncode, UNINSTALL_EXIT["cron_unreadable"], result.err)
        self.assertEqual(result.out, "uninstalled\n")


def _closed_port():
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()
    return port


def _processes_under(path):
    """PIDs of this user's processes whose command line mentions path."""
    pids = []
    needle = str(path).encode()
    for entry in os.listdir("/proc"):
        if not entry.isdigit():
            continue
        try:
            if os.stat("/proc/" + entry).st_uid != os.getuid():
                continue
            with open("/proc/%s/cmdline" % entry, "rb") as handle:
                cmdline = handle.read()
        except OSError:
            continue
        if needle in cmdline and int(entry) != os.getpid():
            pids.append((int(entry), cmdline))
    return pids


@unittest.skipUnless(os.path.isdir("/proc") and shutil.which("pgrep", path="/usr/bin:/bin")
                     and shutil.which("nohup", path="/usr/bin:/bin"),
                     "needs /proc, pgrep and nohup")
class EndToEndTest(unittest.TestCase):

    def test_real_notifyd_installed_by_cron_exits_after_uninstall(self):
        box = Sandbox(self, systemctl="absent", real=("pgrep", "nohup", "setsid"))
        self.addCleanup(self._reap, box.root)
        build = subprocess.run(
            [sys.executable, str(BUILD_SCRIPT), "--output", str(box.source)],
            capture_output=True, timeout=120)
        self.assertEqual(build.returncode, 0, build.stderr)
        port = _closed_port()
        self.assertNotEqual(port, 4096)
        tree = snapshot(box.home)
        box.write_config(server_url="http://127.0.0.1:%d" % port)

        result = box.install()
        self.assertEqual(result.returncode, 0, result.err)
        self.assertEqual(result.out, "installed cron\n")
        running = _processes_under(box.pyz)
        self.assertEqual(len(running), 1, running)
        for sentinel in SENTINELS:
            self.assertNotIn(sentinel.encode(), running[0][1])
        time.sleep(1)
        log = box.log.read_bytes()
        self.assertIn(b"notifyd started", log)
        for sentinel in SENTINELS:
            self.assertNotIn(sentinel.encode(), log)

        started = time.monotonic()
        result = box.uninstall(timeout=180)
        self.assertEqual(result.returncode, 0, result.err)
        self.assertEqual(result.out, "uninstalled\n")
        self.assertLess(time.monotonic() - started, 70)
        self.assertEqual(_processes_under(box.pyz), [])
        self.assertEqual(snapshot(box.home), tree)
        self.assertIsNone(box.crontab())

    @staticmethod
    def _reap(root):
        # Only the notifyd this test started, found by its temporary path.
        for pid, _ in _processes_under(root):
            try:
                os.kill(pid, signal.SIGTERM)
            except OSError:
                pass


if __name__ == "__main__":
    unittest.main()
