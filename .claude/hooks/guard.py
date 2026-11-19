#!/usr/bin/env python3
"""PreToolUse guard for Bash, Monitor, Write and Edit.

Blocks, by exiting 2 with the reason on stderr:
- pushing to a remote with git,
- writing a secret file with Write or Edit,
- staging a secret file with git add,
- committing a secret file or a private key.

It reads the tool call Claude is about to make, so commands the user runs in a
terminal or with the `!` prefix never reach it. It stops mistakes, not a
determined program: a script that opens a socket or shells out from another
language is out of its sight.
"""
import json
import os
import re
import subprocess
import sys

KEYWORDS = {"if", "then", "elif", "else", "do", "while", "until", "!", "{", "time"}
ASSIGNMENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]*=")
SHELLS = {"sh", "bash", "dash", "zsh", "ksh"}
WRAPPERS = {
    "builtin", "command", "env", "exec", "find", "ionice", "nice", "nohup",
    "setsid", "stdbuf", "sudo", "timeout", "watch", "xargs",
}
GIT_VALUE_OPTIONS = {
    "-c", "--attr-source", "--config-env", "--exec-path", "--git-dir",
    "--namespace", "--work-tree",
}
PUSH_COMMANDS = {"push", "send-pack", "http-push"}
# Markers around commands that run in a child shell: `( ... )`, `$( ... )`,
# backticks and `sh -c`. A `cd` inside one ends with it.
ENTER = ("enter",)
EXIT = ("exit",)
# Marks a `|` or `&` that runs the commands around it in child shells.
CHILD = ("child",)
# GIT_* variables that do not move the repository, tree, index or config.
HARMLESS_GIT_VARIABLE = re.compile(
    r"GIT_(AUTHOR|COMMITTER)_\w+|GIT_(SEQUENCE_)?EDITOR|GIT_PAGER|GIT_TERMINAL_PROMPT|GIT_OPTIONAL_LOCKS"
)
GIT_VARIABLE = re.compile(r"GIT_\w+")
# A key in a command's environment that is not a variable: how a wrapper
# changes the environment git gets.
WRAPPED = " wrapped"
# Global git options that move the repository, the work tree or the config.
GIT_LOCATION_OPTIONS = {"--git-dir", "--work-tree", "--config-env", "--bare"}
# `-c NAME=value` keys that move the work tree or pull in more config.
LOCATION_CONFIG = re.compile(r"(?i)core\.(worktree|bare)|include(if)?\b")

SECRET_NAME = re.compile(
    r"""(?ix)
    (?!\.env\.(example|sample|template)$)
    ( .*\.env | \.env\..+
    | local\.properties | (key|keystore|secrets?)\.properties
    | .+\.(jks|keystore|bks|p12|pfx|pem|key|ppk)
    | id_(rsa|dsa|ecdsa|ed25519)[\w-]*
    )
    """
)
PRIVATE_KEY = re.compile(rb"-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY(?: BLOCK)?-----")
KEY_BODY = re.compile(rb"[A-Za-z0-9+/=]{40,}")
KEY_BODY_WINDOW = 400
MAX_SCAN_BYTES = 10 * 1024 * 1024


def deny(reason):
    print(f"Blocked by .claude/hooks/guard.py: {reason}", file=sys.stderr)
    sys.exit(2)


