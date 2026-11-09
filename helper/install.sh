#!/bin/sh
# Installs the phopencode notification service (notifyd) for the current user.
#
# Usage:
#   sh install.sh PATH/TO/notifyd.pyz   install from an uploaded notifyd.pyz and start it
#   sh install.sh --status              print one status line; changes nothing
#
# Before running it, the caller writes the config file, mode 0600, at notifyd's default path:
# $XDG_CONFIG_HOME/phopencode/notifyd.json when XDG_CONFIG_HOME is absolute, else
# ~/.config/phopencode/notifyd.json. This script never reads, prints or passes on its contents
# (it holds the server password and the ntfy topic); it only checks its type, owner and mode.
#
# What it does, all inside the user's HOME (XDG dirs), without sudo or root:
# - checks python3 (3.9 or newer), the config file and that notifyd is not already running;
# - copies the archive to ${XDG_DATA_HOME:-~/.local/share}/phopencode/notifyd.pyz (directory
#   0700, file 0600, written to a temporary name and renamed);
# - picks the service manager: `systemctl --user` when the user manager answers AND the user
#   lingers (`loginctl show-user UID -p Linger` says yes), so the service survives logout;
#   otherwise cron. With systemd it writes
#   ${XDG_CONFIG_HOME:-~/.config}/systemd/user/phopencode-notifyd.service (no secrets in it;
#   Restart=on-failure, so the exit 0 that follows removal of the config file is final), then
#   runs daemon-reload, enable and start. With cron it adds one line
#     @reboot /bin/sh -c '<command>' phopencode-notifyd
#   (the command runs under /bin/sh whatever the crontab's SHELL is, e.g. SHELL=/bin/csh; the
#   trailing word is sh's $0 and marks the line, so no "#" comment is needed). Any older
#   line of that shape is replaced; every other line is kept as it is. It then starts notifyd
#   now, detached (setsid when present, nohup), with its output appended to notifyd.log (0600)
#   next to the archive. A leftover of the other manager from an earlier install is removed.
# - records the directories it created in phopencode/installed-dirs, so that uninstall.sh can
#   remove exactly those, if empty.
#
# Lock: install.sh and uninstall.sh exclude each other with `flock -n` on a read-only
# descriptor of the HOME directory itself, held from the "already running" check to the exit
# (exit 12 when another run holds it; exit 15 when the lock fails for any other reason, so a
# filesystem that cannot lock is never reported as "busy"). HOME always exists and neither script ever creates,
# removes or recreates it, so there is no lock file: nothing is left behind, and no run can
# delete or replace the object another run has locked. The kernel releases the lock when the
# holder exits, even after a crash, so a stale lock cannot exist. The lock descriptor is closed
# for the started notifyd, so it never holds the lock.
#
# Unknown is never taken as safe: a check that fails or cannot run (pgrep error, an unreadable
# crontab, a unit whose state systemctl cannot report) makes the script refuse before it changes
# anything, never assume "not running" or "no line there".
#
# Locale: the script sets LC_ALL=C, so crontab's "no crontab" message (and grep/sed) are not
# translated.
#
# It never stops, restarts or signals a process. If notifyd is already running it refuses
# (exit 7): run uninstall.sh first, which removes the config file and waits for notifyd to
# exit by itself. It never enables lingering.
#
# Paths (HOME, the XDG dirs, python3) must be absolute and made only of A-Z a-z 0-9 . _ / + @ -
# so that they are safe in the unit file, the crontab line and the pgrep pattern.
#
# Output: on success one line on stdout, `installed systemd` or `installed cron`. With
# --status: `status <systemd|cron|none|unknown> <running|stopped|unknown>` (manager unknown:
# the crontab cannot be read; state unknown: pgrep or systemctl could not tell). Messages go to
# stderr.
#
# Exit codes:
#   0  installed (or status printed)
#   1  unexpected failure
#   2  usage error, or the archive is not a readable, non-empty regular file
#   3  python3 not found or does not run
#   4  python3 older than 3.9
#   5  config file missing
#   6  config file is not a regular file owned by the user with mode 0600
#   7  notifyd is already running; uninstall first
#   8  HOME, an XDG dir or the python3 path is not absolute or has unsupported characters
#   9  no usable service manager (no systemd --user with linger, no crontab), the crontab
#      cannot be read (nothing changed), or a service manager step failed
#  10  notifyd was started but is not running a second later (see the log or the journal)
#  11  pgrep not found (needed to tell whether notifyd runs)
#  12  another install.sh or uninstall.sh is running; try again later
#  13  flock not found (util-linux; needed for the lock)
#  14  cannot tell whether notifyd runs (pgrep failed, or a unit exists whose state systemctl
#      cannot report); nothing changed, or, after the start, the start is unconfirmed
#  15  the lock cannot be taken for a reason other than another run holding it (for example
#      a filesystem, such as some NFS homes, that cannot lock a read-only directory
#      descriptor, or a flock without -E); nothing changed
#
# POSIX sh (runs under dash).
set -eu
umask 077
LC_ALL=C
export LC_ALL

