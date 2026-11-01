# Shared by run-review.sh and run-security.sh. Source it, do not run it.
#
# Exit codes of both scripts:
#   0  the model reviewed the change set; stdout is `OK` or `- file:line — problem` lines
#   2  usage error, or the directory or ref is unusable
#   3  nothing to review (an empty change set is not a pass)
#   4  change set too large to send whole
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
# Callers run this under `||`, which turns `set -e` off here, so every step
# checks its own status: a partial patch must never reach the model.
uncommitted_patch() {
  local base=HEAD file code
  git rev-parse --verify --quiet HEAD >/dev/null || base=$(git hash-object -t tree /dev/null) || return 1
  git diff --no-color --no-ext-diff --no-textconv "-U$1" "$base" -- || return 1
  git ls-files --others --exclude-standard -z | while IFS= read -r -d '' file; do
    code=0
    git diff --no-index --no-color --no-ext-diff --no-textconv "-U$1" -- /dev/null "$file" || code=$?
    ((code <= 1)) || exit 1
  done
}

# Wraps a patch in markers and sets TOKEN. The token appears only after the
# patch, so a model that echoes it has proved the whole patch reached it.
frame_payload() {
  local patch=$1 payload=$2 max_bytes=$3 size
  [[ -s $patch ]] || die 3 "nothing to review in $PWD"
  size=$(wc -c <"$patch")
  ((size <= max_bytes)) || die 4 "change set is $size bytes, limit is $max_bytes; split the task"
  TOKEN=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
  {
    printf '<<<BEGIN UNTRUSTED CHANGE SET>>>\n'
    cat "$patch"
    printf '\n<<<END UNTRUSTED CHANGE SET token=%s>>>\n' "$TOKEN"
  } >"$payload"
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
