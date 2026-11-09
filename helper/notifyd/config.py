"""The service's configuration file. Standard library only, Python 3.9+.

The file is one JSON object, UTF-8, at most MAX_FILE_BYTES:

    {
      "server_url": "http://127.0.0.1:4096",   required; http or https, no credentials
      "username": "opencode",                   optional; basic-auth user, default "opencode"
      "password": "<password>",                 required; basic-auth password
      "ntfy_server": "https://ntfy.sh",         optional; https only, default https://ntfy.sh
      "ntfy_topic": "<topic>"                   required; [A-Za-z0-9_-]{1,64}
    }

Unknown and repeated fields are errors, so a typo cannot silently drop a setting.

The file must be a regular file (not a symlink), owned by the user running the service, with
mode exactly 0600: it holds the server password and the ntfy topic, and anyone who knows the
topic can read the notifications.

The password and the topic are secrets. They are held as Secret, whose str and repr are a mask,
and no ConfigError message ever repeats a value read from the file, only field names.
"""
import errno
import hmac
import json
import os
import re
import stat
from pathlib import Path
from typing import Any, Dict, List, Mapping, Optional, Tuple, Union
from urllib.parse import urlsplit

DEFAULT_USERNAME = "opencode"
DEFAULT_NTFY_SERVER = "https://ntfy.sh"
REQUIRED_MODE = 0o600
MAX_FILE_BYTES = 64 * 1024

CONFIG_DIRECTORY = "phopencode"
CONFIG_FILE_NAME = "notifyd.json"

REQUIRED_FIELDS = ("server_url", "password", "ntfy_topic")
OPTIONAL_FIELDS = {"username": DEFAULT_USERNAME, "ntfy_server": DEFAULT_NTFY_SERVER}
ALL_FIELDS = REQUIRED_FIELDS + tuple(OPTIONAL_FIELDS)

_TOPIC = re.compile(r"[A-Za-z0-9_-]{1,64}")
_CONTROL = re.compile(r"[\x00-\x1f\x7f]")
_URL_UNSAFE = re.compile(r"[\x00-\x20\x7f\\]")

_MASK = "<hidden>"


class ConfigError(Exception):
    """The configuration cannot be used. The message names fields, never their values."""


class Secret:
    """A string that never shows itself in str, repr or format. Call reveal() to use it."""

    __slots__ = ("_value",)

    def __init__(self, value: str) -> None:
        if not isinstance(value, str):
            raise TypeError("Secret holds a string")
        self._value = value

    def reveal(self) -> str:
        return self._value

    def __repr__(self) -> str:
        return "Secret(" + _MASK + ")"

    def __str__(self) -> str:
        return _MASK

    def __format__(self, spec: str) -> str:
        return format(_MASK, spec)

    def __eq__(self, other: object) -> bool:
        if not isinstance(other, Secret):
            return NotImplemented
        # surrogatepass: a Secret built directly may hold a lone surrogate; comparing never raises.
        mine = self._value.encode("utf-8", "surrogatepass")
        theirs = other._value.encode("utf-8", "surrogatepass")
        return hmac.compare_digest(mine, theirs)

    __hash__ = None  # type: ignore[assignment]

    def __reduce__(self) -> Any:
        raise TypeError("a Secret is not pickled")


class Config:
    """A validated configuration. Build it with load() or from_mapping()."""

    __slots__ = ("server_url", "username", "password", "ntfy_server", "ntfy_topic")

    def __init__(
        self,
        server_url: str,
        username: str,
        password: Secret,
        ntfy_server: str,
        ntfy_topic: Secret,
    ) -> None:
        self.server_url = server_url
        self.username = username
        self.password = password
        self.ntfy_server = ntfy_server
        self.ntfy_topic = ntfy_topic

    def __repr__(self) -> str:
        return (
            "Config(server_url=%r, username=%r, password=%r, ntfy_server=%r, ntfy_topic=%r)"
            % (self.server_url, self.username, self.password, self.ntfy_server, self.ntfy_topic)
        )

    __str__ = __repr__

    def __reduce__(self) -> Any:
        raise TypeError("a Config is not pickled")


