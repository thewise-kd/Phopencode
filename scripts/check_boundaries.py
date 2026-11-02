#!/usr/bin/env python3
"""Checks module boundaries and scans for secrets. Standard library only.

Prints problems as `path:line: message` and exits 1 when there are any. Exits 2
when the check itself cannot run, such as outside a git checkout. Reports never
repeat the matched value: CI logs of this public repository are public too.

Boundaries: no `android.*` or `androidx.*` import in a JVM module of the module
map in docs/PHASES.md. An import is read the way a compiler reads it: a comment
separates tokens, whitespace and line breaks may sit between them, and comments
and string contents do not count as imports.

Secrets: every file git tracks, plus untracked files that are not ignored, so
that a local run agrees with CI before `git add`. Ignored files such as
`local.properties` stay out. It looks for private key blocks, token formats,
credentials in URLs and Authorization headers, ntfy topics, hard-coded secret
assignments and literal server addresses.

How a legitimate value avoids a false positive, and why a real secret cannot
use the same way out:
- There is no path allowlist and no inline marker. Test files are scanned like
  everything else, so tests assemble fake secrets at runtime from split strings.
- A value is a placeholder only when all of it has a placeholder shape: `<name>`,
  `$NAME`, `${name}`, `{name}`, `{{ name }}`, `${{ context.name }}`, `%s`, `***`,
  `xxx`, or nothing but placeholder words such as `test-password`. Any other
  character disqualifies it, so `test-Abc123` is reported. Digits never count as
  placeholder text, so neither `$ecret123` nor `<abc123>` passes. `${NAME:-text}`
  does not pass either: its default is a literal value.
- `$(command)` counts in a shell statement only, quoted or not, and only when it
  is the entire value and closes on its line. Anything next to it is a literal,
  and in single quotes or in a config file it is plain text; a YAML `run:` line
  is the one config line that holds shell.
- A quoted placeholder must end its word: `"$VALUE"literal` holds a literal.
- Credentials for a reserved domain (RFC 2606) cannot open a real server.
- A private key header alone is a format constant; only a header followed by a
  key body is reported.
- Files with NUL bytes get the exact formats only, the heuristics would read
  noise. That keeps a NUL byte from hiding a key or a token.
"""
import argparse
import bisect
import ipaddress
import os
import re
import subprocess
import sys
from pathlib import Path

DEFAULT_ROOT = Path(__file__).resolve().parents[1]

JVM_MODULE = re.compile(
    r"(core/(?:model|api|api-http|session|ssh|remote|files|git|storage-api|navigation|testing)"
    r"|feature/[^/]+/logic)/"
)
SOURCE_SUFFIXES = {".kt", ".kts", ".java"}
ANDROID_IMPORT = re.compile(
    r"(?<![\w.])import\s+(?:static\s+)?(androidx?(?:\s*\.\s*(?:\w+|\*))+)"
)
CHAR_LITERAL = re.compile(r"'(?:\\.[^'\n]*|[^'\\\n])'")

PEM_HEADER = re.compile(r"-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY(?: BLOCK)?-----")
PEM_BODY = re.compile(r"[A-Za-z0-9+/=]{40,}")
PEM_BODY_LINE = re.compile(r"[A-Za-z0-9+/=]+")
PEM_BODY_MINIMUM = 40
PEM_BODY_WINDOW = 400