class ShellParser:
    """Splits shell text into simple commands, each a list of unquoted words.

    Command substitutions become commands of their own, between ENTER and EXIT.
    Quoted text and here-doc bodies stay data, so a commit message that mentions
    a command is not one; the `$(...)` and backticks in an unquoted here-doc body
    run like any others.
    """

    def __init__(self, source):
        self.source = source
        self.position = 0
        self.commands = []
        self.heredocs = []

    def peek(self, offset=0):
        index = self.position + offset
        return self.source[index] if index < len(self.source) else ""

    def parse(self, closer=""):
        words = []
        word = None
        drop_next_word = False

        def end_word():
            nonlocal word, drop_next_word
            if word is not None:
                if not drop_next_word:
                    words.append(word)
                drop_next_word = False
                word = None

        def end_command():
            nonlocal words
            end_word()
            if words:
                self.commands.append(words)
            words = []

        while self.position < len(self.source):
            char = self.peek()
            if closer and char == closer:
                self.position += 1
                break
            if char == "\\":
                if self.peek(1) != "\n":
                    word = (word or "") + self.peek(1)
                self.position += 2
            elif char == "'":
                end = self.source.find("'", self.position + 1)
                end = len(self.source) if end < 0 else end
                word = (word or "") + self.source[self.position + 1 : end]
                self.position = end + 1
            elif char == '"':
                word = (word or "") + self.double_quoted()
            elif char == "`" or (char == "$" and self.peek(1) == "("):
                word = (word or "") + self.substitution()
            elif char == "(":
                end_command()
                self.position += 1
                self.subshell(")")
            elif char == "\n":
                end_command()
                self.position += 1
                self.skip_heredoc_bodies()
            elif char == "&" and self.peek(1) == ">":
                self.position += 1
            elif char in "&|" and self.peek(1) != char:
                end_command()
                self.position += 2 if self.source.startswith("|&", self.position) else 1
                self.commands.append(CHILD)
            elif char in ";&|)":
                end_command()
                self.position += 2 if char in "&|" else 1
            elif char in " \t":
                end_word()
                self.position += 1
            elif char == "#" and word is None:
                while self.position < len(self.source) and self.peek() != "\n":
                    self.position += 1
            elif self.source.startswith("<<", self.position) and self.peek(2) != "<":
                end_word()
                self.position += 3 if self.peek(2) == "-" else 2
                strip_tabs = self.source[self.position - 1] == "-"
                self.heredocs.append((*self.heredoc_delimiter(), strip_tabs))
            elif char in "<>":
                if word is not None and word.isdigit():
                    word = None
                end_word()
                drop_next_word = True
                self.position += 1
                while self.peek() in (">", "&", "<", "|"):
                    self.position += 1
            else:
                word = (word or "") + char
                self.position += 1
        end_command()
        return self.commands

    def subshell(self, closer):
        self.commands.append(ENTER)
        self.parse(closer)
        self.commands.append(EXIT)

    def substitution(self):
        """Parses a `$(...)` or backtick substitution at the position as a child shell."""
        if self.peek() == "`":
            self.position += 1
            self.subshell("`")
        else:
            self.position += 2
            self.subshell(")")
        return "$()"

    def double_quoted(self):
        self.position += 1
        return self.expanded_text('"')

    def expanded_text(self, closer=""):
        """Reads text that only substitutions expand: double quotes or a here-doc body."""
        text = []
        while self.position < len(self.source):
            char = self.peek()
            if closer and char == closer:
                self.position += 1
                break
            if char == "\\":
                text.append(self.peek(1))
                self.position += 2
            elif char == "`" or (char == "$" and self.peek(1) == "("):
                text.append(self.substitution())
            else:
                text.append(char)
                self.position += 1
        return "".join(text)

    def heredoc_delimiter(self):
        """Returns the delimiter word and whether any part of it was quoted."""
        while self.peek() in (" ", "\t"):
            self.position += 1
        delimiter = []
        quoted = False
        while self.peek() and self.peek() not in " \t\n;&|()<>":
            if self.peek() in "'\"\\":
                quoted = True
            else:
                delimiter.append(self.peek())
            self.position += 1
        return "".join(delimiter), quoted

    def skip_heredoc_bodies(self):
        for delimiter, quoted, strip_tabs in self.heredocs:
            body = []
            while self.position < len(self.source):
                end = self.source.find("\n", self.position)
                end = len(self.source) if end < 0 else end
                line = self.source[self.position : end]
                self.position = min(end + 1, len(self.source))
                if (line.lstrip("\t") if strip_tabs else line) == delimiter:
                    break
                body.append(line)
            if not quoted:
                inner = ShellParser("\n".join(body))
                inner.expanded_text()
                self.commands.extend(inner.commands)
        self.heredocs = []


def basename(word):
    return word.rsplit("/", 1)[-1]


def assignments(words):
    """Returns the NAME=value words as a dict."""
    return dict(word.split("=", 1) for word in words if ASSIGNMENT.match(word))