def default_path(environ: Optional[Mapping[str, str]] = None) -> Path:
    """`$XDG_CONFIG_HOME/phopencode/notifyd.json` when that is absolute, else under ~/.config."""
    if environ is None:
        environ = os.environ
    base = environ.get("XDG_CONFIG_HOME", "")
    if not os.path.isabs(base):
        home = environ.get("HOME", "")
        if not os.path.isabs(home):
            raise ConfigError("neither XDG_CONFIG_HOME nor HOME is an absolute path")
        base = os.path.join(home, ".config")
    return Path(base) / CONFIG_DIRECTORY / CONFIG_FILE_NAME


def load(path: Union[str, "os.PathLike[str]"]) -> Config:
    """Reads, checks and parses the configuration file at path. Raises ConfigError."""
    data = _read_private_file(os.fspath(path))
    return from_mapping(_parse_json(data))


def from_mapping(fields: Mapping[str, Any]) -> Config:
    """Validates already parsed fields. Raises ConfigError."""
    unknown = sorted(name for name in fields if name not in ALL_FIELDS)
    if unknown:
        raise ConfigError("unknown fields: " + ", ".join(_safe_names(unknown)))
    missing = [name for name in REQUIRED_FIELDS if name not in fields]
    if missing:
        raise ConfigError("missing fields: " + ", ".join(missing))
    values = {}  # type: Dict[str, str]
    for name in ALL_FIELDS:
        value = fields.get(name, OPTIONAL_FIELDS.get(name))
        if not isinstance(value, str):
            raise ConfigError("%s must be a string" % name)
        if not value:
            raise ConfigError("%s must not be empty" % name)
        if not _is_utf8(value):
            raise ConfigError("%s is not valid UTF-8 text" % name)
        values[name] = value

    username = values["username"]
    if ":" in username or _CONTROL.search(username):
        raise ConfigError("username must not contain ':' or control characters")
    password = values["password"]
    if _CONTROL.search(password):
        raise ConfigError("password must not contain control characters")
    topic = values["ntfy_topic"]
    if not _TOPIC.fullmatch(topic):
        raise ConfigError("ntfy_topic must be 1 to 64 of A-Z, a-z, 0-9, '_' and '-'")

    return Config(
        server_url=_check_url("server_url", values["server_url"], ("http", "https")),
        username=username,
        password=Secret(password),
        ntfy_server=_check_url("ntfy_server", values["ntfy_server"], ("https",)),
        ntfy_topic=Secret(topic),
    )


def _read_private_file(path: str) -> bytes:
    # OSErrors become ConfigErrors raised outside the except blocks, so callers handle one
    # exception type and no OSError is chained onto the error.
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_CLOEXEC", 0)
    flags |= getattr(os, "O_NONBLOCK", 0)  # a FIFO must not block the open
    failure = None  # type: Optional[str]
    fd = -1
    try:
        fd = os.open(path, flags)
    except OSError as error:
        failure = _open_failure(path, error.errno)
    if failure is not None:
        raise ConfigError(failure)
    data = None  # type: Optional[bytes]
    try:
        data = _read_checked(fd, path)
    except OSError:
        failure = "cannot read config file %s" % path
    finally:
        try:
            os.close(fd)
        except OSError:
            pass
    if failure is not None or data is None:
        raise ConfigError(failure or "cannot read config file %s" % path)
    return data


def _open_failure(path: str, code: Optional[int]) -> str:
    if code == errno.ENOENT:
        return "config file %s not found" % path
    if code == errno.ELOOP:
        return "config file %s is a symlink" % path
    if code in (errno.EACCES, errno.EPERM):
        return "config file %s cannot be opened: permission denied" % path
    return "cannot open config file %s" % path