_START = r"(?<![A-Za-z0-9_])"
_END = r"(?![A-Za-z0-9])"
TOKEN_FORMATS = (
    ("GitHub", re.compile(rf"{_START}gh[pousr]_[A-Za-z0-9]{{36,}}")),
    ("GitHub", re.compile(rf"{_START}github_pat_[A-Za-z0-9_]{{22,}}")),
    ("AWS", re.compile(rf"{_START}(?:AKIA|ASIA)[0-9A-Z]{{16}}{_END}")),
    ("Anthropic", re.compile(rf"{_START}sk-ant-[A-Za-z0-9_-]{{20,}}")),
    ("OpenAI", re.compile(rf"{_START}sk-(?!ant-)[A-Za-z0-9_-]{{20,}}")),
    ("Slack", re.compile(rf"{_START}xox[abeoprs]-[A-Za-z0-9-]{{10,}}")),
    ("Google", re.compile(rf"{_START}AIza[0-9A-Za-z_-]{{35}}")),
    ("Stripe", re.compile(rf"{_START}[sr]k_live_[0-9a-zA-Z]{{16,}}")),
    ("npm", re.compile(rf"{_START}npm_[A-Za-z0-9]{{36}}")),
    ("GitLab", re.compile(rf"{_START}glpat-[A-Za-z0-9_-]{{20,}}")),
    ("ntfy", re.compile(rf"{_START}tk_[a-z0-9]{{29,}}")),
    ("Z.AI", re.compile(rf"{_START}[0-9a-f]{{32}}\.[A-Za-z0-9]{{16}}{_END}")),
    ("JWT", re.compile(rf"{_START}eyJ[A-Za-z0-9_-]{{10,}}\.eyJ[A-Za-z0-9_-]{{10,}}\.[A-Za-z0-9_-]{{10,}}")),
)
# Kebab-case names can have the OpenAI shape; a generated key has digits.
NEEDS_A_DIGIT = {"OpenAI"}

AUTHORIZATION = re.compile(
    r"""authorization["']?[ \t]*[:=,][ \t]*["']?(?:bearer|basic)[ \t]+([A-Za-z0-9+/=_.~-]{16,})""",
    re.I,
)
URL_CREDENTIALS = re.compile(
    r"""\b[a-zA-Z][a-zA-Z0-9+.-]*://[^\s:/@'"`]+:([^\s/'"`]+)@([^\s/:?#'"`<>)\]]*)"""
)
NTFY_URL = re.compile(r"(?<![\w.-])ntfy\.sh/([A-Za-z0-9_-]+)")
NTFY_SITE_PATHS = {"docs", "app", "v1", "pricing", "account", "login", "signup", "static"}

_KEYWORD = (
    "password|passwd|passphrase|secret|api[_-]?key|auth[_-]?token|access[_-]?token"
    "|private[_-]?key|ntfy[_-]?topic"
)
QUOTED_ASSIGNMENT = re.compile(
    rf"""({_KEYWORD})["']?[ \t]*(?::[ \t]*[\w.<>?,]+[ \t]*)?(?::|=(?!=))[ \t]*"""
    r"""(?:"([^"\n]*)"|'([^'\n]*)')""",
    re.I,
)
# What may follow a quoted value that ends its word: space or punctuation of the
# surrounding text, a quote closing an outer string, perhaps after a line ending
# escape, a sentence's full stop, or a method call on it. Anything else is more of
# the same word, so a literal.
QUOTED_WORD_END = re.compile(
    r"""[\s;|&),\]}>/`]|(?:\\[nrt])?["'](?:[\s,)\]}]|$)|\.(?:\s|$)|\.\w+\(|$""", re.M
)
SHELL_WORD_END = " \t\r;|&)"
# An unquoted value that can hold spaces. It must end there, or `<a>b` would pass
# as `<a>`; the ordinary value pattern then reads the whole of it.
_DELIMITED_VALUE = r"""(?:\$?\{\{[^\n]*?\}\}|\$\{[^{}\n]*\}|<[^<>\n]*>)(?=[\s"';#]|$)"""
SHELL_ASSIGNMENT = re.compile(
    r"(?<![\w-])[A-Z0-9_]*"
    r"(PASSWORD|PASSWD|PASSPHRASE|SECRET|API_?KEY|AUTH_?TOKEN|ACCESS_?TOKEN|PRIVATE_?KEY|NTFY_?TOPIC)"
    r"=(" + _DELIMITED_VALUE + r"""|[^\s"';]+)""",
    re.M,
)
CONFIG_ASSIGNMENT = re.compile(
    rf"({_KEYWORD})[ \t]*([:=])[ \t]*(" + _DELIMITED_VALUE + r"""|[^\s"'#]+)""",
    re.I | re.M,
)
YAML_SUFFIXES = {".yml", ".yaml"}
CONFIG_SUFFIXES = YAML_SUFFIXES | {".properties", ".env", ".ini", ".conf", ".cfg", ".toml"}

