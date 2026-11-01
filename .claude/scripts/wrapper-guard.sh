#!/usr/bin/env bash
# PreToolUse hook for the reviewer and security agents. Reads the tool call on
# stdin and allows only the named script with plain arguments, so an agent that
# holds Bash cannot write files or run anything else.
#
# Usage: wrapper-guard.sh SCRIPT_NAME
set -euo pipefail

SCRIPT_NAME=${1:?script name required} python3 -I -c '
import json, os, re, sys

command = json.load(sys.stdin).get("tool_input", {}).get("command", "")
script = re.escape(os.environ["SCRIPT_NAME"])
if not re.fullmatch(r"\.claude/scripts/" + script + r"( [\w./~^:=@+-]+)*", command.strip()):
    print("Blocked: this agent may only run .claude/scripts/" + os.environ["SCRIPT_NAME"], file=sys.stderr)
    sys.exit(2)
'
