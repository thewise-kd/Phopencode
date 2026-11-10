#!/usr/bin/env python3
"""Release audit. Standard library only.

Run from the repository root as `python3 scripts/audit_release.py`. Prints one short line per
problem, `location: rule`, and exits 1 when there are any, 0 when clean, 2 when the audit
itself cannot run (outside a git checkout, a shallow clone without `--skip-history`, or a
file it must read that exists but cannot be read, such as a directory in a file's place); a
tracked file deleted from the working tree is a reported problem, never read as empty. A
line never repeats a matched secret, address or host: CI logs of this public repository are
public too. Locations are `path` or `path:line` in the working tree, and
`<commit>:<path>[:line]`, `<commit>:(commit message):line` or
`refs/tags/<name>:(tag message):line` in the history.

1. Secret scan across the full history. Every blob reachable from any ref (`git log --all`:
   branches, tags, remotes, the stash and filter-branch backups, plus trees and blobs a ref
   names directly) and every commit and annotated tag message. Refs are read by full name and
   tag objects by id, so a tag named like a branch is still read; a ref git cannot read stops
   the audit with exit 2. Every history command runs with replace refs and grafts disabled
   (`--no-replace-objects`, GIT_NO_REPLACE_OBJECTS, GIT_GRAFT_FILE), so a replacement cannot
   hide the original commit, while the objects under refs/replace/* are scanned like any ref;
   a grafts file is reported. Content rules are check_boundaries.py's own (private key blocks,
   token formats, credentials in URLs and Authorization headers, ntfy.sh topics, hard-coded
   secret assignments, server addresses in host positions), so both scripts agree on what a
   secret is, plus three that only make sense for a whole history: high-entropy values
   assigned to a `password`/`secret`/`token`/`key` name, any public IPv4 address (loopback,
   private, link-local and the documentation ranges 192.0.2/24, 198.51.100/24, 203.0.113/24
   are not public), any address in 100.64.0.0/10 (shared/CGNAT space, where Tailscale puts
   every device; reported as its own rule), and `ntfy://host/<topic>` subscription links.
   An address followed by `/prefix`, with a prefix of 24 or less and all host bits zero,
   names a network, not a device, and neither address rule reports it.
   The address scan is IPv4 only (check_boundaries' host-position rule is too): IPv6
   literals, Tailscale's fd7a:115c:a1e0::/48 included, are not detected. Path rules: a keystore
   (`*.jks`, `*.keystore`, `*.p12`, `*.pfx`, `*.bks`), `local.properties`, `.env` or
   `.env.*`, or `google-services.json` ever committed. Each (blob, path) pair is reported
   once, at the oldest commit that holds it; HISTORY_ALLOWLIST applies per path, so content
   allowed at one path is still reported where it is copied.

2. License headers. This repository puts no license header in its source files: the project
   license is the root `LICENSE` (MIT), and third-party licenses are recorded in
   `THIRD_PARTY_NOTICES.md`, with their texts shipped under
   `feature/settings/ui/src/main/assets/licenses/`. So "license headers" is taken to mean that
   those records are complete:
   - `LICENSE` exists and is the MIT license.
   - Every catalog library (`gradle/libs.versions.toml`) that the release ships appears in
     `THIRD_PARTY_NOTICES.md` (as `group:artifact`, or its `-android`/`-jvm` variant). Every
     `*.gradle` and `*.gradle.kts` script is read, Kotlin DSL and Groovy forms alike
     (`implementation libs.x`, `implementation(libs.x)`, `implementation 'g:a:v'`,
     `group: 'g', name: 'a'`, `project(':x')`, `project(path: ':x')`); only settings scripts,
     build-logic's own build script and scripts in test source sets do not ship. A
     `group:artifact` dependency must be in the notices itself, and a local file dependency
     (`files`, `fileTree`) is reported, since nothing records its license. The call forms
     `add("conf", notation)`, `dependencies.add(...)`, `addLibraries("conf", libs, "alias")`
     and `"conf"(notation)` are read in build scripts and convention plugins alike, with
     `findLibrary("x").get()`, `platform(...)` and simple `val` variables resolved; a
     declaration the audit cannot read, including a shipping configuration called with an
     argument it does not know, is reported. The catalog is read with `tomllib` (Python
     3.11+), or a small TOML reader on older Pythons; invalid TOML, an unreadable entry and an
     empty bundle are reported. A library ships
     when such a script or a convention plugin in `build-logic/src/main`
     adds it to `api`, `implementation` or `runtimeOnly` of a module other than the
     test-support ones (TEST_SUPPORT_MODULES, which the audit checks only test configurations
     use). A bundle (`libs.bundles.*`, `findBundle`) counts as each library in the catalog's
     `[bundles]` table. Test, debug and build-plugin libraries, platforms (BOMs, which carry
     no code) and catalog entries no build file uses do not ship. A catalog entry, reference,
     alias, bundle or configuration the audit cannot read or resolve is a problem of its own,
     so nothing slips past unread.
   - Every license text the notices link exists, and every text in the licenses directory is
     the target of a real Markdown link there (inline or reference-style); a file name only
     written in the text does not count.
   - Bundled third-party files, the TextMate grammars and themes under
     `core/codeview/src/main/assets/codeview/`: every directory has a `LICENSE`, is recorded
     in that directory's `NOTICE.md` and in `THIRD_PARTY_NOTICES.md`, and each of its license
     files is shipped unchanged under `licenses/textmate/`. A license file anywhere else
     (other than the root `LICENSE`) means an unrecorded vendored copy.

3. No analytics or tracking. No analytics, crash reporting or ads SDK (Firebase, Google
   Analytics, Crashlytics, Sentry, Bugsnag, App Center, Mixpanel, Amplitude, Segment, Play
   Services ads and measurement, and others in TRACKING_GROUPS) in the version catalog, any
   Gradle build file, the convention plugins, the checked-in release classpath or the
   notices: by group, and by artifact name whatever the group (TRACKING_ARTIFACT_PREFIXES
   match any continuation, such as `play-services-ads-lite` or
   `play-services-measurement-api`). No use of their packages in any Kotlin or Java source,
   tests included, in imports, code or string literals (`Class.forName`); no import of them
   in Python, read with `ast` (every `import`/`from` form, and `__import__` or
   `import_module` with a literal name); no advertising permission in a manifest
   (`com.google.android.gms.permission.AD_ID` or any `android.permission.ACCESS_ADSERVICES_*`).

4. Network targets are SSH and ntfy only.
   - The network security config every manifest names (all manifests, library and test ones
     included, resolved to every `res/xml*/<name>.xml` in any module or source set) denies
     cleartext by default and allows it to `127.0.0.1` alone, without subdomains. A missing
     or unresolvable reference, or more than one distinct config, is reported. No manifest
     sets `usesCleartextTraffic` to anything but `false`. Manifests are read with an XML
     parser and the android namespace resolved, so quoting, spacing, attribute order and
     prefix do not matter; a manifest that does not parse is reported. A config value the
     audit cannot verify (a resource reference, say) is reported.
   - In the app's main source sets (`app/`, `core/*/`, `feature/*/*/` under `src/main`, except
     TEST_SUPPORT_MODULES), network
     APIs (sockets, socket factories, URL connections, OkHttp, sshj, WebView, name lookups,
     and other HTTP clients) appear only in NETWORK_CODE: the SSH connection and the tunnel
     that carries the opencode API to the server's 127.0.0.1. Code that hands a URL to another
     app (`UriHandler`, `ACTION_VIEW`, custom tabs) appears only in LINK_OPENERS.
   - A URL literal in main code or resources (comments and XML namespaces aside; assets are
     data that is shown or parsed, never fetched) names 127.0.0.1, a reserved domain, a
     templated host (see url_host: a templated port, path or user does not count), or a host
     listed for that file in DISPLAY_URLS: URLs that are only shown, or opened in the
     browser by the user.
   - In `helper/` (the server-side Python; its tests aside) only `notifyd/ntfy.py`, which
     publishes to the configured ntfy server, and `notifyd/sse.py`, which reads the local
     opencode server's event stream, import network modules (checked on the full imported
     name with `ast`, as for tracking imports); its URL literals name the
     default ntfy server or loopback; its shell scripts download nothing.

These checks read source text; they are a release gate next to code review, not a proof.
"""
import argparse
import ast
import hashlib
import ipaddress
import math
import os
import posixpath
import re
import subprocess
import sys
import xml.etree.ElementTree as ElementTree
import xml.parsers.expat as expat
from collections import Counter
from pathlib import Path, PurePosixPath

try:
    import tomllib
except ImportError:  # Python older than 3.11: Catalog falls back to TomlSubset
    tomllib = None

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_boundaries  # noqa: E402  (sits next to this script)

DEFAULT_ROOT = Path(__file__).resolve().parents[1]

# --- 1. Secrets in history -------------------------------------------------------------------

# Known false positives in the history: {(path, rule fragment, value digest): why}. The digest is
# the first 16 hex digits of the SHA-256 of the matched value, so an entry covers that one value
# at that one path, in every commit, and the value itself never has to be written here. Only the
# audit's own rules (public IP, high-entropy value, ntfy link) report a value to compare.
_TESTS = "src/test/kotlin/io/github/thewisekd/phopencode"
HISTORY_ALLOWLIST = {
    ("scripts/tests/test_check_boundaries.py", "public IP", "6694f83c9f476da3"):
        "a four-part version string in the list of text that is not a server address",
    ("scripts/tests/test_check_boundaries.py", "public IP", "94ca4798a4dbeb81"):
        "a four-part Gradle version string in the same list",
    (f"core/api-http/{_TESTS}/core/api_http/client/ServerVersionTest.kt", "public IP", "a8db30efa3d96eee"):
        "a four-part version string the opencode version parser must reject",
    ("core/testing/src/main/kotlin/io/github/thewisekd/phopencode/core/testing/contract/ApiContracts.kt",
     "public IP", "3cad1d8f03f2d7eb"):
        "a four-part version string in the server-version contract cases",
    (f"core/remote/{_TESTS}/core/remote/detect/ScanReportTest.kt", "public IP", "5bf3bddbd02dbb6a"):
        "127.0.0.1 written backwards, a listener address that must not count as loopback",
}

KEYSTORE_SUFFIXES = (".jks", ".keystore", ".p12", ".pfx", ".bks")
ENV_FILE = re.compile(r"^\.env(?:\..+)?$")
ENV_TEMPLATES = (".example", ".sample", ".template")