def real_commands(words, environment=None):
    """Yields the commands behind keywords, assignments, wrappers and `sh -c`.

    A command is a (environment, words) pair, where environment holds the
    NAME=value assignments in front of it or its wrapper, and the WRAPPED key.
    Assignments alone, and those in front of `sh -c`, are ("assign",
    environment). ENTER, EXIT and CHILD pass through, and a `sh -c` script
    runs between ENTER and EXIT.
    """
    if not isinstance(words, list):
        yield words
        return
    environment = dict(environment or {})
    start = 0
    while start < len(words) and (words[start] in KEYWORDS or ASSIGNMENT.match(words[start])):
        if ASSIGNMENT.match(words[start]):
            environment.update(assignments(words[start : start + 1]))
        start += 1
    words = words[start:]
    if not words:
        yield ("assign", environment)
        return
    name = basename(words[0])
    if name in SHELLS:
        for index, word in enumerate(words[:-1]):
            if re.fullmatch(r"-[A-Za-z]*c[A-Za-z]*", word):
                yield ("assign", environment)
                yield ENTER
                yield from commands_in(words[index + 1])
                yield EXIT
                return
    elif name == "eval":
        yield from commands_in(" ".join(words[1:]), environment)
    elif name in WRAPPERS:
        for index, word in enumerate(words[1:], 1):
            if basename(word) in SHELLS | {"git", "eval"}:
                options = words[1:index]
                environment.update(assignments(options))
                if name == "sudo":
                    environment[WRAPPED] = "`sudo`, which resets the environment"
                elif name in ("env", "exec") and any(o.startswith("-") for o in options):
                    environment[WRAPPED] = f"`{name}` with options"
                yield from real_commands(words[index:], environment)
                return
    else:
        yield environment, words


def commands_in(source, environment=None):
    for words in ShellParser(source).parse():
        yield from real_commands(words, environment)


def split_git(words):
    """Returns the -C directories, a location override or None, the subcommand and its arguments."""
    directories = []
    override = None
    index = 1
    while index < len(words) and words[index].startswith("-"):
        option = words[index].partition("=")[0]
        if option in GIT_LOCATION_OPTIONS:
            override = f"`{option}`"
        if words[index] == "-C":
            directories.extend(words[index + 1 : index + 2])
            index += 2
        elif words[index] == "-c":
            value = (words[index + 1 : index + 2] or [""])[0]
            if "$" in value or LOCATION_CONFIG.match(value):
                override = f"`-c {value}`"
            index += 2
        elif words[index] in GIT_VALUE_OPTIONS:
            index += 2
        else:
            index += 1
    if index >= len(words):
        return directories, override, None, []
    return directories, override, words[index], words[index + 1 :]


def is_secret_path(path):
    if SECRET_NAME.fullmatch(basename(path)):
        return True
    return ".ssh" in path.split("/") and not path.endswith(".pub")


def run_git(directory, *arguments):
    """Returns git's stdout as bytes, or None when git fails."""
    try:
        result = subprocess.run(
            ["git", *arguments],
            cwd=directory,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            env={**os.environ, "GIT_OPTIONAL_LOCKS": "0"},
        )
    except OSError:
        return None
    return result.stdout if result.returncode == 0 else None


def check_add(directory, arguments):
    """Blocks secrets and returns the paths this git add would stage."""
    named = [argument for argument in arguments if not argument.startswith("-")]
    for path in named:
        if is_secret_path(path):
            deny(f"`git add` names the secret file {path}.")
    output = run_git(directory, "add", "--dry-run", *arguments) or b""
    paths = []
    for line in os.fsdecode(output).splitlines():
        match = re.fullmatch(r"add '(.*)'", line)
        if match:
            paths.append(os.path.join(directory, match.group(1)))
    for path in paths:
        if is_secret_path(path):
            deny(f"`git add` would stage the secret file {path}.")
    return paths


def holds_private_key(data):
    """A key is a header followed by its body; a header alone is a format constant."""
    return any(
        KEY_BODY.search(data, match.end(), match.end() + KEY_BODY_WINDOW)
        for match in PRIVATE_KEY.finditer(data)
    )


def check_commit(directory, pending_paths):
    """Scans what this commit can include: the index, tracked edits, a chained add."""
    toplevel = run_git(directory, "rev-parse", "--show-toplevel")
    if toplevel is None:
        return
    root = os.fsdecode(toplevel).rstrip("\n")
    names = run_git(root, "diff", "--cached", "--name-only", "--diff-filter=d", "-z")
    modified = run_git(root, "ls-files", "--modified", "-z")
    if names is None or modified is None:
        deny("could not read the staged files to scan them.")
    staged = {os.fsdecode(name) for name in names.split(b"\0") if name}
    on_disk = {os.fsdecode(name) for name in modified.split(b"\0") if name}
    on_disk.update(os.path.relpath(path, root) for path in pending_paths)

    for path in sorted(staged | on_disk):
        if is_secret_path(path):
            deny(f"the commit would include the secret file {path}. Unstage it.")
    for path in sorted(staged):
        data = run_git(root, "show", f":{path}") or b""
        if holds_private_key(data[:MAX_SCAN_BYTES]):
            deny(f"{path} holds a private key. Unstage it; generate test keys at runtime.")
    for path in sorted(on_disk):
        try:
            with open(os.path.join(root, path), "rb") as file:
                data = file.read(MAX_SCAN_BYTES)
        except OSError:
            continue
        if holds_private_key(data):
            deny(f"{path} holds a private key. Keep it out of the commit.")