PROG=install.sh
UNIT_NAME=phopencode-notifyd.service
MARKER=phopencode-notifyd
# Lines written by this script: @reboot /bin/sh -c '<command>' phopencode-notifyd
MARKER_LINE="^@reboot /bin/sh -c '[^']*' $MARKER\$"
START_CHECK_SECONDS=1

WORK=
TMP_PYZ=
TMP_UNIT=
cleanup() {
    if [ -n "$TMP_PYZ" ]; then rm -f "$TMP_PYZ"; fi
    if [ -n "$TMP_UNIT" ]; then rm -f "$TMP_UNIT"; fi
    if [ -n "$WORK" ]; then rm -rf "$WORK"; fi
}
trap cleanup EXIT

say() {
    printf '%s: %s\n' "$PROG" "$*" >&2
}

fail() {
    code=$1
    shift
    say "error: $*"
    exit "$code"
}

usage() {
    printf 'usage: sh install.sh PATH/TO/notifyd.pyz | --status\n'
}

safe_path() {
    case $1 in
        /*) ;;
        *) return 1 ;;
    esac
    case $1 in
        *[!A-Za-z0-9._/+@-]*) return 1 ;;
    esac
    return 0
}

# Arguments.
if [ $# -ne 1 ]; then
    usage >&2
    exit 2
fi
MODE=install
SOURCE=
case $1 in
    --status) MODE=status ;;
    -h|--help) usage; exit 0 ;;
    -*) usage >&2; exit 2 ;;
    *) SOURCE=$1 ;;
esac
if [ "$MODE" = install ]; then
    if [ ! -f "$SOURCE" ] || [ ! -r "$SOURCE" ] || [ ! -s "$SOURCE" ]; then
        fail 2 "$SOURCE is not a readable, non-empty file"
    fi
fi

# Paths, the same rules as notifyd's config.default_path().
HOME_DIR=${HOME:-}
safe_path "$HOME_DIR" || fail 8 "HOME must be an absolute path made of A-Z a-z 0-9 . _ / + @ -"
CONFIG_BASE=${XDG_CONFIG_HOME:-}
case $CONFIG_BASE in
    /*) ;;
    *) CONFIG_BASE=$HOME_DIR/.config ;;
esac
DATA_BASE=${XDG_DATA_HOME:-}
case $DATA_BASE in
    /*) ;;
    *) DATA_BASE=$HOME_DIR/.local/share ;;
esac
safe_path "$CONFIG_BASE" || fail 8 "XDG_CONFIG_HOME has unsupported characters"
safe_path "$DATA_BASE" || fail 8 "XDG_DATA_HOME has unsupported characters"

CONFIG=$CONFIG_BASE/phopencode/notifyd.json
DATA=$DATA_BASE/phopencode
PYZ=$DATA/notifyd.pyz
LOG=$DATA/notifyd.log
DIRS_FILE=$DATA/installed-dirs
UNIT_DIR=$CONFIG_BASE/systemd/user
UNIT=$UNIT_DIR/$UNIT_NAME
WANTS_DIR=$UNIT_DIR/default.target.wants
WANTS_LINK=$WANTS_DIR/$UNIT_NAME

USER_ID=$(id -u)
if [ -z "${XDG_RUNTIME_DIR:-}" ] && [ -d "/run/user/$USER_ID" ]; then
    XDG_RUNTIME_DIR=/run/user/$USER_ID
    export XDG_RUNTIME_DIR
fi

# notifyd as started by this script: python3 (absolute path), the archive, then options.
PYZ_PATTERN=$(printf '%s\n' "$PYZ" | sed 's/[.+]/\\&/g')
PATTERN="^[^ ]*python[^ ]* +$PYZ_PATTERN( |\$)"
HAVE_PGREP=
if command -v pgrep >/dev/null 2>&1; then
    HAVE_PGREP=yes
fi

systemd_usable() {
    command -v systemctl >/dev/null 2>&1 || return 1
    systemctl --user show-environment >/dev/null 2>&1
}

lingering() {
    command -v loginctl >/dev/null 2>&1 || return 1
    linger=$(loginctl show-user "$USER_ID" -p Linger 2>/dev/null) || return 1
    [ "$linger" = "Linger=yes" ]
}

# Prints "<ActiveState> <SubState>" of the unit, or nothing when systemctl cannot tell.
# Read-only.
unit_state() {
    if ! out=$(systemctl --user show -p ActiveState -p SubState "$UNIT_NAME" 2>/dev/null); then
        return 0
    fi
    a=$(printf '%s\n' "$out" | sed -n 's/^ActiveState=//p')
    s=$(printf '%s\n' "$out" | sed -n 's/^SubState=//p')
    case $a$s in
        *[!a-z-]*) return 0 ;;
    esac
    if [ -n "$a" ] && [ -n "$s" ]; then
        printf '%s %s\n' "$a" "$s"
    fi
}

# Prints running, stopped or unknown. Read-only. Running when any check sees notifyd; stopped
# only when every check that applies answered "not running". Used before installing and by
# --status; the check after the start (confirm_start) is stricter for systemd.
probe() {
    pstate=unknown
    if [ -n "$HAVE_PGREP" ]; then
        rc=0
        pgrep -u "$USER_ID" -f "$PATTERN" >/dev/null 2>&1 || rc=$?
        case $rc in
            0) echo running; return 0 ;;
            1) pstate=stopped ;;
        esac
    fi
    if [ -e "$UNIT" ] || [ -L "$WANTS_LINK" ]; then
        if systemd_usable; then
            # Cautious side: any state in which a process may exist or come back (activating
            # includes SubState auto-restart, a crashed notifyd waiting for its restart).
            case $(unit_state) in
                active\ *|activating\ *|deactivating\ *|reloading\ *|refreshing\ *)
                    echo running; return 0 ;;
                inactive\ *|failed\ *) ;;
                *) pstate=unknown ;;
            esac
        else
            pstate=unknown
        fi
    fi
    echo "$pstate"
}

# 0 when file $1 holds this script's crontab line, 1 when not; a grep error is fatal.
has_cron_line() {
    rc=0
    grep -q "$MARKER_LINE" "$1" || rc=$?
    case $rc in
        0) return 0 ;;
        1) return 1 ;;
    esac
    fail 1 "cannot search the crontab copy"
}

# Copies file $1 to $2 without this script's crontab line; a grep error is fatal.
without_cron_line() {
    rc=0
    grep -v "$MARKER_LINE" "$1" >"$2" || rc=$?
    if [ "$rc" -gt 1 ]; then
        fail 1 "cannot filter the crontab copy"
    fi
}

# Prints the crontab into file $1 (empty when the user has none). 1 when it cannot be read.
cron_read() {
    rc=0
    crontab -l >"$1" 2>"$WORK/crontab.err" || rc=$?
    if [ "$rc" -ne 0 ]; then
        if grep -qi 'no crontab' "$WORK/crontab.err"; then
            : >"$1"
            return 0
        fi
        return 1
    fi
    return 0
}

make_work() {
    if [ -z "$WORK" ]; then
        WORK=$(mktemp -d "${TMPDIR:-/tmp}/phopencode-install.XXXXXX")
    fi
}

# Removes a unit file left by an earlier systemd install. The service is not active here.
remove_unit() {
    if [ ! -e "$UNIT" ] && [ ! -L "$WANTS_LINK" ]; then
        return 0
    fi
    if systemd_usable; then
        systemctl --user disable "$UNIT_NAME" >/dev/null 2>&1 || true
    fi
    rm -f "$WANTS_LINK" "$UNIT"
    if systemd_usable; then
        systemctl --user daemon-reload >/dev/null 2>&1 || true
    fi
}

# Status mode: read-only.
if [ "$MODE" = status ]; then
    manager=none
    if [ -f "$UNIT" ]; then
        manager=systemd
    elif command -v crontab >/dev/null 2>&1; then
        make_work
        if ! cron_read "$WORK/current"; then
            manager=unknown
        elif has_cron_line "$WORK/current"; then
            manager=cron
        fi
    fi
    printf 'status %s %s\n' "$manager" "$(probe)"
    exit 0
fi

# python3, 3.9 or newer.
PY=$(command -v python3 2>/dev/null) || PY=
case $PY in
    /*) ;;
    *) fail 3 "python3 not found; install Python 3.9 or newer" ;;
esac
safe_path "$PY" || fail 8 "the python3 path has unsupported characters"
VERSION=$("$PY" -I -c 'import sys; print("%d %d" % sys.version_info[:2])' 2>/dev/null) \
    || fail 3 "python3 does not run"
MAJOR=${VERSION% *}
MINOR=${VERSION#* }
case $MAJOR$MINOR in
    ''|*[!0-9]*) fail 3 "cannot tell the python3 version" ;;
esac
if [ "$MAJOR" -lt 3 ] || { [ "$MAJOR" -eq 3 ] && [ "$MINOR" -lt 9 ]; }; then
    fail 4 "python3 is $MAJOR.$MINOR; notifyd needs 3.9 or newer"
fi

# The config file: written beforehand by the app, never read here.
if [ -L "$CONFIG" ]; then
    fail 6 "config file $CONFIG is a symlink; it must be a regular file with mode 0600"
fi
if [ ! -e "$CONFIG" ]; then
    fail 5 "config file $CONFIG not found; write it (mode 0600) before installing"
fi
if [ ! -f "$CONFIG" ]; then
    fail 6 "config file $CONFIG is not a regular file; it must have mode 0600"
fi
# Only the mode and owner fields of the listing are used.
CONFIG_STAT=$(ls -ln "$CONFIG" | { read -r perms links owner rest; printf '%s %s' "$perms" "$owner"; })
case ${CONFIG_STAT% *} in
    -rw-------|-rw-------.) ;;
    *) fail 6 "config file $CONFIG must have mode 0600" ;;
esac
if [ "${CONFIG_STAT#* }" != "$USER_ID" ]; then
    fail 6 "config file $CONFIG is not owned by this user; it must be the user's, mode 0600"
fi

if [ -z "$HAVE_PGREP" ]; then
    fail 11 "pgrep not found (procps); it is needed to tell whether notifyd runs"
fi

# The lock on HOME, held from here to the exit. fd 9 is the lock; children that stay must
# close it.
command -v flock >/dev/null 2>&1 || fail 13 "flock not found (util-linux); it is needed for the lock"
[ -d "$HOME_DIR" ] || fail 8 "HOME $HOME_DIR is not a directory"
exec 9<"$HOME_DIR"
# -E 200: flock's exit code for contention only; any other failure (a filesystem that cannot
# lock a read-only directory descriptor, such as some NFS homes, or a flock without -E) is not
# "busy".
rc=0
flock -n -E 200 9 || rc=$?
case $rc in
    0) ;;
    200) fail 12 "another install.sh or uninstall.sh is running; try again later" ;;
    *) fail 15 "cannot lock HOME $HOME_DIR (flock exit $rc; the filesystem may not support" \
        "locking it); nothing was changed" ;;
esac

case $(probe) in
    running) fail 7 "notifyd is already running; run uninstall.sh first" ;;
    stopped) ;;
    *) fail 14 "cannot tell whether notifyd runs; nothing was changed" ;;
esac

# The crontab, read before anything is written: it may hold a line from an earlier install,
# which must go when systemd takes over. One that cannot be read stops the install.
HAVE_CRONTAB=
if command -v crontab >/dev/null 2>&1; then
    HAVE_CRONTAB=yes
    make_work
    if ! cron_read "$WORK/current"; then
        fail 9 "cannot read the crontab; nothing was changed"
    fi
fi

# Service manager choice, before anything is written.
MANAGER=
if systemd_usable && lingering; then
    MANAGER=systemd
elif [ -n "$HAVE_CRONTAB" ]; then
    MANAGER=cron
else
    fail 9 "no service manager: systemd --user is unusable or the user does not linger," \
        "and crontab is not installed"
fi

# Directories, recorded when this script creates them.
CREATED=
ensure_dir() {
    if [ -d "$1" ]; then
        return 0
    fi
    ensure_dir "$(dirname "$1")"
    mkdir -m 700 "$1"
    CREATED="$CREATED $1"
}

record_dirs() {
    for dir in $CREATED; do
        if [ ! -f "$DIRS_FILE" ] || ! grep -qxF "$dir" "$DIRS_FILE"; then
            printf '%s\n' "$dir" >>"$DIRS_FILE"
        fi
    done
    CREATED=
}

ensure_dir "$DATA"
chmod 700 "$DATA"
record_dirs

# The archive, renamed into place.
TMP_PYZ=$DATA/.notifyd.pyz.$$
cp "$SOURCE" "$TMP_PYZ"
chmod 600 "$TMP_PYZ"
mv -f "$TMP_PYZ" "$PYZ"
TMP_PYZ=

# Checks, a moment after the start, that notifyd still runs. $1: where to look when it does
# not; $2: the manager. Strict side, unlike probe(): for systemd only ActiveState=active with
# SubState=running confirms the start. `activating auto-restart` (and inactive/failed) means it
# already exited; any other or unreadable answer is unknown.
confirm_start() {
    sleep "$START_CHECK_SECONDS"
    if [ "$2" = systemd ]; then
        case $(unit_state) in
            "active running") return 0 ;;
            "activating auto-restart"|inactive\ *|failed\ *)
                fail 10 "notifyd exited at once; see $1" ;;
            *) fail 14 "notifyd was started, but whether it runs cannot be told; see $1" ;;
        esac
    fi
    case $(probe) in
        running) ;;
        stopped) fail 10 "notifyd exited at once; see $1" ;;
        *) fail 14 "notifyd was started, but whether it runs cannot be told; see $1" ;;
    esac
}

if [ "$MANAGER" = systemd ]; then
    # An earlier cron install's line goes, so that a reboot cannot start a second notifyd.
    if [ -n "$HAVE_CRONTAB" ] && has_cron_line "$WORK/current"; then
        without_cron_line "$WORK/current" "$WORK/new"
        if [ -s "$WORK/new" ]; then
            crontab "$WORK/new" || fail 9 "the crontab could not be written"
        else
            crontab -r || fail 9 "the crontab could not be removed"
        fi
    fi
    ensure_dir "$UNIT_DIR"
    TMP_UNIT=$UNIT_DIR/.$UNIT_NAME.$$
    cat >"$TMP_UNIT" <<EOF
# Written by phopencode helper/install.sh; removed by helper/uninstall.sh.
[Unit]
Description=phopencode notification service (notifyd)

[Service]
Type=simple
ExecStart=$PY $PYZ --config $CONFIG
Restart=on-failure
RestartSec=30
RestartPreventExitStatus=2
UMask=0077

[Install]
WantedBy=default.target
EOF
    chmod 600 "$TMP_UNIT"
    mv -f "$TMP_UNIT" "$UNIT"
    TMP_UNIT=
    if [ ! -d "$WANTS_DIR" ]; then
        CREATED="$CREATED $WANTS_DIR"
    fi
    record_dirs
    systemctl --user daemon-reload || fail 9 "systemctl --user daemon-reload failed"
    systemctl --user enable "$UNIT_NAME" >/dev/null 2>&1 \
        || fail 9 "systemctl --user enable $UNIT_NAME failed"
    systemctl --user start "$UNIT_NAME" || fail 9 "systemctl --user start $UNIT_NAME failed"
    confirm_start "journalctl --user -u $UNIT_NAME" systemd
    printf 'installed systemd\n'
    exit 0
fi

# cron
remove_unit
without_cron_line "$WORK/current" "$WORK/new"
printf "@reboot /bin/sh -c 'umask 077; exec %s %s --config %s >>%s 2>&1 </dev/null' %s\n" \
    "$PY" "$PYZ" "$CONFIG" "$LOG" "$MARKER" >>"$WORK/new"
if ! cmp -s "$WORK/current" "$WORK/new"; then
    crontab "$WORK/new" || fail 9 "crontab could not be written"
fi

: >>"$LOG"
chmod 600 "$LOG"
if command -v setsid >/dev/null 2>&1; then
    (cd / && exec setsid nohup "$PY" "$PYZ" --config "$CONFIG" >>"$LOG" 2>&1 </dev/null 9>&-) &
else
    (cd / && exec nohup "$PY" "$PYZ" --config "$CONFIG" >>"$LOG" 2>&1 </dev/null 9>&-) &
fi
confirm_start "$LOG" cron
printf 'installed cron\n'
exit 0