IP_ADDRESS = re.compile(
    r"""(?:://|@|(?:host(?:name)?|server|address|addr|\bip)["']?[ \t]*[:=][ \t]*["']?)"""
    r"(\d{1,3}(?:\.\d{1,3}){3})(?![\d.])",
    re.I,
)
EMULATOR_HOST = ipaddress.ip_address("10.0.2.2")
DOCUMENTATION_NETWORKS = tuple(
    ipaddress.ip_network(network)
    for network in ("192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24")
)

RESERVED_DOMAINS = ("example.com", "example.net", "example.org")
RESERVED_TLDS = ("example", "invalid", "test")

SHORTEST_SECRET = 4
LONGEST_PLACEHOLDER = 64
PLACEHOLDER_SHAPE = re.compile(
    r"\*+|[xX]+|\.+|…+|%s|%\([A-Za-z_]+\)s"
    r"|<[^<>\d]*>"  # <password>, <Z.AI key>
    r"|\{[A-Za-z_]+\}"  # {value}
    r"|\{\{[^{}\d]*\}\}"  # {{ value }}
    r"|\$\{\{[ \t]*[A-Za-z_]+\.[\w.-]+[ \t]*\}\}"  # ${{ secrets.VALUE_2 }}
    r"|\$\{[A-Za-z_]\w*(?::-|:\?[^{}]*)\}"  # ${VALUE:-}, ${VALUE:?not set}
    r"|(?:\$[A-Za-z_]+|\$\{[^{}\d:=-]*\}|[:/@.-])+"  # $VALUE, ${value}, $a:$b
)
PLACEHOLDER_WORDS = re.compile(
    "(?:fake|dummy|test|testing|example|sample|placeholder|changeme|change|me|your|my|here"
    "|the|replace|redacted|none|null|nil|empty|unset|todo|tbd|true|false|generated|random"
    "|hidden|masked|sentinel|password|passwd|pass|pw|secret|token|key|value|topic|user"
    "|username|api|auth|access|server|client|ntfy)+"
)

PLACEHOLDER_ADVICE = "use a placeholder such as <value>, or build test values at runtime"


class CheckError(Exception):
    """The check could not run; this says nothing about the repository."""


def is_jvm_module(path):
    return JVM_MODULE.match(path) is not None


def is_placeholder(value):
    value = value.strip()
    if len(value) < SHORTEST_SECRET or PLACEHOLDER_SHAPE.fullmatch(value):
        return True
    if len(value) > LONGEST_PLACEHOLDER:
        return False
    return PLACEHOLDER_WORDS.fullmatch(re.sub(r"[-_ .]", "", value.lower())) is not None


def is_reserved_host(host):
    host = host.lower()
    return (
        host.rsplit(".", 1)[-1] in RESERVED_TLDS
        or any(host == domain or host.endswith("." + domain) for domain in RESERVED_DOMAINS)
    )


def is_server_address(text):
    try:
        address = ipaddress.ip_address(text)
    except ValueError:
        return False
    return not (
        address.is_loopback
        or address.is_unspecified
        or address.is_link_local
        or address.is_multicast
        or address == EMULATOR_HOST
        or any(address in network for network in DOCUMENTATION_NETWORKS)
    )


