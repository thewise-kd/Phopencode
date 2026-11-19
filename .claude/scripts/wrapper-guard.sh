#!/usr/bin/env bash
# PreToolUse hook for the reviewer and security agents. Reads the tool call on
# stdin and allows only the named script with plain arguments, so an agent that
# holds Bash cannot write files or run anything else.
#
# The script may be named by its relative path, `.claude/scripts/SCRIPT_NAME`,
# or by the absolute path of that same file in this project: the directory this
# guard lives in, joined with SCRIPT_NAME, as one exact string. Relays sometimes
# write the absolute path even when told the relative one, and both run the same
# file, so accepting it adds no reach. Any other path, another script, `./`,
# `..` or `~` forms, quotes and shell syntax stay blocked.
#
# Usage: wrapper-guard.sh SCRIPT_NAME
set -euo pipefail

SCRIPTS_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SCRIPT_NAME=${1:?script name required} SCRIPTS_DIR=$SCRIPTS_DIR python3 -I -c '
import json, os, re, sys

command = json.load(sys.stdin).get("tool_input", {}).get("command", "")
name = os.environ["SCRIPT_NAME"]
paths = (".claude/scripts/" + name, os.environ["SCRIPTS_DIR"] + "/" + name)
path = "(?:" + "|".join(re.escape(p) for p in paths) + ")"
if not re.fullmatch(path + r"( [\w./~^:=@+-]+)*", command.strip()):
    print("Blocked: this agent may only run .claude/scripts/" + name, file=sys.stderr)
    sys.exit(2)
'
