# Shared by run-review.sh and run-security.sh. Source it, do not run it.
#
# Exit codes of both scripts:
#   0  the model reviewed the change set; stdout is `OK` or `- file:line — problem` lines,
#      or --print-input printed the assembled input without calling a model
#   2  usage error, or the directory, ref or path is unusable
#   3  nothing to review (an empty change set is not a pass); with --paths, no
#      tracked text file at HEAD matches the paths
#   4  change set too large to send whole; with --paths, split the paths into
#      smaller chunks
#   5  tool failure: expired login, timeout, network, crash
#   6  the model's answer broke the output contract
# Any non-zero exit means there is no verdict. Never read one as a pass.

die() {
  local code=$1
  shift
  printf '%s: %s\n' "${0##*/}" "$*" >&2
  exit "$code"
}

enter_repo() {
  local root
  [[ -d $1 ]] || die 2 "no such directory: $1"
  root=$(git -C "$1" rev-parse --show-toplevel 2>/dev/null) || die 2 "not a git work tree: $1"
  cd "$root"
}

# Prints tracked changes against HEAD, then untracked files as additions.
# A commit takes the index, not the work tree, so when the index differs from
# both HEAD and the work tree, it prints the staged diff (HEAD to index) and
# then the unstaged one (index to work tree): an edit that is staged and then
# undone on disk still reaches the model. Otherwise one diff against HEAD
# covers both.
# Callers run this under `||`, which turns `set -e` off here, so every step
# checks its own status: a partial patch must never reach the model.
uncommitted_patch() {
  local base=HEAD file code staged=0 unstaged=0
  git rev-parse --verify --quiet HEAD >/dev/null || base=$(git hash-object -t tree /dev/null) || return 1
  git diff --cached --quiet --no-ext-diff --no-textconv "$base" -- || staged=$?
  git diff --quiet --no-ext-diff --no-textconv -- || unstaged=$?
  ((staged <= 1 && unstaged <= 1)) || return 1
  if ((staged && unstaged)); then
    git diff --cached --no-color --no-ext-diff --no-textconv "-U$1" "$base" -- || return 1
    git diff --no-color --no-ext-diff --no-textconv "-U$1" -- || return 1
  else
    git diff --no-color --no-ext-diff --no-textconv "-U$1" "$base" -- || return 1
  fi
  git ls-files --others --exclude-standard -z | while IFS= read -r -d '' file; do
    code=0
    git diff --no-index --no-color --no-ext-diff --no-textconv "-U$1" -- /dev/null "$file" || code=$?
    ((code <= 1)) || exit 1
  done
}

# Wraps a patch in markers and sets TOKEN. The token appears only after the
# patch, so a model that echoes it has proved the whole patch reached it.
# LABEL names the content in the markers (default: CHANGE SET).
frame_payload() {
  local patch=$1 payload=$2 max_bytes=$3 label=${4:-CHANGE SET} size
  [[ -s $patch ]] || die 3 "nothing to review in $PWD"
  size=$(wc -c <"$patch")
  ((size <= max_bytes)) || die 4 "change set is $size bytes, limit is $max_bytes; split the task"
  TOKEN=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
  {
    printf '<<<BEGIN UNTRUSTED %s>>>\n' "$label"
    cat "$patch"
    printf '\n<<<END UNTRUSTED %s token=%s>>>\n' "$label" "$TOKEN"
  } >"$payload"
}

