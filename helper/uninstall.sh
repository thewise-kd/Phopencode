#!/bin/sh
# Removes the phopencode notification service (notifyd) installed by install.sh.
#
# Usage: sh uninstall.sh
#
# Steps, in this order:
# 1. Removes the config file ($XDG_CONFIG_HOME/phopencode/notifyd.json when XDG_CONFIG_HOME is
#    absolute, else ~/.config/phopencode/notifyd.json). notifyd notices that and exits by
#    itself (exit 0) within about 30 seconds. Its contents are never read or printed.
# 2. Waits up to PHOPENCODE_UNINSTALL_WAIT seconds (default 60), polling read-only with pgrep
#    (and the unit's ActiveState for a systemd install; any state in which a process may
#    exist or come back, `activating auto-restart` included, counts as running), for notifyd
#    to exit.
# 3. systemd: `systemctl --user disable` (the unit only; the process is left alone), removes
#    the unit file and its default.target.wants link, `daemon-reload`.
#    cron: removes only install.sh's line, `@reboot /bin/sh -c '<command>' phopencode-notifyd`;
#    every other line is kept as it is. A crontab left empty is removed.
# 4. Removes notifyd.pyz, notifyd.log and installed-dirs, then the directories install.sh
#    recorded as created, and the phopencode data and config directories, each only if empty.
#
# Lock: before step 1 it takes the same lock as install.sh, `flock -n` on a read-only
# descriptor of the HOME directory, and holds it to the exit; exit 12, with nothing changed,
# when another run holds it, and exit 15 when the lock fails for any other reason. HOME is never created, removed or recreated by these scripts, so
# there is no lock file and no path race; the kernel releases the lock when the holder exits.
#
# Unknown is never taken as "stopped": if pgrep is missing or fails, or a unit exists whose
# state systemctl cannot report, the files are still removed but the result is
# `uninstalled unknown` (exit 5). A crontab that cannot be read or written, or no crontab
# command at all (so a leftover @reboot line cannot be ruled out), gives exit 4.
#
# Locale: the script sets LC_ALL=C, so crontab's "no crontab" message is not translated.
#
# It never stops, restarts or signals a process. If notifyd still runs after the wait, the
# files are removed anyway, a message says so and the exit code is 3; notifyd still exits by
# itself because its config file is gone.
#
# Running it when nothing is installed is fine: it exits 0 (4 when crontab is missing, as above).
#
# Output: one line on stdout, `uninstalled`, `uninstalled still-running` or
# `uninstalled unknown`. Nothing on stdout for exits 1, 2, 8, 12, 13, 15. Messages go to stderr.
#
# Exit codes:
#   0  removed, or nothing was installed (`uninstalled`)
#   1  unexpected failure
#   2  usage error
#   3  files removed, but notifyd was still running after the wait (`uninstalled still-running`)
#   4  files removed, but the crontab could not be read or written, or the crontab command is
#      missing, so an @reboot line may remain (`uninstalled`)
#   5  files removed, but whether notifyd still runs cannot be told (`uninstalled unknown`)
#   8  HOME or an XDG dir is not absolute or has unsupported characters, or HOME is not a
#      directory
#  12  another install.sh or uninstall.sh is running; nothing was changed
#  13  flock not found (util-linux; needed for the lock); nothing was changed
#  15  the lock cannot be taken for a reason other than another run holding it (for example
#      a filesystem, such as some NFS homes, that cannot lock a read-only directory
#      descriptor); nothing was changed
# When several apply, the first of 3, 5, 4 wins.
#
# POSIX sh (runs under dash).
set -eu
umask 077
LC_ALL=C
export LC_ALL

PROG=uninstall.sh
UNIT_NAME=phopencode-notifyd.service
MARKER=phopencode-notifyd
MARKER_LINE="^@reboot /bin/sh -c '[^']*' $MARKER\$"

WORK=
cleanup() {
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

case $# in
    0) ;;
    *)
        case $1 in
            -h|--help) printf 'usage: sh uninstall.sh\n'; exit 0 ;;
        esac
        printf 'usage: sh uninstall.sh\n' >&2
        exit 2
        ;;
esac

WAIT=${PHOPENCODE_UNINSTALL_WAIT:-60}
case $WAIT in
    ''|*[!0-9]*) fail 2 "PHOPENCODE_UNINSTALL_WAIT must be a number of seconds" ;;
esac

# Paths, the same rules as install.sh and notifyd's config.default_path().
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

CONFIG_DIR=$CONFIG_BASE/phopencode
CONFIG=$CONFIG_DIR/notifyd.json
DATA=$DATA_BASE/phopencode
PYZ=$DATA/notifyd.pyz
LOG=$DATA/notifyd.log
DIRS_FILE=$DATA/installed-dirs
UNIT_DIR=$CONFIG_BASE/systemd/user
UNIT=$UNIT_DIR/$UNIT_NAME
WANTS_LINK=$UNIT_DIR/default.target.wants/$UNIT_NAME

# The lock on HOME, held to the exit.
command -v flock >/dev/null 2>&1 || fail 13 "flock not found (util-linux); it is needed for the lock"
[ -d "$HOME_DIR" ] || fail 8 "HOME $HOME_DIR is not a directory"
exec 9<"$HOME_DIR"
# -E 200: flock's exit code for contention only; any other failure is not "busy".
rc=0
flock -n -E 200 9 || rc=$?
case $rc in
    0) ;;
    200) fail 12 "another install.sh or uninstall.sh is running; nothing was changed" ;;
    *) fail 15 "cannot lock HOME $HOME_DIR (flock exit $rc; the filesystem may not support" \
        "locking it); nothing was changed" ;;