def _read_checked(fd: int, path: str) -> bytes:
    """Checks the open file, then reads it. Raises ConfigError or OSError."""
    info = os.fstat(fd)
    if not stat.S_ISREG(info.st_mode):
        raise ConfigError("config file %s is not a regular file" % path)
    if hasattr(os, "getuid") and info.st_uid != os.getuid():
        raise ConfigError("config file %s is not owned by the user running the service" % path)
    if stat.S_IMODE(info.st_mode) != REQUIRED_MODE:
        raise ConfigError(
            "config file %s has mode %04o; it must be 0600" % (path, stat.S_IMODE(info.st_mode))
        )
    if info.st_size > MAX_FILE_BYTES:
        raise ConfigError("config file %s is too large" % path)
    chunks = []  # type: List[bytes]
    total = 0
    while True:
        chunk = os.read(fd, MAX_FILE_BYTES + 1 - total)
        if not chunk:
            break
        chunks.append(chunk)
        total += len(chunk)
        if total > MAX_FILE_BYTES:
            raise ConfigError("config file %s is too large" % path)
    return b"".join(chunks)


def _is_utf8(value: str) -> bool:
    try:
        value.encode("utf-8")
    except UnicodeEncodeError:
        return False
    return True


def _parse_json(data: bytes) -> Dict[str, Any]:
    # Errors are raised outside the except blocks: a chained exception would carry the
    # document (JSONDecodeError.doc, UnicodeDecodeError.object), secrets included.
    failure = None  # type: Optional[str]
    parsed = None  # type: Any
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        text = None
        failure = "config file is not valid UTF-8"
    if text is not None:
        try:
            parsed = json.loads(text, object_pairs_hook=_reject_duplicates)
        except _DuplicateField as error:
            failure = "field %s appears more than once" % error.name
        except ValueError as error:
            line = getattr(error, "lineno", None)
            column = getattr(error, "colno", None)
            failure = "config file is not valid JSON"
            if line is not None and column is not None:
                failure += " (line %d, column %d)" % (line, column)
        except RecursionError:
            failure = "config file is not valid JSON"
    if failure is not None:
        raise ConfigError(failure)
    if not isinstance(parsed, dict):
        raise ConfigError("config file must hold a JSON object")
    return parsed


class _DuplicateField(Exception):
    def __init__(self, name: str) -> None:
        super().__init__()
        self.name = name


def _reject_duplicates(pairs: List[Tuple[str, Any]]) -> Dict[str, Any]:
    result = {}  # type: Dict[str, Any]
    for key, value in pairs:
        if key in result:
            raise _DuplicateField(_safe_names([key])[0])
        result[key] = value
    return result


def _safe_names(names: List[str]) -> List[str]:
    """Field names for messages: a known name, or an unknown one only if it looks like a name."""
    safe = []
    for name in names:
        if name in ALL_FIELDS or re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,31}", name):
            safe.append(name)
        else:
            safe.append("<unnamed>")
    return safe


def _check_url(name: str, value: str, schemes: Tuple[str, ...]) -> str:
    allowed = " or ".join(schemes)
    if _URL_UNSAFE.search(value):
        raise ConfigError("%s must not contain spaces or control characters" % name)
    try:
        parts = urlsplit(value)
        port = parts.port
    except ValueError:
        parts = None
        port = None
    if parts is None:
        raise ConfigError("%s is not a valid URL" % name)
    if parts.scheme.lower() not in schemes:
        raise ConfigError("%s must be an %s URL" % (name, allowed))
    if "@" in parts.netloc or parts.username is not None or parts.password is not None:
        raise ConfigError("%s must not contain credentials" % name)
    if not parts.hostname:
        raise ConfigError("%s must name a host" % name)
    if port is not None and port == 0:
        raise ConfigError("%s has an invalid port" % name)
    if parts.query or parts.fragment or "?" in value or "#" in value:
        raise ConfigError("%s must not have a query or a fragment" % name)
    return value.rstrip("/")