ENTROPY_ASSIGNMENT = re.compile(
    r"(?i)(password|passwd|secret|token|api[_-]?key|access[_-]?key)"
    r"[A-Za-z0-9_-]*[\"']?[ \t]*[:=][ \t]*[\"']?([A-Za-z0-9+/_=.~-]{20,})"
)
DOTTED_NAME = re.compile(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+")
MINIMUM_ENTROPY = 3.5

SHARED_NETWORK = ipaddress.ip_network("100.64.0.0/10")
CIDR_PREFIX = re.compile(r"/(\d{1,2})(?!\d)")
MAX_NETWORK_PREFIX = 24
IPV4 = re.compile(r"(?<![\w.])(\d{1,3}(?:\.\d{1,3}){3})(?!\w|\.\d)")
NTFY_LINK = re.compile(r"ntfy://[^\s/\"'<>`]+/([A-Za-z0-9_-]+)")


def entropy(value):
    counts = Counter(value)
    return -sum(n / len(value) * math.log2(n / len(value)) for n in counts.values())


def is_random_looking(value):
    return (
        any(c.isdigit() for c in value)
        and any(c.isalpha() for c in value)
        and DOTTED_NAME.fullmatch(value) is None
        and not check_boundaries.is_placeholder(value)
        and entropy(value) >= MINIMUM_ENTROPY
    )


def is_public_address(text):
    try:
        address = ipaddress.ip_address(text)
    except ValueError:
        return False
    documentation = any(address in net for net in check_boundaries.DOCUMENTATION_NETWORKS)
    return address.is_global and not documentation


def is_call(text, end):
    """True when the value ending at `end` is a function or constructor being called."""
    return text[end : end + 1] == "("


def is_shared_address(text):
    """True for 100.64.0.0/10, where Tailscale gives each device its address.

    `is_global` is False there (RFC 6598 shared space), so the public rule alone misses it.
    """
    try:
        return ipaddress.ip_address(text) in SHARED_NETWORK
    except ValueError:
        return False


def is_network_base(address, text, end):
    """True for `address/prefix` naming a whole network: prefix at most 24, host bits all zero.

    Such a CIDR, like the constant that names Tailscale's range, is not a device. A host
    written as /25 to /32, or with host bits set before any prefix, is still an address.
    """
    prefix = CIDR_PREFIX.match(text, end)
    if not prefix or int(prefix.group(1)) > MAX_NETWORK_PREFIX:
        return False
    try:
        ipaddress.ip_network(f"{address}/{int(prefix.group(1))}", strict=True)
    except ValueError:
        return False
    return True


def history_text_findings(text):
    """Yields (offset, message, value) for the rules check_boundaries does not have."""
    for match in ENTROPY_ASSIGNMENT.finditer(text):
        if is_random_looking(match.group(2)) and not is_call(text, match.end(2)):
            yield match.start(), "secret scan: high-entropy value assigned to a secret name", match.group(2)
    for match in IPV4.finditer(text):
        if is_network_base(match.group(1), text, match.end(1)):
            continue
        if is_public_address(match.group(1)):
            yield match.start(), "secret scan: public IP address", match.group(1)
        elif is_shared_address(match.group(1)):
            yield match.start(), "secret scan: Tailscale/CGNAT address", match.group(1)
    for match in NTFY_LINK.finditer(text):
        if not check_boundaries.is_placeholder(match.group(1)):
            yield match.start(), "secret scan: ntfy subscription link with a real topic", match.group(1)


def digest(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]


def allowed(path, message, value):
    key = digest(value)
    return any(
        path == entry and fragment in message and key == entry_digest
        for entry, fragment, entry_digest in HISTORY_ALLOWLIST
    )


def raw_findings(path, data):
    """Returns (line, message, value or None) findings for one blob or message, no allowlist.

    check_boundaries reports no value, so its findings can never be allowlisted.
    """
    findings = {(line, f"secret scan: {message}", None) for line, message in check_boundaries.scan_secrets(path, data)}
    if b"\0" not in data:
        text = data.decode("utf-8", errors="replace")
        for offset, message, value in history_text_findings(text):
            findings.add((text.count("\n", 0, offset) + 1, message, value))
    return findings


def apply_allowlist(path, findings):
    """The (line, message) findings that the allowlist does not cover at `path`."""
    return sorted(
        {(line, message) for line, message, value in findings if value is None or not allowed(path, message, value)}
    )


def scan_content(path, data):
    """Returns sorted (line, message) findings for one blob or message, allowlist applied."""
    return apply_allowlist(path, raw_findings(path, data))


def scan_kind(path):
    """What, besides the bytes, check_boundaries' result depends on: the kind of file."""
    return (
        check_boundaries.is_config_file(path),
        check_boundaries.may_hold_shell(path),
        PurePosixPath(path).suffix,
    )


def path_findings(path):
    name = PurePosixPath(path).name
    lower = name.lower()
    if lower.endswith(KEYSTORE_SUFFIXES):
        yield "secret scan: keystore file committed"
    if name == "local.properties":
        yield "secret scan: local.properties committed"
    if ENV_FILE.match(name) and not lower.endswith(ENV_TEMPLATES):
        yield "secret scan: .env file committed"
    if name == "google-services.json":
        yield "secret scan: google-services.json committed"


# The history as stored, not as presented: replace refs and grafts can make a commit that
# holds a secret disappear from `git log`, and a signature in the log output would break its
# parsing. Replacement objects are still scanned, because refs/replace/* are refs.
GIT_HISTORY_OPTIONS = ("--no-replace-objects", "-c", "log.showSignature=false")


def git_environment():
    env = dict(os.environ)
    env["GIT_NO_REPLACE_OBJECTS"] = "1"
    env["GIT_GRAFT_FILE"] = os.devnull
    return env


def git(root, *arguments, data=None):
    try:
        result = subprocess.run(
            ["git", *GIT_HISTORY_OPTIONS, "-C", str(root), *arguments],
            input=data,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=git_environment(),
        )
    except OSError as error:
        raise check_boundaries.CheckError(f"cannot run git: {error}") from error
    if result.returncode != 0:
        detail = os.fsdecode(result.stderr).strip()
        raise check_boundaries.CheckError(f"git {arguments[0]} failed ({detail})")
    return result.stdout


def read_blobs(root, shas):
    """Yields (sha, bytes) for each blob, through one `git cat-file --batch`."""
    output = git(root, "cat-file", "--batch", data="".join(sha + "\n" for sha in shas).encode())
    index = 0
    for _ in shas:
        end = output.index(b"\n", index)
        sha, _kind, size = output[index:end].decode().split(" ")
        start = end + 1
        yield sha, output[start : start + int(size)]
        index = start + int(size) + 1


def audit_history(root):
    problems = []
    if git(root, "rev-parse", "--is-shallow-repository").strip() == b"true":
        raise check_boundaries.CheckError(
            "the clone is shallow, so its history is incomplete: run `git fetch --unshallow` "
            "or pass --skip-history"
        )
    common = os.fsdecode(git(root, "rev-parse", "--git-common-dir").strip())
    candidates = [os.path.join(common, "info", "grafts")]
    if os.environ.get("GIT_GRAFT_FILE"):
        candidates.append(os.environ["GIT_GRAFT_FILE"])
    for grafts in candidates:
        if (root / grafts).is_file():
            problems.append(f"{grafts}: grafts file present; the audit ignores it, git log does not")
    log = git(root, "log", "--all", "--reverse", "-z", "--format=%H%n%T%n%B")
    # Every (blob, path) pair, at the oldest commit that has it. A blob is scanned once per kind
    # of path, but the allowlist is applied at each path on its own: content allowed at one
    # path is still reported when it is copied to another.
    occurrences = {}
    first_path = {}
    seen_trees = set()

    def add_tree(tree, where):
        if tree in seen_trees:
            return
        seen_trees.add(tree)
        for entry in git(root, "ls-tree", "-r", "-z", "--full-tree", tree).split(b"\0"):
            if not entry:
                continue
            info, path = entry.split(b"\t", 1)
            _mode, kind, sha = info.decode().split(" ")
            if kind != "blob":
                continue
            path = os.fsdecode(path)
            occurrences.setdefault((sha, path), where)
            first_path.setdefault(path, where)

    for record in log.split(b"\0"):
        if not record.strip():
            continue
        commit, tree, message = (record.decode("utf-8", errors="replace").split("\n", 2) + [""])[:3]
        short = commit[:12]
        for line, finding in scan_content("", message.encode("utf-8")):
            problems.append(f"{short}:(commit message):{line}: {finding}")
        add_tree(tree, short)
    problems.extend(audit_refs(root, add_tree, occurrences))
    for path, where in first_path.items():
        for finding in path_findings(path):
            problems.append(f"{where}:{path}: {finding}")
    by_blob = {}
    for (sha, path), where in occurrences.items():
        by_blob.setdefault(sha, []).append((path, where))
    for sha, data in read_blobs(root, sorted(by_blob)):
        cache = {}
        for path, where in by_blob[sha]:
            kind = scan_kind(path)
            if kind not in cache:
                cache[kind] = raw_findings(path, data)
            for line, finding in apply_allowlist(path, cache[kind]):
                problems.append(f"{where}:{path}:{line}: {finding}")
    return problems


def audit_refs(root, add_tree, occurrences):
    """Scans what refs reach besides commits: tag messages, and trees or blobs a ref names.

    Refs are read by full name and objects by id, so a tag named like a branch is still the
    tag. A ref git cannot read stops the audit (exit 2) rather than being skipped.
    """
    problems = []
    listing = git(root, "for-each-ref", "--format=%(refname)%00%(objecttype)%00%(objectname)")
    for line in listing.splitlines():
        refname, kind, sha = line.decode("utf-8", errors="replace").split("\0")
        while kind == "tag":
            body = git(root, "cat-file", "tag", sha)
            header, _, message = body.partition(b"\n\n")
            for number, finding in scan_content("", message):
                problems.append(f"{refname}:(tag message):{number}: {finding}")
            fields = dict(
                field.decode("utf-8", errors="replace").split(" ", 1)
                for field in header.split(b"\n")
                if b" " in field
            )
            sha, kind = fields.get("object", ""), fields.get("type", "")
        if kind == "tree":
            add_tree(sha, refname)
        elif kind == "blob":
            occurrences.setdefault((sha, f"(blob named by {refname})"), refname)
        elif kind != "commit":
            raise check_boundaries.CheckError(f"cannot read what {refname} points to")
    return problems


# --- 2. Licenses -------------------------------------------------------------------------------

LICENSE = "LICENSE"
NOTICES = "THIRD_PARTY_NOTICES.md"
CATALOG = "gradle/libs.versions.toml"
LICENSE_ASSETS = "feature/settings/ui/src/main/assets/licenses"
TEXTMATE_COPIES = LICENSE_ASSETS + "/textmate"
BUNDLE_ROOT = "core/codeview/src/main/assets/codeview"
BUNDLE_NOTICE = BUNDLE_ROOT + "/NOTICE.md"
LICENSE_NAME = re.compile(r"(?i)^(?:licen[cs]e|copying|notice)(?:\.[\w.-]+)?$")
MIT_MARKERS = (
    "Permission is hereby granted, free of charge",
    'THE SOFTWARE IS PROVIDED "AS IS"',
)

# Modules that only tests use. Their dependencies and code do not ship; the audit checks that no
# build file or convention plugin adds them to a configuration other than a test one.
TEST_SUPPORT_MODULES = {
    ":core:testing": "fakes, fixtures and contract tests for the JVM modules' tests",
}

SHIPPED_CONFIGURATIONS = {
    "api", "implementation", "runtimeOnly",
    "releaseApi", "releaseImplementation", "releaseRuntimeOnly",
}
UNSHIPPED_CONFIGURATION = re.compile(
    r"(?i)test|debug|^compileOnly$|^kapt|^ksp|^annotationProcessor$|^lintChecks$|^classpath$"
)

NOTICE_COORDINATE = re.compile(r"`([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+)`")
MARKDOWN_INLINE_LINK = re.compile(r"\]\(\s*(<[^>\n]*>|[^)\s]+)(?:\s+(?:\"[^\"\n]*\"|'[^'\n]*'))?\s*\)")
MARKDOWN_REFERENCE = re.compile(r"^ {0,3}\[[^\]\n]+\]:\s*(<[^>\n]*>|\S+)", re.M)

_CONFIGURATION = r"\b([A-Za-z_]\w*)\s*\(?\s*"
_PLATFORM = r"(?:(?:enforced)?[Pp]latform\s*\(?\s*)?"
_QUOTE = "[\"']"
# A catalog accessor ends at its last name, perhaps with `.get()`; `libs.findLibrary(` is a
# method call, read by CATALOG_LOOKUP instead.
_ACCESSOR_END = r"(?:\.get\(\s*\))?(?![\w.]|\s*\()"
GRADLE_DEPENDENCY_FORMS = (
    ("library", re.compile(_CONFIGURATION + _PLATFORM + r"libs\.((?!versions\.|plugins\.)[A-Za-z0-9_.]*[A-Za-z0-9_])" + _ACCESSOR_END)),
    ("project", re.compile(
        _CONFIGURATION + _PLATFORM + r"project\s*\(?\s*(?:path\s*[:=]\s*)?" + _QUOTE + r"(:[\w.:-]*)" + _QUOTE
    )),
    ("coordinate", re.compile(
        _CONFIGURATION + _PLATFORM + r"\(?\s*group\s*[:=]\s*" + _QUOTE + r"([\w.-]+)" + _QUOTE
        + r"\s*,\s*name\s*[:=]\s*" + _QUOTE + r"([\w.-]+)" + _QUOTE
    )),
    ("coordinate", re.compile(
        _CONFIGURATION + _PLATFORM + _QUOTE + r"([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+)(?::[^\"'\s]*)?" + _QUOTE
    )),
    ("files", re.compile(_CONFIGURATION + r"\b(?:files|fileTree)\s*\(")),
)
# A dependency configuration used as a call in a build script: `implementation(x)` or Groovy
# `implementation x`. One that no form above reads is reported; a configuration block
# (`implementation { ... }`) or an assignment is not a declaration.
CONFIGURATION_USE = re.compile(
    r"\b(?:implementation|api|runtimeOnly|compileOnly|\w+(?:Implementation|Api|RuntimeOnly|CompileOnly))"
    r"\b(?=[ \t]*\(|[ \t]+[^\s{=.(])"
)
ANY_LIBRARY = re.compile(r"\blibs\.(?!versions\.|plugins\.)([A-Za-z0-9_.]*[A-Za-z0-9_])" + _ACCESSOR_END)
PROJECT_REFERENCE = re.compile(r"\bproject\s*\(?\s*(?:path\s*[:=]\s*)?[\"'](:[\w.:-]*)[\"']")
MODULE_STRING = re.compile(r'"(:[\w-]+(?::[\w-]+)*)"')
TEST_CONFIGURATION_STRING = re.compile(r'"\w*[Tt]est\w*"')
CATALOG_LOOKUP = re.compile(r"""\b(findLibrary|library|findBundle)\(\s*["']([\w.-]+)["']\s*\)""")
DEPENDENCY_CALL = re.compile(r"""(?:\b(addLibraries|add)|(["'])([A-Za-z_]\w*)\2)\s*\(""")
STRING_ARGUMENT = re.compile(r"""\s*(["'])([^"'\n]*)\1\s*""")
IDENTIFIER = re.compile(r"[A-Za-z_]\w*")
VALUE_ASSIGNMENT = re.compile(r"\b(?:val|var|def)\s+([A-Za-z_]\w*)\s*(?::\s*[\w.<>?]+\s*)?=\s*([^\n;]+)")
PLATFORM_CALL = re.compile(r"(?:[\w.]*\.)?(?:enforcedPlatform|platform)\s*\((.*)\)", re.S)
ANY_CATALOG_LOOKUP = re.compile(
    r"""(?:[\w.]*(?:\([^()]*\))?\s*\.\s*)*(findLibrary|library|findBundle)\(\s*["']([\w.-]+)["']\s*\)"""
    r"(?:\s*\.\s*get\(\s*\))?"
)
LIBS_ACCESSOR = re.compile(r"libs\.((?!versions\.|plugins\.)[A-Za-z0-9_.]*[A-Za-z0-9_])(?:\.get\(\s*\))?")
COORDINATE_LITERAL = re.compile(r"""(["'])([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+)(?::[^"'\s]*)?\1""")
PROJECT_CALL = re.compile(r"(?:[\w.]*\.)?project\s*\(")
PROJECT_PATH = re.compile(r"""["'](:[\w.:-]*)["']""")
FILES_CALL = re.compile(r"(?:[\w.]*\.)?(?:files|fileTree)\s*\(")
MAP_NOTATION = re.compile(r"""\bgroup\s*[:=]\s*["']([\w.-]+)["']\s*,\s*name\s*[:=]\s*["']([\w.-]+)["']""")


def read_bytes(root, path):
    """The bytes of a working-tree file, or None when it does not exist.

    Every file read of the audit goes through here. A file that exists but cannot be read
    (a directory, no permission, an I/O error) stops the audit with the documented
    "cannot run" error, exit 2: an audit that skipped it would pass for the wrong reason.
    The message names the path and the reason, never the content.
    """
    try:
        return (root / path).read_bytes()
    except FileNotFoundError:
        return None
    except OSError as error:
        reason = error.strerror or type(error).__name__
        raise check_boundaries.CheckError(f"cannot read {path}: {reason}") from error


def read_text(root, path):
    """read_bytes as UTF-8 text; bytes that are not UTF-8 become U+FFFD, never an error."""
    data = read_bytes(root, path)
    return None if data is None else data.decode("utf-8", errors="replace")


def line_of(text, offset):
    return text.count("\n", 0, offset) + 1


def accessor(alias):
    return re.sub(r"[-_]", ".", alias)


class TomlSubsetError(ValueError):
    """The fallback reader met TOML it does not handle."""


class TomlSubset:
    """A small TOML reader for when `tomllib` (Python 3.11+) is missing.

    It reads what a version catalog uses: [tables], `key = value` with bare, dotted or quoted
    keys, basic and literal strings, arrays (spread over lines, trailing commas allowed),
    inline tables, and comments outside strings. Other values are kept as raw text.
    """

    def __init__(self, text):
        self.text = text
        self.index = 0

    def load(self):
        data, table = {}, None
        while True:
            self._space(newlines=True)
            if self.index >= len(self.text):
                return data
            if self.text[self.index] == "[":
                self.index += 1
                name = self._key()
                self._expect("]")
                table = data.setdefault(name[0], {})
            else:
                key = self._key()
                self._space()
                self._expect("=")
                value = self._value()
                target = table if table is not None else data
                for part in key[:-1]:
                    target = target.setdefault(part, {})
                target[key[-1]] = value
            self._space()
            if self.index < len(self.text) and self.text[self.index] != "\n":
                raise TomlSubsetError(f"unexpected text at line {self._line()}")

    def _line(self):
        return self.text.count("\n", 0, self.index) + 1

    def _space(self, newlines=False):
        while self.index < len(self.text):
            char = self.text[self.index]
            if char in " \t\r" or (newlines and char == "\n"):
                self.index += 1
            elif char == "#":
                end = self.text.find("\n", self.index)
                self.index = len(self.text) if end < 0 else end
            else:
                return

    def _expect(self, char):
        self._space()
        if not self.text.startswith(char, self.index):
            raise TomlSubsetError(f"expected {char!r} at line {self._line()}")
        self.index += 1

    def _key(self):
        parts = []
        while True:
            self._space()
            char = self.text[self.index : self.index + 1]
            if char in ("'", '"'):
                parts.append(self._string())
            else:
                match = re.compile(r"[A-Za-z0-9_-]+").match(self.text, self.index)
                if not match:
                    raise TomlSubsetError(f"bad key at line {self._line()}")
                parts.append(match.group())
                self.index = match.end()
            self._space()
            if self.text.startswith(".", self.index):
                self.index += 1
                continue
            return parts

    def _string(self):
        quote = self.text[self.index]
        self.index += 1
        out = []
        while self.index < len(self.text) and self.text[self.index] != quote:
            char = self.text[self.index]
            if char == "\n":
                break
            if char == "\\" and quote == '"':
                out.append(self.text[self.index : self.index + 2])
                self.index += 2
                continue
            out.append(char)
            self.index += 1
        if not self.text.startswith(quote, self.index):
            raise TomlSubsetError(f"unterminated string at line {self._line()}")
        self.index += 1
        value = "".join(out)
        return value.encode("utf-8").decode("unicode_escape") if quote == '"' and "\\" in value else value

    def _value(self):
        self._space()
        char = self.text[self.index : self.index + 1]
        if char in ("'", '"'):
            return self._string()
        if char == "[":
            self.index += 1
            items = []
            while True:
                self._space(newlines=True)
                if self.text.startswith("]", self.index):
                    self.index += 1
                    return items
                items.append(self._value())
                self._space(newlines=True)
                if self.text.startswith(",", self.index):
                    self.index += 1
                elif not self.text.startswith("]", self.index):
                    raise TomlSubsetError(f"bad array at line {self._line()}")
        if char == "{":
            self.index += 1
            table = {}
            self._space()
            if self.text.startswith("}", self.index):
                self.index += 1
                return table
            while True:
                key = self._key()
                self._expect("=")
                value = self._value()
                target = table
                for part in key[:-1]:
                    target = target.setdefault(part, {})
                target[key[-1]] = value
                self._space()
                if self.text.startswith(",", self.index):
                    self.index += 1
                elif self.text.startswith("}", self.index):
                    self.index += 1
                    return table
                else:
                    raise TomlSubsetError(f"bad inline table at line {self._line()}")
        match = re.compile(r"[^\s,\]}#]+").match(self.text, self.index)
        if not match:
            raise TomlSubsetError(f"bad value at line {self._line()}")
        self.index = match.end()
        return match.group()


CATALOG_TABLE_LINE = re.compile(r"^\s*\[\s*([A-Za-z0-9_-]+)\s*\]", re.M)
CATALOG_KEY_LINE = re.compile(r"""^\s*(?:"([^"]+)"|'([^']+)'|([A-Za-z0-9_-]+))\s*[=.]""", re.M)
COORDINATE_TEXT = re.compile(r"([^:\s]+):([^:\s]+)(?::.*)?")


class Catalog:
    """The [libraries] and [bundles] tables of the version catalog.

    `libraries` is {accessor: (alias, "group:artifact", line)}; `bundles` is
    {accessor: ([library accessors], line)}. Read with `tomllib` when Python has it (3.11+),
    else with TomlSubset; invalid TOML is reported and the fallback reader tried. An entry the
    audit cannot read, or an empty bundle, is in `problems`, so nothing slips past by its shape.
    """

    def __init__(self, text, use_tomllib=None):
        self.libraries = {}
        self.bundles = {}
        self.plugins = {}
        self.problems = []
        if use_tomllib is None:
            use_tomllib = tomllib is not None
        elif use_tomllib and tomllib is None:
            raise RuntimeError("use_tomllib=True needs tomllib, which Python has from 3.11 on")
        data = None
        if use_tomllib:
            try:
                data = tomllib.loads(text)
            except tomllib.TOMLDecodeError as error:
                self.problems.append(f"{CATALOG}: not valid TOML ({error})")
        if data is None:
            try:
                data = TomlSubset(text).load()
            except (TomlSubsetError, IndexError) as error:
                if not use_tomllib:
                    self.problems.append(f"{CATALOG}: TOML the audit cannot read ({error})")
                data = {}
        self._load(data, self._entry_lines(text))

    @staticmethod
    def _entry_lines(text):
        """{(table, key): line} for the first line of each top-level key in each table."""
        lines = {}
        tables = [(m.start(), m.group(1)) for m in CATALOG_TABLE_LINE.finditer(text)]
        for match in CATALOG_KEY_LINE.finditer(text):
            table = None
            for start, name in tables:
                if start < match.start():
                    table = name
            key = next(group for group in match.groups() if group is not None)
            lines.setdefault((table, key), text.count("\n", 0, match.start()) + 1)
        return lines

    def _load(self, data, lines):
        libraries = data.get("libraries", {})
        bundles = data.get("bundles", {})
        if not isinstance(libraries, dict) or not isinstance(bundles, dict):
            self.problems.append(f"{CATALOG}: [libraries] or [bundles] is not a table")
            return
        for alias, value in libraries.items():
            number = lines.get(("libraries", alias), 1)
            coordinate = None
            if isinstance(value, str):
                match = COORDINATE_TEXT.fullmatch(value)
                coordinate = f"{match.group(1)}:{match.group(2)}" if match else None
            elif isinstance(value, dict):
                module, group, name = value.get("module"), value.get("group"), value.get("name")
                if isinstance(module, str) and module.count(":") == 1 and all(module.split(":")):
                    coordinate = module
                elif isinstance(group, str) and isinstance(name, str) and group and name:
                    coordinate = f"{group}:{name}"
            if coordinate is None:
                self.problems.append(f"{CATALOG}:{number}: library entry the audit cannot read")
            else:
                self.libraries[accessor(alias)] = (alias, coordinate, number)
        for alias, value in bundles.items():
            number = lines.get(("bundles", alias), 1)
            if not isinstance(value, list) or not all(isinstance(member, str) for member in value):
                self.problems.append(f"{CATALOG}:{number}: bundle entry the audit cannot read")
            elif not value:
                self.problems.append(f"{CATALOG}:{number}: empty bundle {alias}")
            else:
                self.bundles[accessor(alias)] = ([accessor(member) for member in value], number)
        plugins = data.get("plugins", {})
        if not isinstance(plugins, dict):
            self.problems.append(f"{CATALOG}: [plugins] is not a table")
            return
        for alias, value in plugins.items():
            number = lines.get(("plugins", alias), 1)
            plugin_id = value.get("id") if isinstance(value, dict) else value.split(":", 1)[0] if isinstance(value, str) else None
            if isinstance(plugin_id, str) and plugin_id:
                self.plugins[accessor(alias)] = (plugin_id, number)
            else:
                self.problems.append(f"{CATALOG}:{number}: plugin entry the audit cannot read")

    def resolve(self, name, bundle, location, problems):
        """Library accessors behind a reference; reports an unknown name at `location`."""
        if bundle:
            if name not in self.bundles:
                problems.append(f"{location}: unknown catalog bundle {name}")
                return []
            members, number = self.bundles[name]
            for member in members:
                if member not in self.libraries:
                    problems.append(f"{CATALOG}:{number}: bundle {name} names a library not in the catalog")
            return [m for m in members if m in self.libraries]
        if name not in self.libraries:
            problems.append(f"{location}: library alias {name} is not in the catalog")
            return []
        return [name]

    def is_platform(self, name):
        return name in self.libraries and is_platform(self.libraries[name][1])


def is_platform(coordinate):
    return coordinate.split(":")[1].endswith("-bom")


def classify(configuration):
    """True when it ships, False when it does not, None when the audit does not know it."""
    if configuration in SHIPPED_CONFIGURATIONS:
        return True
    if UNSHIPPED_CONFIGURATION.search(configuration):
        return False
    return None


def gradle_scripts(files):
    """Yields (path, module, ships) for every tracked `*.gradle` and `*.gradle.kts` file.

    `module` is the Gradle path of a `build.gradle[.kts]`, or None for another script (an
    applied script or a precompiled convention plugin, which can apply to any module).
    `ships` is False for scripts whose dependencies never reach the app: settings scripts,
    build-logic's own build script (the convention plugins' classpath), and scripts under a
    test source set. Everything else counts as shipping, the safe default.
    """
    for path in files:
        name = PurePosixPath(path).name
        if not name.endswith((".gradle", ".gradle.kts")):
            continue
        parts = path.split("/")
        build_tool = name.startswith("settings.gradle") or (
            parts[0] == "build-logic" and len(parts) == 2
        )
        in_tests = any(part in ("test", "androidTest", "testFixtures") for part in parts[1:-1]) and "src" in parts
        module = ":" + ":".join(parts[:-1]) if name.startswith("build.gradle") else None
        yield path, module, not (build_tool or in_tests)


def gradle_view(root, path):
    """A Gradle script with comments blanked; Groovy single-quoted strings stay strings."""
    text = read_text(root, path) or ""
    return split_source(text, nested=path.endswith(".kts"), keep_strings=True, groovy=not path.endswith(".kts"))


def gradle_dependencies(text, calls_only=False):
    """Yields (start, end, configuration, kind, value) for each dependency declared in `text`.

    First the call forms of dependency_calls (`add`, `dependencies.add`, `addLibraries`,
    `"conf"(...)`), then, unless `calls_only` (Kotlin convention sources, where only those
    calls declare dependencies), the Kotlin DSL and Groovy forms: `conf(libs.x)`,
    `conf libs.x`, `conf(platform(libs.x))`, `conf project(':x')`, `conf(project(path: ":x"))`,
    `conf 'g:a:v'`, `conf("g:a:v")`, `conf group: 'g', name: 'a'`, `conf files(...)` /
    `fileTree(...)`. `kind` is "library", "bundle", "project", "coordinate", "files", or
    "unknown" for a call the audit cannot classify. Overlapping matches count once.
    """
    taken = []
    for declaration in dependency_calls(text):
        taken.append(declaration[:2])
        yield declaration
    if calls_only:
        return
    for kind, pattern in GRADLE_DEPENDENCY_FORMS:
        for match in pattern.finditer(text):
            if any(start < match.end() and match.start() < end for start, end in taken):
                continue
            taken.append((match.start(), match.end()))
            if kind == "coordinate":
                value = f"{match.group(2)}:{match.group(3)}"
            elif kind == "files":
                value = None
            else:
                value = match.group(2)
            yield match.start(), match.end(), match.group(1), kind, value


def convention_sources(files):
    return [p for p in files if p.startswith("build-logic/src/main/") and p.endswith(".kt")]


def split_arguments(arguments):
    """Splits call arguments at top-level commas, outside brackets and quotes."""
    parts, depth, quote, current = [], 0, None, []
    for char in arguments:
        if quote:
            quote = None if char == quote else quote
        elif char in "\"'":
            quote = char
        elif char in "([{":
            depth += 1
        elif char in ")]}":
            depth -= 1
        elif char == "," and depth == 0:
            parts.append("".join(current))
            current = []
            continue
        current.append(char)
    parts.append("".join(current))
    return [part.strip() for part in parts if part.strip()]


def read_notation(expression, values, depth=0):
    """(kind, value) of one dependency notation, or None when the audit cannot tell.

    A plain name is looked up in `values`, the file's `val`/`var`/`def` assignments.
    `platform(...)` is unwrapped; BOMs are dropped later like any platform.
    """
    expression = expression.strip()
    if IDENTIFIER.fullmatch(expression) and expression in values and depth < 5:
        return read_notation(values[expression], values, depth + 1)
    wrapped = PLATFORM_CALL.fullmatch(expression)
    if wrapped:
        return read_notation(wrapped.group(1), values, depth + 1)
    lookup = ANY_CATALOG_LOOKUP.fullmatch(expression)
    if lookup:
        return ("bundle" if lookup.group(1) == "findBundle" else "library"), accessor(lookup.group(2))
    accessor_use = LIBS_ACCESSOR.fullmatch(expression)
    if accessor_use:
        name = accessor_use.group(1)
        return ("bundle", name[len("bundles."):]) if name.startswith("bundles.") else ("library", name)
    coordinate = COORDINATE_LITERAL.fullmatch(expression)
    if coordinate:
        return "coordinate", f"{coordinate.group(2)}:{coordinate.group(3)}"
    if PROJECT_CALL.match(expression):
        path = PROJECT_PATH.search(expression)
        return "project", path.group(1) if path else None
    if FILES_CALL.match(expression):
        return "files", None
    return None


def receiver_after_dot(text, start):
    """True when the name at `start` is a member (`x.implementation`), not a call on its own."""
    return text[:start].rstrip().endswith(".")


def receiver_allowed(text, start):
    """True for a call with no receiver, or one on `dependencies` (`project.dependencies` too)."""
    before = text[:start].rstrip()
    if not before.endswith("."):
        return True
    return re.search(r"\bdependencies\s*\.$", before) is not None


def dependency_calls(text):
    """Yields (start, end, configuration, kind, value) for the call forms of a declaration.

    `add(conf, notation)` and `dependencies.add(...)`, `addLibraries(conf, catalog, "alias",
    ...)` and the string-configuration call `"conf"(notation)`, in Kotlin or Groovy. `add`
    on another receiver (`list.add(x)`) is not a dependency. A configuration given by a
    String parameter of a function in the file marks a forwarding helper; its callers are
    read instead. Anything else the audit cannot read is yielded as kind "unknown".
    """
    values = {m.group(1): m.group(2).strip() for m in VALUE_ASSIGNMENT.finditer(text)}
    for call in DEPENDENCY_CALL.finditer(text):
        name, configuration_name = call.group(1), call.group(3)
        if name and not receiver_allowed(text, call.start()):
            continue
        arguments = call_arguments(text, call.end() - 1)
        if arguments is None:
            yield call.start(), call.end(), "", "unknown", None
            continue
        end = call.end() + len(arguments) + 1
        parts = split_arguments(arguments)
        if configuration_name:  # "conf"(notation)
            configuration, notations = configuration_name, parts[:1]
        else:
            if not parts:
                yield call.start(), end, "", "unknown", None
                continue
            literal = STRING_ARGUMENT.fullmatch(parts[0])
            if literal:
                configuration = literal.group(2)
            elif IDENTIFIER.fullmatch(parts[0]) and STRING_ARGUMENT.fullmatch(values.get(parts[0], "")):
                configuration = STRING_ARGUMENT.fullmatch(values[parts[0]]).group(2)
            elif IDENTIFIER.fullmatch(parts[0]) and re.search(
                r"\bfun\b[^\n{]*\b" + re.escape(parts[0]) + r"\s*:\s*String\b", text
            ):
                continue  # a forwarding helper such as addLibraries' own body
            else:
                yield call.start(), end, "", "unknown", None
                continue
            if name == "addLibraries":
                notations = []
                for alias in parts[2:]:
                    literal = STRING_ARGUMENT.fullmatch(alias)
                    notations.append(f'library("{literal.group(2)}")' if literal else alias)
            else:
                notations = parts[1:2]
        map_form = MAP_NOTATION.search(arguments)
        if map_form and configuration_name:
            yield call.start(), end, configuration, "coordinate", f"{map_form.group(1)}:{map_form.group(2)}"
            continue
        if not notations:
            yield call.start(), end, configuration, "unknown", None
        for notation in notations:
            read = read_notation(notation, values)
            kind, value = read if read else ("unknown", None)
            yield call.start(), end, configuration, kind, value


def call_arguments(text, open_paren):
    """Returns the text between `(` at `open_paren` and its match, or None when unbalanced."""
    depth = 0
    for index in range(open_paren, len(text)):
        depth += (text[index] == "(") - (text[index] == ")")
        if depth == 0:
            return text[open_paren + 1 : index]
    return None


def audit_test_support(root, files):
    """Problems for a TEST_SUPPORT_MODULES entry that something outside tests depends on.

    A reference to one that is not a recognised test-configuration dependency counts as use
    outside tests: the audit does not guess.
    """
    problems = []
    for path, _module, _ships in gradle_scripts(files):
        text = gradle_view(root, path)
        tested = [
            (start, end)
            for start, end, configuration, kind, value in gradle_dependencies(text)
            if kind == "project" and classify(configuration) is False
        ]
        for match in PROJECT_REFERENCE.finditer(text):
            if match.group(1) not in TEST_SUPPORT_MODULES:
                continue
            if not any(start <= match.start() < end for start, end in tested):
                problems.append(
                    f"{path}:{line_of(text, match.start())}: test-support module {match.group(1)} used outside tests"
                )
    for path in convention_sources(files):
        for number, line in enumerate((read_text(root, path) or "").splitlines(), 1):
            for match in MODULE_STRING.finditer(line):
                if match.group(1) in TEST_SUPPORT_MODULES and not TEST_CONFIGURATION_STRING.search(line):
                    problems.append(f"{path}:{number}: test-support module {match.group(1)} used outside tests")
    return problems


def shipped_libraries(root, files, catalog, test_support, listed, problems):
    """Returns {accessor: where} for every catalog library the release ships.

    A bundle counts as each of its libraries, and a name the catalog lacks is reported. A
    dependency given as a `group:artifact` coordinate is checked against the notices here,
    and a local file dependency is reported, since nothing records its license.
    """
    shipped = {}

    def use(names, configuration, path, location, ships_here):
        libraries = [n for n in names if not catalog.is_platform(n)]
        if not libraries:
            return
        kind = classify(configuration)
        if kind is None:
            problems.append(f"{location}: unknown dependency configuration")
        elif kind and ships_here:
            for name in libraries:
                shipped.setdefault(name, path)

    sources = [(path, module, ships, gradle_view(root, path), False) for path, module, ships in gradle_scripts(files)]
    sources += [
        (path, None, True, split_source(read_text(root, path) or "", True, keep_strings=True), True)
        for path in convention_sources(files)
    ]
    for path, module, ships, text, calls_only in sources:
        ships_here = ships and module not in test_support
        covered = []
        for start, end, configuration, kind, value in gradle_dependencies(text, calls_only):
            covered.append((start, end))
            location = f"{path}:{line_of(text, start)}"
            if kind == "unknown":
                problems.append(f"{location}: dependency declaration the audit cannot classify")
            elif kind in ("library", "bundle"):
                bundle = kind == "bundle" or value.startswith("bundles.")
                name = value[len("bundles."):] if kind == "library" and bundle else value
                names = catalog.resolve(name, bundle, location, problems)
                use(names, configuration, path, location, ships_here)
            elif kind == "coordinate":
                if is_platform(value):
                    continue
                shipping = classify(configuration)
                if shipping is None:
                    problems.append(f"{location}: unknown dependency configuration")
                elif shipping and ships_here and not {value, value + "-android", value + "-jvm"} & listed:
                    problems.append(f"{location}: {value} ships in the release but is not in {NOTICES}")
            elif kind == "files":
                if not ships:
                    continue  # the build's own classpath, or a test
                shipping = classify(configuration)
                if shipping is None:
                    problems.append(f"{location}: unknown dependency configuration")
                elif shipping and ships_here:
                    problems.append(f"{location}: local file dependency the audit cannot check")
            elif classify(configuration) is None:
                problems.append(f"{location}: unknown dependency configuration")

        def is_covered(offset):
            if any(start <= offset < end for start, end in covered):
                return True
            # A catalog lookup kept in a variable counts where a declaration uses the variable.
            for assignment in VALUE_ASSIGNMENT.finditer(text):
                if assignment.start(2) <= offset < assignment.end(2):
                    use_of = re.compile(r"\b" + re.escape(assignment.group(1)) + r"\b")
                    return any(use_of.search(text, start, end) for start, end in covered)
            return False

        if not calls_only and ships:
            # Only what could ship: a test or debug configuration never reaches the release.
            for match in CONFIGURATION_USE.finditer(text):
                if classify(match.group()) is False:
                    continue
                if not is_covered(match.start()) and not receiver_after_dot(text, match.start()):
                    problems.append(
                        f"{path}:{line_of(text, match.start())}: dependency declaration the audit cannot classify"
                    )
        for match in ANY_LIBRARY.finditer(text):
            if not is_covered(match.start()):
                problems.append(
                    f"{path}:{line_of(text, match.start())}: catalog reference the audit cannot classify"
                )
        for match in CATALOG_LOOKUP.finditer(text):
            if is_covered(match.start()):
                continue
            if match.group(1) != "findBundle" and catalog.is_platform(accessor(match.group(2))):
                continue
            problems.append(f"{path}:{line_of(text, match.start())}: catalog reference the audit cannot classify")
    return shipped


def markdown_link_targets(markdown, base=""):
    """Repository paths that Markdown links in `markdown` point to.

    Inline links `[text](target "title")` and reference definitions `[label]: target`, with
    `<...>` and `#fragment` removed and the path normalized against `base`, the directory of
    the Markdown file. External and in-page links are left out. A file name merely written in
    the text is not a link.
    """
    targets = set()
    for match in list(MARKDOWN_INLINE_LINK.finditer(markdown)) + list(MARKDOWN_REFERENCE.finditer(markdown)):
        target = match.group(1).strip("<>").split("#", 1)[0]
        if not target or re.match(r"[A-Za-z][A-Za-z0-9+.-]*:", target):
            continue
        targets.add(posixpath.normpath(posixpath.join(base, target)))
    return targets


def audit_licenses(root, files):
    problems = []
    license_text = read_text(root, LICENSE)
    if license_text is None:
        problems.append(f"{LICENSE}: missing")
    elif not (license_text.lstrip().startswith("MIT License") and all(m in license_text for m in MIT_MARKERS)):
        problems.append(f"{LICENSE}: not the MIT license")
    notices = read_text(root, NOTICES)
    if notices is None:
        problems.append(f"{NOTICES}: missing")
        notices = ""
    catalog_text = read_text(root, CATALOG)
    if catalog_text is None:
        problems.append(f"{CATALOG}: missing")
        catalog_text = ""
    catalog = Catalog(catalog_text)
    problems.extend(catalog.problems)
    test_support = set(TEST_SUPPORT_MODULES)
    problems.extend(audit_test_support(root, files))
    listed = set(NOTICE_COORDINATE.findall(notices))
    for name, where in sorted(shipped_libraries(root, files, catalog, test_support, listed, problems).items()):
        alias, coordinate, line = catalog.libraries[name]
        if not {coordinate, coordinate + "-android", coordinate + "-jvm"} & listed:
            problems.append(
                f"{CATALOG}:{line}: {alias} ({coordinate}) ships in the release (via {where}) "
                f"but is not in {NOTICES}"
            )

    tracked = set(files)
    targets = markdown_link_targets(notices)
    for target in sorted(targets):
        if not target.startswith(LICENSE_ASSETS + "/") and target != LICENSE_ASSETS:
            continue
        if target not in tracked and not any(p.startswith(target.rstrip("/") + "/") for p in tracked):
            problems.append(f"{NOTICES}: links a license text that is missing: {target[len(LICENSE_ASSETS) + 1:]}")
    for path in sorted(tracked):
        parent = path.rpartition("/")[0]
        if parent == LICENSE_ASSETS and path not in targets:
            problems.append(f"{path}: license text not linked from {NOTICES}")

    bundle_notice = read_text(root, BUNDLE_NOTICE) or ""
    directories = {}
    for path in tracked:
        if path.startswith(BUNDLE_ROOT + "/"):
            relative = path[len(BUNDLE_ROOT) + 1 :]
            if "/" in relative:
                directory, _, name = relative.rpartition("/")
                directories.setdefault(directory, []).append(name)
    for directory, names in sorted(directories.items()):
        location = f"{BUNDLE_ROOT}/{directory}/"
        if "LICENSE" not in names:
            problems.append(f"{location}: bundled third-party files without a LICENSE")
        if f"`{directory}/`" not in bundle_notice:
            problems.append(f"{location}: not recorded in {BUNDLE_NOTICE}")
        if f"`{directory}/`" not in notices:
            problems.append(f"{location}: not recorded in {NOTICES}")
        for name in sorted(n for n in names if LICENSE_NAME.match(n)):
            copy = f"{TEXTMATE_COPIES}/{directory}/{name}"
            if copy not in tracked:
                problems.append(f"{BUNDLE_ROOT}/{directory}/{name}: not shipped as {copy}")
                continue
            copy_data = read_bytes(root, copy)
            source_data = read_bytes(root, f"{BUNDLE_ROOT}/{directory}/{name}")
            if copy_data is None or source_data is None:
                continue  # audit_missing reports each missing side; None == None must not pass
            if copy_data != source_data:
                problems.append(f"{BUNDLE_ROOT}/{directory}/{name}: differs from its shipped copy {copy}")

    for path in sorted(tracked):
        if path == LICENSE or not LICENSE_NAME.match(PurePosixPath(path).name):
            continue
        if path.startswith((BUNDLE_ROOT + "/", LICENSE_ASSETS + "/")):
            continue
        problems.append(f"{path}: third-party license file outside the recorded bundles")
    return problems


# --- 3. Analytics and tracking -----------------------------------------------------------------

# Maven groups, Gradle plugin ids and Java packages of analytics, crash reporting, attribution and
# ads SDKs. Matched as whole dotted names, so `com.segment.analytics` does not hit `segment`.
TRACKING_GROUPS = (
    "com.google.firebase", "com.google.gms.google-services", "com.google.android.gms.ads",
    "com.google.android.gms.analytics", "com.google.android.gms.measurement",
    "com.google.android.gms.tagmanager", "com.google.android.ump", "com.google.ads",
    "com.crashlytics", "io.fabric", "io.sentry", "com.bugsnag", "com.microsoft.appcenter",
    "com.mixpanel", "com.amplitude", "com.segment.analytics", "com.flurry", "com.appsflyer",
    "com.adjust.sdk", "io.branch", "com.facebook.android", "com.facebook.appevents",
    "com.facebook.ads", "com.onesignal", "com.newrelic", "com.datadoghq", "com.datadog",
    "com.instabug", "com.posthog", "io.embrace", "ly.count", "ch.acra", "org.acra",
    "org.matomo", "org.piwik", "com.yandex.android", "com.yandex.metrica", "com.applovin",
    "com.unity3d.ads", "com.ironsource", "com.heapanalytics", "com.heap", "com.smartlook",
    "com.uxcam", "com.logrocket", "com.rollbar", "io.rollbar", "com.splunk.mint",
    "com.github.instacart.truetime",
)
# Maven artifacts of Play Services modules whose group is shared with harmless ones.
# Artifact names, matched on the artifact part of every `group:artifact` and catalog `name =`
# value whatever its group (a relocated or renamed group must not hide them). Prefixes match any
# continuation, so `play-services-ads-lite`, `play-services-measurement-api` and `firebase-*` are
# caught; stems match as a whole word at the start (`sentry`, `sentry-android`, `sentry-okhttp`).
TRACKING_ARTIFACT_PREFIXES = (
    "play-services-ads", "play-services-analytics", "play-services-measurement",
    "play-services-tagmanager", "play-services-appset", "firebase-", "crashlytics",
)
TRACKING_ARTIFACT_STEMS = (
    "sentry", "bugsnag", "appcenter", "mixpanel", "amplitude", "flurry", "appsflyer", "onesignal",
    "newrelic", "instabug", "posthog", "embrace", "countly", "acra", "matomo", "piwik",
    "applovin", "ironsource", "smartlook", "uxcam", "logrocket", "rollbar", "mobmetricalib",
    "analytics-android", "facebook-core", "facebook-android-sdk", "audience-network-sdk",
    "unity-ads", "dd-sdk-android",
)
TRACKING_ARTIFACT_NAME = re.compile(
    r"(?:" + "|".join(map(re.escape, TRACKING_ARTIFACT_PREFIXES)) + r")"
    r"|(?:" + "|".join(map(re.escape, TRACKING_ARTIFACT_STEMS)) + r")(?![A-Za-z0-9])",
    re.I,
)
ARTIFACT_COORDINATE = re.compile(r"(?<![\w.-])[A-Za-z0-9_.-]+:([A-Za-z0-9_.-]+)")
ARTIFACT_NAME_FIELD = re.compile(r"""\bname\s*[:=]\s*["']([A-Za-z0-9_.-]+)["']""")
PYTHON_TRACKING = (
    "sentry_sdk", "raven", "posthog", "mixpanel", "segment", "amplitude", "bugsnag", "rollbar",
    "newrelic", "ddtrace", "datadog", "firebase_admin", "google.analytics", "analytics",
)
# The advertising ID and the Privacy Sandbox ad-services family: AD_ID, ATTRIBUTION, TOPICS,
# CUSTOM_AUDIENCE and any later ACCESS_ADSERVICES_* permission.
ADVERTISING_PERMISSIONS = ("com.google.android.gms.permission.AD_ID",)
ADVERTISING_PERMISSION_PREFIXES = ("android.permission.ACCESS_ADSERVICES_",)


def is_advertising_permission(name):
    return name in ADVERTISING_PERMISSIONS or name.startswith(ADVERTISING_PERMISSION_PREFIXES)
RELEASE_CLASSPATH_NAME = "release-runtime-classpath.txt"

DYNAMIC_IMPORTERS = ("__import__", "import_module")


def python_imports(text):
    """Yields (line, full module name) for every import in Python source, read with `ast`.

    `import a.b as c` gives `a.b`; `from a import b` gives `a` and `a.b`, so `from urllib
    import request` is `urllib.request`; `__import__("x")` and `import_module("x")` (also as
    `importlib.import_module`) with a literal name give `x`. Relative imports are the
    package's own modules and are skipped. Raises SyntaxError for source `ast` cannot read.
    """
    for node in ast.walk(ast.parse(text)):
        if isinstance(node, ast.Import):
            for alias in node.names:
                yield node.lineno, alias.name
        elif isinstance(node, ast.ImportFrom) and node.level == 0 and node.module:
            yield node.lineno, node.module
            for alias in node.names:
                if alias.name != "*":
                    yield node.lineno, f"{node.module}.{alias.name}"
        elif isinstance(node, ast.Call) and node.args:
            function = node.func
            name = function.id if isinstance(function, ast.Name) else getattr(function, "attr", None)
            argument = node.args[0]
            if (
                name in DYNAMIC_IMPORTERS
                and isinstance(argument, ast.Constant)
                and isinstance(argument.value, str)
            ):
                yield node.lineno, argument.value


def denied_imports(path, text, denied, message):
    """`path:line: message` for each import of a name in `denied` or below it."""
    try:
        imports = sorted(set(python_imports(text)))
    except (SyntaxError, ValueError):
        return [f"{path}: Python source the audit cannot parse"]
    return sorted(
        {
            f"{path}:{line}: {message}"
            for line, name in imports
            if any(name == bad or name.startswith(bad + ".") for bad in denied)
        }
    )


def dotted(name, wildcard=False):
    """A regex for a dotted name that may have whitespace around its dots, as Kotlin allows."""
    body = r"\s*\.\s*".join(re.escape(part) for part in name.split("."))
    return body + (r"\s*\.\s*\*" if wildcard else r"(?![\w-])")


class DottedNames:
    """Finds whole dotted names: a cheap pass for first segments, then the full names there."""

    def __init__(self, names, wildcards=()):
        firsts = sorted({name.split(".")[0] for name in (*names, *wildcards)})
        self.start = re.compile(r"\b(?:" + "|".join(map(re.escape, firsts)) + r")\b")
        self.full = re.compile(
            "|".join([dotted(n) for n in names] + [dotted(n, wildcard=True) for n in wildcards])
        )

    def finditer(self, text):
        for match in self.start.finditer(text):
            before = text[match.start() - 1 : match.start()]
            if before and (before.isalnum() or before in "_.-"):
                continue
            full = self.full.match(text, match.start())
            if full:
                yield full


TRACKING_NAME = DottedNames(TRACKING_GROUPS)
def is_tracking_name(name):
    """True for a Maven group or plugin id that is, or is under, a TRACKING_GROUPS name."""
    return any(name == group or name.startswith(group + ".") for group in TRACKING_GROUPS)


def tracking_artifacts(text):
    """Yields the offset of each artifact name that starts with a denied prefix or stem."""
    for pattern in (ARTIFACT_COORDINATE, ARTIFACT_NAME_FIELD):
        for match in pattern.finditer(text):
            if TRACKING_ARTIFACT_NAME.match(match.group(1)):
                yield match.start(1)


def is_build_file(path):
    return (
        path == CATALOG
        or path == NOTICES
        or path.endswith((".gradle.kts", ".gradle"))
        or path.startswith("build-logic/src/main/")
        or path.endswith("/" + RELEASE_CLASSPATH_NAME)
        or PurePosixPath(path).name == "gradle.properties"
    )


def audit_tracking(root, files):
    problems = []
    for path in files:
        suffix = PurePosixPath(path).suffix
        if path == CATALOG:
            text = read_text(root, path) or ""
            catalog = Catalog(text)
            lines = set()
            for _alias, coordinate, line in catalog.libraries.values():
                group, artifact = coordinate.split(":", 1)
                if is_tracking_name(group) or TRACKING_ARTIFACT_NAME.match(artifact):
                    lines.add(line)
            for plugin_id, line in catalog.plugins.values():
                if is_tracking_name(plugin_id):
                    lines.add(line)
            if catalog.problems:  # what the parser could not read, the text scan still sees
                offsets = {m.start() for m in TRACKING_NAME.finditer(text)} | set(tracking_artifacts(text))
                lines |= {line_of(text, offset) for offset in offsets}
            for line in sorted(lines):
                problems.append(f"{path}:{line}: analytics or tracking dependency")
        elif is_build_file(path):
            text = read_text(root, path) or ""
            offsets = {m.start() for m in TRACKING_NAME.finditer(text)} | set(tracking_artifacts(text))
            for line in sorted({line_of(text, offset) for offset in offsets}):
                problems.append(f"{path}:{line}: analytics or tracking dependency")
        elif suffix in (".kt", ".java"):
            # Strings too: `Class.forName("<package>.X")` loads an SDK with no import.
            code = source_view(root, path, keep_strings=True)
            for match in TRACKING_NAME.finditer(code):
                problems.append(f"{path}:{line_of(code, match.start())}: analytics or tracking import")
        elif suffix == ".py":
            text = read_text(root, path) or ""
            problems.extend(denied_imports(path, text, PYTHON_TRACKING, "analytics or tracking import"))
        elif PurePosixPath(path).name == "AndroidManifest.xml":
            text = read_text(root, path) or ""
            manifest = manifest_attributes(root, path)
            if manifest is None:
                problems.append(f"{path}: manifest the audit cannot parse")
            else:
                for line, element, value in manifest.get("name", []):
                    if element.startswith("uses-permission") and is_advertising_permission(value):
                        problems.append(f"{path}:{line}: advertising ID permission")
            # Any mention, attribute or not: a receiver, service or meta-data of an SDK.
            for match in TRACKING_NAME.finditer(text):
                problems.append(f"{path}:{line_of(text, match.start())}: analytics or tracking component")
    return problems


# --- 4. Network targets ------------------------------------------------------------------------

APP_MANIFEST = "app/src/main/AndroidManifest.xml"
ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
XML_RESOURCE = re.compile(r"@xml/([A-Za-z0-9_]+)")
XML_RESOURCE_FILE = re.compile(r"/src/[^/]+/res/xml(?:-[^/]+)?/([A-Za-z0-9_]+)\.xml$")
CLEARTEXT_DOMAIN = "127.0.0.1"
MAIN_SOURCE = re.compile(r"^((?:app|core/[^/]+|feature/[^/]+/[^/]+))/src/main/")
_PACKAGE = "src/main/kotlin/io/github/thewisekd/phopencode"

# Main-source paths (exact files, or directories ending in /) that may use network APIs, read
# from the code. Nothing else in the app opens a connection.
NETWORK_CODE = {
    "core/ssh/src/main/":
        "the SSH connection (sshj, the host name lookup it needs) and the direct-tcpip tunnel; "
        "the tunnel's SocketFactory is the only way other code reaches the server",
    "core/api-http/src/main/":
        "OkHttp client of `opencode serve` at http://127.0.0.1, built on the tunnel's "
        "SocketFactory (HttpOpencodeClient.create), so its connections are SSH channels",
    f"core/remote/{_PACKAGE}/core/remote/connection/OpencodeClientFactory.kt":
        "hands the SSH session's tunnel SocketFactory to core:api-http",
    f"core/remote/{_PACKAGE}/core/remote/detect/PortProbe.kt":
        "connects through the tunnel SocketFactory to a port of the server's loopback; refuses "
        "any host that is not a loopback literal",
    f"core/remote/{_PACKAGE}/core/remote/detect/ScanReport.kt":
        "InetAddress.getByName on an IPv6 literal only, which parses and never looks a name up",
}

# Main-source files that hand a URL to another app. Each was read: none fetches anything itself.
LINK_OPENERS = {
    f"core/markdown/{_PACKAGE}/core/markdown/LinkConfirmation.kt":
        "a markdown link opens in the browser only after the user confirms the dialog",
    f"core/markdown/{_PACKAGE}/core/markdown/MarkdownView.kt":
        "routes markdown links through LinkConfirmation",
    f"feature/chat/ui/{_PACKAGE}/feature/chat/ui/ChatGraph.kt":
        "opens a form's http(s) web step in the browser when the user taps it",
    f"feature/notify/ui/{_PACKAGE}/feature/notify/ui/setup/SetupScreen.kt":
        "opens the ntfy:// subscription link in the ntfy app, or ntfy's phone-app page when the "
        "user taps it after the app was found missing",
}

# Hosts that main code may name in a URL literal, per file, because the URL is only shown or
# opened by the user, never fetched by the app.
DISPLAY_URLS = {
    f"feature/settings/ui/{_PACKAGE}/feature/settings/ui/licenses/LicenseTexts.kt": {
        "github.com": "source links in the license notices shown on the licenses screen",
        "www.eclipse.org": "the EPL notice's source link, shown on the licenses screen",
    },
    f"feature/notify/ui/{_PACKAGE}/feature/notify/ui/setup/SetupScreen.kt": {
        "docs.ntfy.sh": "ntfy's phone-app page, opened only by the user's tap (LINK_OPENERS)",
    },
    f"feature/notify/logic/{_PACKAGE}/feature/notify/logic/contract/Ntfy.kt": {
        "ntfy.sh": "the default ntfy server (decision D5); the app never connects to it, it "
                   "writes it into the helper's config and the ntfy:// link",
    },
}

NETWORK_APIS = (
    "java.net.Socket", "java.net.ServerSocket", "java.net.DatagramSocket",
    "java.net.MulticastSocket", "java.net.URL", "java.net.URLConnection",
    "java.net.HttpURLConnection", "java.net.InetAddress", "java.net.http",
    "javax.net.SocketFactory", "javax.net.ServerSocketFactory", "javax.net.ssl",
    "java.nio.channels.SocketChannel", "java.nio.channels.ServerSocketChannel",
    "java.nio.channels.DatagramChannel", "java.nio.channels.AsynchronousSocketChannel",
    "okhttp3", "retrofit2", "io.ktor", "com.android.volley", "org.apache.http",
    "net.schmizz.sshj", "com.hierynomus.sshj", "com.jcraft.jsch", "android.webkit.WebView",
    "android.net.http", "coil3.network", "io.grpc", "org.java_websocket",
)
NETWORK_WILDCARDS = ("java.net", "javax.net", "java.nio.channels", "android.webkit")
NETWORK_API = DottedNames(NETWORK_APIS, NETWORK_WILDCARDS)
LINK_OPENER = re.compile(r"\b(?:LocalUriHandler|UriHandler|ACTION_VIEW|CustomTabsIntent|openUri)\b")
URL_LITERAL = re.compile(r"\b(?:https?|wss?|ftp)://([^\s/\"'<>`?#)\],;|]*)")
XML_COMMENT = re.compile(r"<!--.*?-->", re.S)
XML_NAMESPACE = re.compile(r"\bxmlns(?::[\w.-]+)?\s*=\s*(?:\"[^\"]*\"|'[^']*')")

HELPER_NETWORK_FILES = {
    "helper/notifyd/ntfy.py": "publishes to the configured ntfy server",
    "helper/notifyd/sse.py": "reads the event stream of the local opencode server",
}
HELPER_HOSTS = {"ntfy.sh": "the default ntfy server, decision D5"}
PYTHON_NETWORK = (
    "socket", "ssl", "http.client", "urllib.request", "urllib3", "requests", "httpx", "aiohttp",
    "ftplib", "smtplib", "telnetlib", "xmlrpc.client", "websocket", "websockets", "asyncio.streams",
)
SHELL_NETWORK = re.compile(r"(?<![\w-])(?:curl|wget|nc|ncat|netcat|socat|ssh|scp|rsync|telnet)(?![\w-])|/dev/(?:tcp|udp)/")


def blank(segment):
    return re.sub(r"[^\n]", " ", segment)


SPECIAL = re.compile(r"//|/\*|\"\"\"|\"|'")
BLOCK_COMMENT_MARK = re.compile(r"/\*|\*/")
STRING_REST = re.compile(r'(?:\\.|[^"\\\n])*(?:"|$)', re.M)
SINGLE_STRING_REST = re.compile(r"(?:\\.|[^'\\\n])*(?:'|$)", re.M)
CHAR_LITERAL = re.compile(r"'(?:\\.[^'\n]*|[^'\\\n])'")
_SOURCE_CACHE = {}


STRING_STOP = {'"': re.compile(r'["\\\n]|\$\{'), '"""': re.compile(r'"""|\$\{')}
INTERPOLATION_STOP = re.compile(r'//|/\*|"""|["\'{}]')


def skip_string(text, index, quote, nested=True):
    """Reads a Kotlin or Groovy string whose opening `quote` ends just before `index`.

    Returns (end, codes): the index after the closing quote, and the (start, end) spans of
    the code inside each top-level `${ ... }`. A `\\$` escape is not an interpolation. An
    unterminated `"` string ends at the line break, an unterminated raw string at the end.
    """
    codes, length, stop = [], len(text), STRING_STOP[quote]
    while True:
        match = stop.search(text, index)
        if not match:
            return length, codes
        token, index = match.group(), match.end()
        if token == "\\":
            index += 1
        elif token == "\n":
            return match.start(), codes
        elif token == "${":
            close = skip_interpolation(text, index, nested)
            codes.append((index, max(index, close - 1)))
            index = close
        else:  # the closing quote; a raw string may end in extra quotes
            while quote == '"""' and index < length and text[index] == '"':
                index += 1
            return index, codes


def skip_interpolation(text, index, nested=True):
    """Returns the index after the `}` closing an interpolation whose code starts at `index`.

    Nested braces (lambdas) and nested strings, with interpolations of their own, are
    skipped whole, and so are comments and character literals: a brace in `/* { */`,
    `// {` or `'{'` does not count. Block comments nest only when `nested` (Kotlin), as in
    split_source; in Groovy the first `*/` ends one.
    """
    depth, length = 1, len(text)
    while True:
        match = INTERPOLATION_STOP.search(text, index)
        if not match:
            return length
        token, index = match.group(), match.end()
        if token == "//":
            end = text.find("\n", index)
            index = length if end < 0 else end
        elif token == "/*":
            comment_depth, index = 1, length
            for mark in BLOCK_COMMENT_MARK.finditer(text, match.end()):
                comment_depth += 1 if mark.group() == "/*" and nested else -1 if mark.group() == "*/" else 0
                if comment_depth == 0:
                    index = mark.end()
                    break
        elif token in ('"""', '"'):
            index, _ = skip_string(text, index, token, nested)
        elif token == "'":
            literal = CHAR_LITERAL.match(text, match.start())
            index = literal.end() if literal else index
        elif token == "{":
            depth += 1
        else:
            depth -= 1
            if depth == 0:
                return index


def split_source(text, nested, keep_strings, groovy=False):
    """Returns `text` with comments, and unless `keep_strings` string literals, blanked.

    In Kotlin (`nested`) and Groovy double-quoted strings only the literal text is blanked:
    the code inside `${ ... }` runs, so it stays and is itself read as source.
    Blanked characters become spaces and line breaks stay, so offsets and line numbers hold.
    Block comments nest in Kotlin and not in Java or Groovy; in Groovy (`groovy`) single
    quotes delimit strings too, so `'https://...'` is not read as a comment. Regex-driven, so
    plain code is copied in chunks: a character loop takes tens of seconds over this
    repository's sources.
    """
    out = []
    index, length = 0, len(text)
    while index < length:
        match = SPECIAL.search(text, index)
        if not match:
            out.append(text[index:])
            break
        out.append(text[index : match.start()])
        token, start = match.group(), match.start()
        if token == "//":
            end = text.find("\n", start)
            end = length if end < 0 else end
            out.append(blank(text[start:end]))
        elif token == "/*":
            depth, end = 1, length
            for mark in BLOCK_COMMENT_MARK.finditer(text, match.end()):
                depth += 1 if mark.group() == "/*" and nested else -1 if mark.group() == "*/" else 0
                if depth == 0:
                    end = mark.end()
                    break
            out.append(blank(text[start:end]))
        elif token in ('"""', '"') and (nested or groovy):
            # Kotlin and Groovy strings interpolate: `${ ... }` is code and stays code.
            end, codes = skip_string(text, match.end(), token, nested)
            if keep_strings:
                out.append(text[start:end])
            else:
                cursor = start
                for code_start, code_end in codes:
                    out.append(blank(text[cursor:code_start]))
                    out.append(split_source(text[code_start:code_end], nested, False, groovy))
                    cursor = code_end
                out.append(blank(text[cursor:end]))
        elif token == '"""':
            end = text.find('"""', match.end())
            end = length if end < 0 else end + 3
            while end < length and text[end] == '"':
                end += 1
            out.append(text[start:end] if keep_strings else blank(text[start:end]))
        elif token == '"':
            end = STRING_REST.match(text, match.end()).end()
            out.append(text[start:end] if keep_strings else blank(text[start:end]))
        elif groovy:
            if text.startswith("'''", start):
                end = text.find("'''", start + 3)
                end = length if end < 0 else end + 3
            else:
                end = SINGLE_STRING_REST.match(text, match.end()).end()
            out.append(text[start:end] if keep_strings else blank(text[start:end]))
        else:
            literal = CHAR_LITERAL.match(text, start)
            end = literal.end() if literal else start + 1
            out.append(blank(text[start:end]) if literal and not keep_strings else text[start:end])
        index = end
    return "".join(out)


def source_view(root, path, keep_strings):
    """The cached split_source view of a Kotlin or Java file."""
    key = (root, path, keep_strings)
    if key not in _SOURCE_CACHE:
        text = read_text(root, path) or ""
        _SOURCE_CACHE[key] = split_source(text, not path.endswith(".java"), keep_strings)
    return _SOURCE_CACHE[key]


HOST_RUN = re.compile(r"[A-Za-z0-9.-]*")
TEMPLATE_STARTS = "${%*"


def url_host(authority):
    """Returns (host, templated) for a URL's authority.

    Only the host decides: a templated user, port, path or query leaves a literal host to be
    checked. The host counts as templated when it starts with a template (`$host`, `${h}`,
    `{h}`, `%1$s`, `<h>`), or when its literal part is cut short by one: no dot yet
    (`evil${x}`) or ending in a dot or hyphen (`api.$domain`). `evil.example$path` keeps the
    literal host `evil.example`.
    """
    rest = authority.replace("\\", "").rpartition("@")[2]
    if rest.startswith("["):
        host = rest[1:].partition("]")[0].lower()
        return host, any(char in host for char in TEMPLATE_STARTS + "{")
    run = HOST_RUN.match(rest).group()
    after = rest[len(run):]
    if after[:1] and after[0] in TEMPLATE_STARTS:
        if not run or "." not in run or not run[-1].isalnum():
            return run.lower(), True
    return run.lower(), False


def is_local_or_reserved(host):
    if host in ("localhost",) or check_boundaries.is_reserved_host(host):
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def literal_hosts(text):
    """Yields (offset, host) for each URL literal with a fixed, real-looking host."""
    for match in URL_LITERAL.finditer(text):
        host, templated = url_host(match.group(1))
        if templated or not re.search(r"[A-Za-z0-9]", host):
            continue
        yield match.start(), host


def main_module(path, test_support):
    match = MAIN_SOURCE.match(path)
    if not match or "/src/main/assets/" in path:
        return None
    module = ":" + match.group(1).replace("/", ":")
    return None if module in test_support else match.group(1)


def listed(path, table):
    return any(path == key or (key.endswith("/") and path.startswith(key)) for key in table)


def audit_network_config(path, text):
    """Problems in one network security config file."""
    problems = []
    try:
        config = ElementTree.fromstring(text)
    except ElementTree.ParseError:
        return [f"{path}: not well-formed XML"]
    def flag(element, name):
        return (element.get(name) or "").strip()

    base = config.find("base-config")
    if base is None or flag(base, "cleartextTrafficPermitted") != "false":
        problems.append(f"{path}: cleartext must be denied in base-config")
    for domain_config in config.iter("domain-config"):
        permitted = flag(domain_config, "cleartextTrafficPermitted")
        if permitted not in ("", "true", "false"):
            problems.append(f"{path}: cleartextTrafficPermitted set to a value the audit cannot verify")
            continue
        if permitted != "true":
            continue
        for domain in domain_config.iter("domain"):
            if (domain.text or "").strip() != CLEARTEXT_DOMAIN or flag(domain, "includeSubdomains") not in ("", "false"):
                problems.append(f"{path}: cleartext allowed beyond {CLEARTEXT_DOMAIN}")
        if domain_config.find("domain-config") is not None:
            problems.append(f"{path}: nested domain-config under a cleartext one")
    if config.find("debug-overrides") is not None:
        problems.append(f"{path}: debug-overrides present")
    return problems


_MANIFEST_CACHE = {}


def manifest_attributes(root, path):
    """{android attribute name: [(line, element, value)]} of a manifest, or None if unparsable.

    Read with expat and namespaces resolved, so quoting, whitespace, attribute order and the
    prefix bound to the android namespace do not matter. Values are stripped. The line is the
    attribute's own line when it can be found in the start tag, else the element's.
    """
    key = (root, path)
    if key in _MANIFEST_CACHE:
        return _MANIFEST_CACHE[key]
    data = read_bytes(root, path)
    attributes = None
    if data is not None:
        found = []
        parser = expat.ParserCreate(namespace_separator="}")

        def start(name, attrs):
            for qualified, value in attrs.items():
                namespace, _, local = qualified.rpartition("}")
                if namespace == ANDROID_NAMESPACE:
                    found.append((parser.CurrentByteIndex, name.rpartition("}")[2], local, value.strip()))

        parser.StartElementHandler = start
        try:
            parser.Parse(data, True)
            attributes = {}
            for offset, element, local, value in found:
                tag_end = data.find(b">", offset)
                tag_end = len(data) if tag_end < 0 else tag_end
                spot = re.compile(rb"[\s:]" + re.escape(local.encode()) + rb"\s*=").search(data, offset, tag_end)
                line = data.count(b"\n", 0, spot.start() + 1 if spot else offset) + 1
                attributes.setdefault(local, []).append((line, element, value))
        except expat.ExpatError:
            attributes = None
    _MANIFEST_CACHE[key] = attributes
    return attributes


def audit_cleartext(root, files):
    """Audits the network security config that the manifests name, wherever it is.

    Every manifest in the tree, library and test ones included, is read: Android merges them,
    so any of them can set or replace the config. Each `@xml/<name>` reference is resolved to
    every `res/xml*/<name>.xml` in the tree, all source sets and modules, because resource
    merging lets any of those copies win in some build. A reference that resolves to nothing
    or is not an `@xml/` resource, more than one distinct config, or no reference at all is a
    problem of its own.
    """
    problems = []
    references = {}
    for path in files:
        if PurePosixPath(path).name != "AndroidManifest.xml":
            continue
        manifest = manifest_attributes(root, path)
        if manifest is None:
            problems.append(f"{path}: manifest the audit cannot parse")
            continue
        for line, _element, value in manifest.get("usesCleartextTraffic", []):
            if value == "true":
                problems.append(f"{path}:{line}: cleartext traffic enabled")
            elif value != "false":
                problems.append(f"{path}:{line}: usesCleartextTraffic set to a value the audit cannot verify")
        for line, _element, value in manifest.get("networkSecurityConfig", []):
            location = f"{path}:{line}"
            resource = XML_RESOURCE.fullmatch(value)
            configs = [
                candidate for candidate in files
                if resource and XML_RESOURCE_FILE.search(candidate)
                and XML_RESOURCE_FILE.search(candidate).group(1) == resource.group(1)
            ]
            if not configs:
                problems.append(f"{location}: networkSecurityConfig does not resolve to a tracked res/xml file")
                continue
            references.setdefault(resource.group(1), []).append(location)
            for config in configs:
                problems.extend(audit_network_config(config, read_text(root, config) or ""))
    if not references and not any("networkSecurityConfig does not resolve" in p for p in problems):
        problems.append(f"{APP_MANIFEST}: no manifest sets android:networkSecurityConfig")
    if len(references) > 1:
        for locations in references.values():
            for location in locations:
                problems.append(f"{location}: more than one network security config in the manifests")
    return problems


def audit_app_network(root, files, test_support):
    problems = []
    for path in files:
        if main_module(path, test_support) is None:
            continue
        suffix = PurePosixPath(path).suffix
        if suffix in (".kt", ".java"):
            code = source_view(root, path, keep_strings=False)
            if not listed(path, NETWORK_CODE):
                for match in NETWORK_API.finditer(code):
                    problems.append(
                        f"{path}:{line_of(code, match.start())}: network API outside the SSH and tunnel code"
                    )
            if not listed(path, LINK_OPENERS):
                for match in LINK_OPENER.finditer(code):
                    problems.append(
                        f"{path}:{line_of(code, match.start())}: opens links in another app; not a reviewed link opener"
                    )
            literals = source_view(root, path, keep_strings=True)
        elif suffix == ".xml":
            text = read_text(root, path) or ""
            literals = XML_NAMESPACE.sub(
                lambda m: blank(m.group()), XML_COMMENT.sub(lambda m: blank(m.group()), text)
            )
        else:
            continue
        allowed_hosts = DISPLAY_URLS.get(path, {})
        for offset, host in literal_hosts(literals):
            if not is_local_or_reserved(host) and host not in allowed_hosts:
                problems.append(f"{path}:{line_of(literals, offset)}: hard-coded external URL in app code")
    return problems


def audit_helper(root, files):
    problems = []
    for path in files:
        if not path.startswith("helper/") or path.startswith("helper/tests/"):
            continue
        suffix = PurePosixPath(path).suffix
        if suffix == ".py":
            text = read_text(root, path) or ""
            if path not in HELPER_NETWORK_FILES:
                problems.extend(
                    denied_imports(path, text, PYTHON_NETWORK, "network module outside ntfy.py and sse.py")
                )
        elif suffix == ".sh":
            text = read_text(root, path) or ""
            for number, line in enumerate(text.splitlines(), 1):
                code = line.split("#", 1)[0] if not line.lstrip().startswith("#!") else ""
                if SHELL_NETWORK.search(code):
                    problems.append(f"{path}:{number}: helper script reaches the network")
        else:
            continue
        for offset, host in literal_hosts(text):
            if not is_local_or_reserved(host) and host not in HELPER_HOSTS:
                problems.append(
                    f"{path}:{line_of(text, offset)}: helper URL to a host other than the ntfy server or loopback"
                )
    return problems


# --- Driver ------------------------------------------------------------------------------------


def audit_missing(root, files):
    """A tracked file deleted from the working tree, reported once at its path.

    The working-tree rules read files through read_bytes, where a missing file is None, and
    most of them then treat it as empty. A deletion must not pass that way (two missing
    copies of a license compare equal, a missing source has no imports), so every tracked
    path that is not on disk is a problem of its own.
    """
    return [
        f"{path}: tracked but missing from the working tree"
        for path in files
        if not os.path.lexists(root / path)
    ]


def run_audit(root, skip_history=False):
    files = check_boundaries.list_files(root)
    problems = []
    if not skip_history:
        problems.extend(audit_history(root))
    test_support = set(TEST_SUPPORT_MODULES)
    problems.extend(audit_missing(root, files))
    problems.extend(audit_licenses(root, files))
    problems.extend(audit_tracking(root, files))
    problems.extend(audit_cleartext(root, files))
    problems.extend(audit_app_network(root, files, test_support))
    problems.extend(audit_helper(root, files))
    return sorted(set(problems))


def main():
    parser = argparse.ArgumentParser(description="Audit the repository before a release.")
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT, help="repository root")
    parser.add_argument(
        "--skip-history", action="store_true", help="check the working tree only, not the git history"
    )
    arguments = parser.parse_args()
    root = arguments.root.resolve()
    sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")
    if not root.is_dir():
        print(f"audit_release: error: {root} is not a directory", file=sys.stderr)
        return 2
    try:
        problems = run_audit(root, arguments.skip_history)
    except check_boundaries.CheckError as error:
        print(f"audit_release: error: {error}", file=sys.stderr)
        return 2
    for problem in problems:
        print(problem)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