def git_variables(text):
    """Returns the GIT_* names in text that can move the repository or its config."""
    return [name for name in GIT_VARIABLE.findall(text) if not HARMLESS_GIT_VARIABLE.fullmatch(name)]


def location_change(items):
    """Returns how the command line changes git's location or config, or None.

    Only `cd` and `git -C` are followed. Anything else that could move the
    repository, the work tree, the index or the config counts, whether it would
    run or not: GIT_* variables set, exported, unset or named in any command
    but git itself, location options, wrappers that change the environment,
    and `source`.
    """
    for item in items:
        if item in (ENTER, EXIT, CHILD):
            continue
        environment = item[1] if item[0] == "assign" else item[0]
        if WRAPPED in environment:
            return f"runs git under {environment[WRAPPED]}"
        variables = git_variables(" ".join(environment))
        if variables:
            return f"sets {variables[0]}"
        if item[0] == "assign":
            continue
        words = item[1]
        name = basename(words[0])
        named = git_variables(" ".join(words[1:]))
        if name == "git":
            override = split_git(words)[1]
            if override:
                return f"uses {override}"
        elif name in ("source", "."):
            return f"runs `{name}`, which can set any variable"
        elif name == "export" and any(
            word.startswith("-") or "$" in word.split("=", 1)[0] for word in words[1:]
        ):
            return "runs `export` with options or a computed name"
        elif named:
            return f"names {named[0]} in `{name}`"
    return None


def check_git(words, directory, pending_paths):
    directories, _, subcommand, arguments = split_git(words)
    for path in directories:
        directory = os.path.join(directory, os.path.expanduser(path))
    if subcommand is None:
        return
    if subcommand.startswith("$"):
        deny("the git subcommand is computed at runtime, so it cannot be verified. Spell it out.")
    if subcommand in PUSH_COMMANDS:
        deny("git push is never run from Claude. The user pushes from their own terminal.")
    command_text = " ".join(words)
    if "alias." in command_text and "push" in command_text:
        deny("a git alias that pushes is the same as git push. The user pushes.")
    if subcommand == "add":
        pending_paths.extend(check_add(directory, arguments))
    elif subcommand == "commit":
        if "$" in directory:
            deny(f"cannot scan the commit: {directory} is computed at runtime. Spell out the path.")
        check_commit(directory, pending_paths)


def stages(item):
    """Tells whether a parsed item is a git add or git commit."""
    if item in (ENTER, EXIT, CHILD) or item[0] == "assign":
        return False
    words = item[1]
    return basename(words[0]) == "git" and split_git(words)[2] in ("add", "commit")


def check_command(command, directory):
    """Follows `cd`; a child shell's `cd` ends with it."""
    items = list(commands_in(command))
    if any(stages(item) for item in items):
        change = location_change(items)
        if change:
            deny(
                f"this command line runs git add or commit and {change}. Run git add/commit "
                "without overriding the repository location; use cd or git -C."
            )
    pending_paths = []
    saved = []
    for item in items:
        if item == ENTER:
            saved.append(directory)
        elif item == EXIT:
            if saved:
                directory = saved.pop()
        elif item == CHILD or item[0] == "assign":
            continue
        else:
            words = item[1]
            if words[0] == "cd":
                if len(words) > 1 and words[1] != "-":
                    directory = os.path.join(directory, os.path.expanduser(words[1]))
            elif basename(words[0]) == "git":
                check_git(words, directory, pending_paths)


def main():
    event = json.load(sys.stdin)
    tool_input = event.get("tool_input") or {}
    if event.get("tool_name") in ("Bash", "Monitor"):
        check_command(tool_input.get("command") or "", event.get("cwd") or os.getcwd())
    elif is_secret_path(tool_input.get("file_path") or ""):
        deny(f"{tool_input['file_path']} is a secret file and must not be written by Claude.")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        deny(f"the check itself failed, so the call is refused ({error!r}).")