esac

# 1. The config file goes first: that is what makes notifyd exit.
rm -f "$CONFIG"

USER_ID=$(id -u)
if [ -z "${XDG_RUNTIME_DIR:-}" ] && [ -d "/run/user/$USER_ID" ]; then
    XDG_RUNTIME_DIR=/run/user/$USER_ID
    export XDG_RUNTIME_DIR
fi

PYZ_PATTERN=$(printf '%s\n' "$PYZ" | sed 's/[.+]/\\&/g')
PATTERN="^[^ ]*python[^ ]* +$PYZ_PATTERN( |\$)"
HAVE_PGREP=
if command -v pgrep >/dev/null 2>&1; then
    HAVE_PGREP=yes
fi

HAVE_UNIT=
SYSTEMD=
if [ -e "$UNIT" ] || [ -L "$WANTS_LINK" ]; then
    HAVE_UNIT=yes
    if command -v systemctl >/dev/null 2>&1 \
            && systemctl --user show-environment >/dev/null 2>&1; then
        SYSTEMD=yes
    fi
fi

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
# only when every check that applies answered "not running".
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
    if [ -n "$HAVE_UNIT" ]; then
        if [ -n "$SYSTEMD" ]; then
            # Cautious side: any state in which a process may exist or come back counts as
            # running, including `activating auto-restart` (between a crash and its restart).
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

# 2. Wait for notifyd to exit by itself.
waited=0
STATE=$(probe)
while [ "$STATE" = running ] && [ "$waited" -lt "$WAIT" ]; do
    sleep 1
    waited=$((waited + 1))
    STATE=$(probe)
done

# 3. The service manager entries.
if [ -n "$HAVE_UNIT" ]; then
    if [ -n "$SYSTEMD" ]; then
        systemctl --user disable "$UNIT_NAME" >/dev/null 2>&1 \
            || say "warning: systemctl --user disable $UNIT_NAME failed; its files are removed"
    fi
    rm -f "$WANTS_LINK" "$UNIT"
    if [ -n "$SYSTEMD" ]; then
        systemctl --user daemon-reload >/dev/null 2>&1 \
            || say "warning: systemctl --user daemon-reload failed"
    fi
fi

CRON_FAILED=
cron_failed() {
    CRON_FAILED=yes
    say "warning: $*; remove the crontab line ending with ' $MARKER' by hand"
}
if command -v crontab >/dev/null 2>&1; then
    WORK=$(mktemp -d "${TMPDIR:-/tmp}/phopencode-uninstall.XXXXXX")
    rc=0
    crontab -l >"$WORK/current" 2>"$WORK/crontab.err" || rc=$?
    if [ "$rc" -ne 0 ]; then
        if grep -qi 'no crontab' "$WORK/crontab.err"; then
            : >"$WORK/current"
        else
            cron_failed "cannot read the crontab"
        fi
    fi
    if [ -z "$CRON_FAILED" ]; then
        rc=0
        grep -q "$MARKER_LINE" "$WORK/current" || rc=$?
        case $rc in
            0)
                rc=0
                grep -v "$MARKER_LINE" "$WORK/current" >"$WORK/new" || rc=$?
                if [ "$rc" -gt 1 ]; then
                    cron_failed "cannot filter the crontab"
                elif [ -s "$WORK/new" ]; then
                    crontab "$WORK/new" || cron_failed "cannot write the crontab"
                else
                    crontab -r || cron_failed "cannot remove the crontab"
                fi
                ;;
            1) ;;
            *) cron_failed "cannot search the crontab" ;;
        esac
    fi
else
    # An earlier install may have used cron while the command existed; that cannot be ruled
    # out without it, so this is never reported as a clean removal.
    cron_failed "the crontab command is not available, so whether an @reboot line remains" \
        "cannot be checked"
fi

# 4. Files and the directories install.sh created, each only if empty.
CREATED=
if [ -f "$DIRS_FILE" ]; then
    CREATED=$(sed -n '1!G;h;$p' "$DIRS_FILE")
fi
rm -f "$PYZ" "$LOG" "$DIRS_FILE" "$DATA"/.notifyd.pyz.*
for dir in "$DATA" "$CONFIG_DIR" $CREATED; do
    safe_path "$dir" || continue
    case $dir in
        "$HOME_DIR"/*|"$CONFIG_BASE"|"$CONFIG_BASE"/*|"$DATA_BASE"|"$DATA_BASE"/*) ;;
        *) continue ;;
    esac
    if [ -d "$dir" ] && [ ! -L "$dir" ]; then
        rmdir "$dir" 2>/dev/null || true
    fi
done

case $STATE in
    running)
        say "notifyd is still running after ${WAIT}s; its files are removed and it exits by" \
            "itself once it sees that its config file is gone"
        printf 'uninstalled still-running\n'
        exit 3
        ;;
    stopped) ;;
    *)
        say "cannot tell whether notifyd still runs (pgrep or systemctl could not answer);" \
            "its files are removed and it exits by itself once it sees that its config file" \
            "is gone"
        printf 'uninstalled unknown\n'
        exit 5
        ;;
esac
printf 'uninstalled\n'
if [ -n "$CRON_FAILED" ]; then
    exit 4
fi
exit 0