# Reads only the committed tree at HEAD, never the work tree or the index, so
# untracked, ignored and uncommitted files cannot reach a model. Only regular
# files count: symlinks and submodules are skipped. Blobs are read raw, without
# textconv or filters, and streamed, so a large file is never held whole. A
# file with a NUL byte anywhere or invalid UTF-8 is binary and is skipped. Run
# it from the top of the work tree. Modes:
#   audit_source text MAX_BYTES OUT PATH...
#     writes every text file at HEAD under the PATHs (relative to the top; `.`
#     is everything) to OUT, whole and with line numbers. Exits 2 for a bad
#     path or no HEAD, 3 if no tracked text file matches, 4 if OUT would
#     exceed MAX_BYTES.
#   audit_source export DIR EXCLUDE_REGEX
#     writes every file at HEAD whose path does not match EXCLUDE_REGEX into DIR.
audit_source() {
  local code=0
  python3 -I - "$@" <<'PYTHON' || code=$?
import codecs
import os
import posixpath
import re
import subprocess
import sys


def fail(code, message):
    sys.stderr.write(message + "\n")
    sys.exit(code)


def head_files():
    listing = subprocess.run(
        ["git", "ls-tree", "-r", "-z", "--full-tree", "HEAD"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
    )
    if listing.returncode != 0:
        fail(2, "no commit at HEAD to audit")
    files = []
    for entry in listing.stdout.split(b"\0"):
        if not entry:
            continue
        meta, path = entry.split(b"\t", 1)
        mode, kind, oid = meta.split(b" ")
        if kind == b"blob" and mode in (b"100644", b"100755"):
            files.append((path.decode("utf-8", "surrogateescape"), oid))
    return files


class Blobs:
    """Streams blobs from one `git cat-file --batch`, so no blob is held whole."""

    def __init__(self):
        self.git = subprocess.Popen(
            ["git", "cat-file", "--batch"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        )

    def chunks(self, oid):
        self.git.stdin.write(oid + b"\n")
        self.git.stdin.flush()
        header = self.git.stdout.readline().split()
        if len(header) != 3 or header[1] != b"blob":
            fail(2, "git cat-file failed")
        left = int(header[2])
        while left:
            chunk = self.git.stdout.read(min(left, 1 << 16))
            if not chunk:
                fail(2, "git cat-file failed")
            left -= len(chunk)
            yield chunk
        self.git.stdout.read(1)


def normalize(path):
    if not path or path.startswith("/"):
        fail(2, "path must be relative to the top of the repository: " + path)
    clean = posixpath.normpath(path)
    if clean == ".." or clean.startswith("../"):
        fail(2, "path leaves the repository: " + path)
    return clean


def under(file, prefixes):
    return any(p == "." or file == p or file.startswith(p + "/") for p in prefixes)


def read_text(chunks, limit):
    """Returns the blob as text, None if it is binary, or False if it is text over limit.

    Any NUL byte or invalid UTF-8 makes a blob binary. Only up to limit bytes
    are kept; the rest is read only to tell binary from text.
    """
    decoder = codecs.getincrementaldecoder("utf-8")()
    parts, kept, binary = [], 0, False
    for chunk in chunks:
        if binary:
            continue
        try:
            if b"\0" in chunk:
                raise UnicodeDecodeError("utf-8", chunk, 0, 1, "NUL byte")
            part = decoder.decode(chunk)
        except UnicodeDecodeError:
            binary, parts = True, []
            continue
        kept += len(chunk)
        if kept <= limit:
            parts.append(part)
        else:
            parts = []
    if binary:
        return None
    try:
        parts.append(decoder.decode(b"", final=True))
    except UnicodeDecodeError:
        return None
    return "".join(parts) if kept <= limit else False


def text(max_bytes, out, paths):
    prefixes = [normalize(p) for p in paths]
    files = [(path, oid) for path, oid in head_files() if under(path, prefixes)]
    if not files:
        fail(3, "no tracked file at HEAD under: " + " ".join(paths))
    too_big = "selected code is over %d bytes, the limit; split the paths into smaller chunks" % max_bytes
    blobs, chunks, size = Blobs(), [], 0
    for path, oid in files:
        body = read_text(blobs.chunks(oid), max_bytes - size)
        if body is None:
            continue
        if body is False:
            fail(4, too_big)
        lines = [line.rstrip("\r") for line in body.split("\n")]
        if lines[-1] == "":
            lines.pop()
        noun = "line" if len(lines) == 1 else "lines"
        chunk = "=== FILE %s (%d %s) ===\n" % (path, len(lines), noun)
        chunk += "".join("%6d\t%s\n" % (n, line) for n, line in enumerate(lines, 1))
        chunk = chunk.encode("utf-8", "surrogateescape")
        size += len(chunk) + 1
        if size > max_bytes:
            fail(4, too_big)
        chunks.append(chunk)
    if not chunks:
        fail(3, "no tracked text file at HEAD under: %s (only binary files)" % " ".join(paths))
    with open(out, "wb") as handle:
        handle.write(b"\n".join(chunks))


def export(target, exclude):
    pattern = re.compile(exclude)
    blobs = Blobs()
    for path, oid in head_files():
        if pattern.search(path):
            continue
        dest = os.path.join(target, path)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "wb") as handle:
            for chunk in blobs.chunks(oid):
                handle.write(chunk)


if sys.argv[1] == "text":
    text(int(sys.argv[2]), sys.argv[3], sys.argv[4:])
elif sys.argv[1] == "export":
    export(sys.argv[2], sys.argv[3])
else:
    fail(2, "unknown audit_source mode")
PYTHON
  ((code == 0)) || die "$((code == 3 || code == 4 ? code : 2))" "could not select the code to audit"
}

# Prints the verdict from a model answer, or exits 6 if it breaks the contract.
check_verdict() {
  local answer=$1 text first body
  text=$(sed 's/[[:space:]]*$//' "$answer" | grep -v '^$' || true)
  first=${text%%$'\n'*}
  body=${text#"$first"}
  body=${body#$'\n'}
  if [[ $first == "REVIEWED $TOKEN" ]]; then
    if [[ $body == OK ]]; then
      printf 'OK\n'
      return
    fi
    if [[ -n $body ]] && ! grep -qvE '^- .+:[0-9]+(-[0-9]+)? — .+$' <<<"$body"; then
      printf '%s\n' "$body"
      return
    fi
  fi
  printf '%s\n' "$text" | head -n 20 >&2
  die 6 "model answer breaks the output contract, no verdict"
}

# Runs a command under a time limit and exits 5 if it fails.
run_tool() {
  local limit=$1 log=$2 code=0
  shift 2
  timeout --kill-after=15s "$limit" "$@" >>"$log" 2>&1 || code=$?
  if ((code == 124 || code == 137)); then
    die 5 "$1 timed out after ${limit}s"
  elif ((code != 0)); then
    tail -n 20 "$log" >&2
    die 5 "$1 exited with code $code"
  fi
}