def list_files(root):
    """Returns repository-relative paths: tracked, plus untracked but not ignored."""
    try:
        result = subprocess.run(
            ["git", "-C", str(root), "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except OSError as error:
        raise CheckError(f"cannot run git: {error}") from error
    if result.returncode != 0:
        detail = os.fsdecode(result.stderr).strip()
        raise CheckError(
            f"{root} must be a git checkout, because git lists the files to scan ({detail})"
        )
    return sorted({os.fsdecode(name) for name in result.stdout.split(b"\0") if name})


def strip_to_code(text, nested_comments):
    """Returns `text` with each comment, string and character literal as one space.

    A space and not nothing, because these separate tokens the way the compiler
    sees them. Line breaks stay, so a line number in the result is one in `text`.
    Block comments nest in Kotlin and not in Java.
    """
    length = len(text)
    index = 0
    code = []
    comment_depth = 0
    in_raw = False
    in_string = False
    while index < length:
        char = text[index]
        pair = text[index : index + 2]
        if char == "\n":
            code.append(char)
            in_string = False
            index += 1
        elif comment_depth:
            if pair == "/*" and nested_comments:
                comment_depth += 1
                index += 2
            elif pair == "*/":
                comment_depth -= 1
                index += 2
            else:
                index += 1
        elif in_raw:
            if text.startswith('"""', index):
                in_raw = False
                index += 3
            else:
                index += 1
        elif in_string:
            if char == "\\":
                index += 2 if text[index + 1 : index + 2] != "\n" else 1
            else:
                in_string = char != '"'
                index += 1
        elif pair == "//":
            code.append(" ")
            end = text.find("\n", index)
            index = length if end < 0 else end
        elif pair == "/*":
            code.append(" ")
            comment_depth = 1
            index += 2
        elif text.startswith('"""', index):
            code.append(" ")
            in_raw = True
            index += 3
        elif char == '"':
            code.append(" ")
            in_string = True
            index += 1
        elif char == "'":
            code.append(" ")
            match = CHAR_LITERAL.match(text, index)
            index = match.end() if match else index + 1
        else:
            code.append(char)
            index += 1
    return "".join(code)


def find_android_imports(text, nested_comments):
    code = strip_to_code(text, nested_comments).replace("`", " ")
    for match in ANDROID_IMPORT.finditer(code):
        yield code.count("\n", 0, match.start()) + 1, re.sub(r"\s", "", match.group(1))


def is_command_substitution(text, start, closing_quote=""):
    """Tells whether the shell value at `start` is one `$(...)` and nothing else.

    It must close on its line, and whatever follows it, past `closing_quote`, must
    end the word: `$(true)literal` still holds a literal.
    """
    if not text.startswith("$(", start):
        return False
    end = text.find("\n", start)
    end = len(text) if end < 0 else end
    depth = 0
    for index in range(start + 1, end):
        depth += (text[index] == "(") - (text[index] == ")")
        if depth == 0:
            after = index + 1
            if closing_quote:
                if text[after:after + 1] != closing_quote:
                    return False
                after += 1
            return after >= end or text[after] in SHELL_WORD_END
    return False


def is_config_file(path):
    return Path(path).suffix in CONFIG_SUFFIXES or Path(path).name.startswith(".env")


def may_hold_shell(path):
    """Config files hold plain values, except a workflow's `run:` lines in YAML."""
    return Path(path).suffix in YAML_SUFFIXES or not is_config_file(path)


def is_shell_statement(keyword, text, value_start, path):
    """Tells whether `keyword` is assigned the way a shell does, with no spaces."""
    return (
        may_hold_shell(path)
        and text[value_start - 2 : value_start] == '="'
        and (keyword.isupper() or Path(path).suffix == ".sh")
    )


def has_key_body(window):
    if PEM_BODY.search(window):
        return True
    total = 0
    for line in window.splitlines():
        line = line.strip()
        total = total + len(line) if PEM_BODY_LINE.fullmatch(line) else 0
        if total >= PEM_BODY_MINIMUM:
            return True
    return False


def find_exact_secrets(text):
    """Yields (offset, message) for formats that are safe to look for in any file."""
    for match in PEM_HEADER.finditer(text):
        if has_key_body(text[match.end() : match.end() + PEM_BODY_WINDOW]):
            yield match.start(), "private key block"
    for name, pattern in TOKEN_FORMATS:
        for match in pattern.finditer(text):
            if name not in NEEDS_A_DIGIT or any(char.isdigit() for char in match.group()):
                yield match.start(), f"possible {name} token"


def assignment_message(keyword):
    subject = "ntfy topic" if keyword.lower().startswith("ntfy") else "secret"
    return f"hard-coded {subject}: {PLACEHOLDER_ADVICE}"


def find_heuristic_secrets(text, path):
    """Yields (offset, message) for rules that only make sense in text files."""
    for match in AUTHORIZATION.finditer(text):
        if not is_placeholder(match.group(1)):
            yield match.start(), f"credential in an Authorization header: {PLACEHOLDER_ADVICE}"
    for match in URL_CREDENTIALS.finditer(text):
        if not is_placeholder(match.group(1)) and not is_reserved_host(match.group(2)):
            yield match.start(), f"password in a URL: {PLACEHOLDER_ADVICE}"
    for match in NTFY_URL.finditer(text):
        topic = match.group(1)
        if topic.lower() not in NTFY_SITE_PATHS and not is_placeholder(topic):
            yield match.start(), f"real ntfy topic: {PLACEHOLDER_ADVICE}"
    for match in QUOTED_ASSIGNMENT.finditer(text):
        keyword = match.group(1)
        double_quoted = match.group(2) is not None
        value = match.group(2) if double_quoted else match.group(3)
        computed = (
            double_quoted
            and is_shell_statement(keyword, text, match.start(2), path)
            and is_command_substitution(text, match.start(2), '"')
        )
        word_ended = QUOTED_WORD_END.match(text, match.end()) is not None
        if not computed and not (word_ended and is_placeholder(value)):
            yield match.start(), assignment_message(keyword)
    for match in SHELL_ASSIGNMENT.finditer(text):
        computed = is_command_substitution(text, match.start(2))
        if not computed and not is_placeholder(match.group(2)):
            yield match.start(), assignment_message(match.group(1))
    if is_config_file(path):
        for match in CONFIG_ASSIGNMENT.finditer(text):
            keyword, _separator, value = match.groups()
            # A shell statement in a config file, such as a workflow's `run:` line.
            shell = (
                may_hold_shell(path) and text[match.start(3) - 1] == "=" and keyword.isupper()
            )
            if shell and is_command_substitution(text, match.start(3)):
                continue
            if not is_placeholder(value):
                yield match.start(), assignment_message(keyword)
    for match in IP_ADDRESS.finditer(text):
        if is_server_address(match.group(1)):
            yield match.start(1), "literal server address: use a reserved documentation address"


def scan_secrets(path, data):
    """Returns (line, message) findings for one file's bytes."""
    binary = b"\0" in data
    text = data.decode("latin-1" if binary else "utf-8", errors="replace")
    findings = list(find_exact_secrets(text))
    if not binary:
        findings.extend(find_heuristic_secrets(text, path))
    line_starts = [match.end() for match in re.finditer("\n", text)]
    return {(bisect.bisect_right(line_starts, offset) + 1, message) for offset, message in findings}


def check_file(root, path, problems):
    target = root / path
    if target.is_symlink() or not target.is_file():
        return
    try:
        data = target.read_bytes()
    except OSError as error:
        problems.append((path, 1, f"cannot read the file to scan it: {error.strerror}"))
        return
    for line, message in scan_secrets(path, data):
        problems.append((path, line, message))
    if is_jvm_module(path) and target.suffix in SOURCE_SUFFIXES:
        text = data.decode("utf-8", errors="replace")
        for line, name in find_android_imports(text, target.suffix != ".java"):
            problems.append((path, line, f"JVM module must not import {name}"))


def run_checks(root):
    problems = []
    for path in list_files(root):
        check_file(root, path, problems)
    return sorted(set(problems))


def main():
    parser = argparse.ArgumentParser(description="Check module boundaries and scan for secrets.")
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT, help="repository root")
    root = parser.parse_args().root.resolve()
    sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")
    if not root.is_dir():
        print(f"check_boundaries: error: {root} is not a directory", file=sys.stderr)
        return 2
    try:
        problems = run_checks(root)
    except CheckError as error:
        print(f"check_boundaries: error: {error}", file=sys.stderr)
        return 2
    for path, line, message in problems:
        print(f"{path}:{line}: {message}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
