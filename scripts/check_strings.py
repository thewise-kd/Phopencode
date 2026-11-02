#!/usr/bin/env python3
"""Checks the app's user-facing strings. Standard library only.

Prints problems as `path:line: message` and exits 1 when there are any. Exits 2
when the check itself cannot run.

String resources, in every `src/<set>/res/` that is not a test source set:
- each file with strings has a partner of the same name in `values/` and
  `values-tr/`; an empty `strings_*.xml` stub needs one too,
- the two files hold the same keys, except `translatable="false"` keys, which
  live in `values/` only,
- keys of `strings_<f>_<screen>.xml` start with `<f>_<screen>_`. A plain
  `strings.xml` has no prefix to follow, it is the module-level file.

Hard-coded text in Kotlin files that use Compose, outside test source sets and
`@Preview` functions: a literal with letters in it, passed as `Text("...")`, as
a named argument such as `title = "..."`, or set as a semantics description.

This reads tokens, not lines, so comments, string templates and the enclosing
call are known. It does not resolve types: it misses text behind a variable, in
a `when` branch or in a default parameter value, and it trusts the argument name.

Text that really is not translated, such as a product name, is allowed by a
comment on the literal's line that gives the reason: `// strings-ok: <reason>`.
"""
import argparse
import os
import re
import sys
import xml.parsers.expat
from collections import namedtuple
from pathlib import Path

DEFAULT_ROOT = Path(__file__).resolve().parents[1]
SKIPPED_DIRECTORIES = {
    ".git", ".gradle", ".kotlin", ".idea", ".claude", ".cxx", "node_modules", "__pycache__",
}
STRING_ELEMENTS = {"string", "plurals", "string-array"}
ENGLISH, TURKISH = "values", "values-tr"
PREFIXED_FILE = re.compile(r"strings_(.+)\.xml")

COMPOSE_PACKAGES = (
    ["androidx", ".", "compose", "."],
    ["androidx", ".", "navigation", ".", "compose", "."],
)
ALLOW_COMMENT = re.compile(r"strings-ok:\s*\S")
FORMAT_SPECIFIER = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]")

# The argument index that holds user-visible text in calls that take it by position.
POSITIONAL_TEXT = {
    "Text": 0, "BasicText": 0, "Icon": 1, "Image": 1, "showSnackbar": 0, "makeText": 1,
}
# These are usually called on a receiver; `Foo.Text(...)` is a domain type instead.
QUALIFIED_TEXT_CALLS = {"showSnackbar", "makeText"}

TEXT_WORDS = (
    "text", "title", "subtitle", "label", "placeholder", "hint", "message",
    "description", "headline",
)
_CAPITALISED = "|".join(word.capitalize() for word in TEXT_WORDS)
# `text`, `confirmText`, `emptyTitle`, `contentDescription`; never `context`.
TEXT_ARGUMENT = re.compile(rf"(?:{'|'.join(TEXT_WORDS)})|[a-z]\w*(?:{_CAPITALISED})")
SEMANTIC_PROPERTIES = {"contentDescription", "stateDescription"}

ANIMATION_CALL = re.compile(
    r"animate[A-Z]\w*|update\w*Transition|remember\w*Transition|create\w*Transition"
    r"|Crossfade|AnimatedContent|AnimatedVisibility"
)
NON_UI_CALL = re.compile(
    r"(?:[vdiwe]|wtf|log\w*|debug|info|warn\w*|error|trace|print\w*|check\w*|require\w*"
    r"|assert\w*|fail)|\w*(?:Exception|Error)",
)
# Punctuation that can end what a `(` calls: `f()(x)`, `a[0](x)`, `{ }(x)`, `f<T>(x)`.
CALLABLE_ENDS = {")", "]", "}", ">"}
NON_CALLS = {
    "if", "while", "for", "when", "catch", "fun", "constructor", "super", "this", "return",
}
DECLARATION_START = {
    "fun", "val", "var", "class", "object", "interface", "typealias", "private", "internal",
    "public", "protected", "@",
}

OPERATORS = (
    "===", "!==", "==", "!=", "<=", ">=", "->", "+=", "-=", "*=", "/=", "%=", "?:", "?.", "::",
    "&&", "||",
)

Resource = namedtuple("Resource", "element name line translatable")


class Token(namedtuple("Token", "kind text line")):
    """`kind` is id, name (a backticked identifier), string or op."""

    @property
    def code(self):
        """The text as syntax, for comparing with keywords and punctuation.

        A string or a backticked name can hold any text and is never syntax, so
        structure must be read from `code`; `text` is for the content itself.
        """
        return "" if self.kind in ("string", "name") else self.text


def rel(path, root):
    return Path(path).relative_to(root).as_posix()


def walk(root):
    """Yields the source files of the repository, without build output."""
    for directory, subdirectories, files in os.walk(root):
        parts = Path(directory).relative_to(root).parts
        inside_sources = "src" in parts
        subdirectories[:] = sorted(
            name
            for name in subdirectories
            if name not in SKIPPED_DIRECTORIES and (inside_sources or name != "build")
        )
        for name in sorted(files):
            yield Path(directory) / name


def source_set(relative_parts):
    """Returns the `<set>` of `.../src/<set>/...`, or None outside a source set."""
    for index, part in enumerate(relative_parts[:-1]):
        if part == "src":
            return relative_parts[index + 1]
    return None


def is_shipped(relative_parts):
    name = source_set(relative_parts)
    return name is not None and "test" not in name.lower()


# String resources


def parse_resources(path):
    """Returns (resources, None), or (None, (line, message)) for a broken file.

    A valid XML file that is not a `<resources>` file gives an empty list.
    """
    found = []
    depth = 0
    is_resources = False
    parser = xml.parsers.expat.ParserCreate()

    def start(name, attributes):
        nonlocal depth, is_resources
        if depth == 0:
            is_resources = name == "resources"
        elif depth == 1 and is_resources and name in STRING_ELEMENTS and "name" in attributes:
            translatable = attributes.get("translatable", "true") != "false"
            found.append(Resource(name, attributes["name"], parser.CurrentLineNumber, translatable))
        depth += 1

    def end(_name):
        nonlocal depth
        depth -= 1

    parser.StartElementHandler = start
    parser.EndElementHandler = end
    try:
        parser.Parse(path.read_bytes(), True)
    except xml.parsers.expat.ExpatError as error:
        return None, (error.lineno, f"invalid XML: {error}")
    except OSError as error:
        return None, (1, f"cannot read the file: {error.strerror}")
    return found, None


def identity(resource):
    """Android keeps one namespace per type: R.string.a and R.plurals.a can coexist."""
    return resource.element, resource.name


def check_duplicates(path_name, resources, seen, problems):
    for resource in resources:
        key = identity(resource)
        if key in seen:
            problems.append(
                (path_name, resource.line, f"duplicate key {resource.name}, first in {seen[key]}")
            )
        else:
            seen[key] = path_name


def missing_message(resource, other_resources, other_file):
    message = f"<{resource.element}> key {resource.name} is missing from {other_file}"
    others = sorted({r.element for r in other_resources if r.name == resource.name})
    if others:
        message += ", which declares it as " + " and ".join(f"<{other}>" for other in others)
    return message


def check_prefix(file_name, path_name, resources, problems):
    match = PREFIXED_FILE.fullmatch(file_name)
    if not match:
        return
    prefix = match.group(1) + "_"
    for resource in resources:
        if not resource.name.startswith(prefix) or resource.name == prefix:
            problems.append(
                (path_name, resource.line, f"key {resource.name} must start with {prefix}")
            )


def check_pair(file_name, english, turkish, english_name, turkish_name, problems):
    """Compares one file name across the two folders; `None` means it is absent."""
    if turkish is None or english is None:
        present, name, other_folder = (
            (english, english_name, f"{TURKISH}/") if turkish is None else (turkish, turkish_name, f"{ENGLISH}/")
        )
        # Only `values/` may hold a file of untranslatable strings on its own.
        needs_partner = (
            turkish is not None
            or PREFIXED_FILE.fullmatch(file_name)
            or any(r.translatable for r in present)
        )
        if needs_partner:
            problems.append((name, 1, f"no partner file {other_folder}{file_name}"))
        return

    english_keys = {identity(r): r for r in english}
    turkish_keys = {identity(r) for r in turkish}
    for resource in english:
        if resource.translatable and identity(resource) not in turkish_keys:
            message = missing_message(resource, turkish, f"{TURKISH}/{file_name}")
            problems.append((english_name, resource.line, message))
    for resource in turkish:
        counterpart = english_keys.get(identity(resource))
        if counterpart is None:
            message = missing_message(resource, english, f"{ENGLISH}/{file_name}")
            problems.append((turkish_name, resource.line, message))
        elif not counterpart.translatable:
            problems.append(
                (turkish_name, resource.line, f'key {resource.name} is translatable="false" in {ENGLISH}/')
            )


def check_res_directory(res, root, problems):
    folders = {}
    for folder in (ENGLISH, TURKISH):
        files = {}
        seen = {}
        for path in sorted((res / folder).glob("*.xml")):
            path_name = rel(path, root)
            resources, error = parse_resources(path)
            if error:
                problems.append((path_name, error[0], error[1]))
                files[path.name] = "broken"
                continue
            if resources or PREFIXED_FILE.fullmatch(path.name):
                files[path.name] = resources
                check_duplicates(path_name, resources, seen, problems)
                check_prefix(path.name, path_name, resources, problems)
        folders[folder] = files

    for file_name in sorted(set(folders[ENGLISH]) | set(folders[TURKISH])):
        english = folders[ENGLISH].get(file_name)
        turkish = folders[TURKISH].get(file_name)
        if "broken" in (english, turkish):
            continue
        check_pair(
            file_name,
            english,
            turkish,
            rel(res / ENGLISH / file_name, root),
            rel(res / TURKISH / file_name, root),
            problems,
        )


# Kotlin tokens


class Tokenizer:
    """Reads Kotlin into identifiers, strings and punctuation.

    A string token's text is its literal part only: templates are dropped and
    escapes decoded, so `"Hi $name\\n"` reads `Hi `. It never raises on broken
    source; an unterminated construct simply ends where the line or file does.
    """

    def __init__(self, source):
        self.source = source
        self.position = 0
        self.line = 1
        self.tokens = []
        self.comments = {}

    def peek(self, offset=0):
        index = self.position + offset
        return self.source[index] if index < len(self.source) else ""

    def advance(self, count=1):
        end = min(self.position + count, len(self.source))
        self.line += self.source.count("\n", self.position, end)
        self.position = end

    def run(self):
        while self.position < len(self.source):
            char = self.peek()
            line = self.line
            if char.isspace():
                self.advance()
            elif self.source.startswith("//", self.position):
                self.line_comment()
            elif self.source.startswith("/*", self.position):
                self.block_comment()
            elif self.source.startswith('"""', self.position):
                self.tokens.append(Token("string", self.raw_string(), line))
            elif char == '"':
                self.tokens.append(Token("string", self.plain_string(), line))
            elif char == "'":
                self.char_literal()
            elif char == "`":
                self.tokens.append(Token("name", self.backtick_name(), line))
            elif char.isalnum() or char == "_":
                self.tokens.append(Token("id", self.word(), line))
            else:
                self.tokens.append(Token("op", self.operator(), line))
        return self.tokens, self.comments

    def line_comment(self):
        end = self.source.find("\n", self.position)
        end = len(self.source) if end < 0 else end
        self.comments[self.line] = self.source[self.position : end]
        self.position = end

    def block_comment(self):
        depth = 0
        while self.position < len(self.source):
            if self.source.startswith("/*", self.position):
                depth += 1
                self.advance(2)
            elif self.source.startswith("*/", self.position):
                depth -= 1
                self.advance(2)
                if depth == 0:
                    return
            else:
                self.advance()

    def word(self):
        start = self.position
        while self.peek().isalnum() or self.peek() == "_":
            self.advance()
        return self.source[start : self.position]

    def backtick_name(self):
        self.advance()
        start = self.position
        while self.peek() and self.peek() not in "`\n":
            self.advance()
        name = self.source[start : self.position]
        self.advance()
        return name

    def operator(self):
        for operator in OPERATORS:
            if self.source.startswith(operator, self.position):
                self.advance(len(operator))
                return operator
        char = self.peek()
        self.advance()
        return char

    def char_literal(self):
        self.advance()
        if self.peek() == "\\":
            self.advance(2)
        while self.peek() and self.peek() not in "'\n":
            self.advance()
        if self.peek() == "'":
            self.advance()

    def template(self):
        """Skips `$name` or `${...}`; a lone `$` is literal text and returns False."""
        if self.peek(1) == "{":
            self.advance(2)
            depth = 1
            while self.position < len(self.source) and depth:
                char = self.peek()
                if self.source.startswith('"""', self.position):
                    self.raw_string()
                elif char == '"':
                    self.plain_string()
                elif char == "'":
                    self.char_literal()
                else:
                    depth += (char == "{") - (char == "}")
                    self.advance()
            return True
        if self.peek(1).isalpha() or self.peek(1) == "_":
            self.advance()
            self.word()
            return True
        return False

    def plain_string(self):
        self.advance()
        text = []
        while self.position < len(self.source):
            char = self.peek()
            if char == '"':
                self.advance()
                break
            if char == "\n":
                break
            if char == "\\":
                text.append(self.escape())
            elif char == "$" and self.template():
                continue
            else:
                text.append(char)
                self.advance()
        return "".join(text)

    def escape(self):
        if self.peek(1) == "u":
            digits = self.source[self.position + 2 : self.position + 6]
            if re.fullmatch(r"[0-9a-fA-F]{4}", digits):
                self.advance(6)
                return chr(int(digits, 16))
        self.advance(2)
        return ""

    def raw_string(self):
        self.advance(3)
        text = []
        while self.position < len(self.source):
            if self.source.startswith('"""', self.position):
                self.advance(3)
                while self.peek() == '"':
                    self.advance()
                break
            char = self.peek()
            if char == "$" and self.template():
                continue
            text.append(char)
            self.advance()
        return "".join(text)


# Hard-coded text


class Frame:
    """An open bracket, and for `(`, the call it belongs to."""

    def __init__(self, bracket, owner=None, qualified=False, declaration=False):
        self.bracket = bracket
        self.owner = owner
        self.qualified = qualified
        self.declaration = declaration
        self.index = 0
        self.argument = None
        self.at_start = True

    def grouped(self):
        """Returns the frame of a grouping `(`, which passes text to the same target."""
        frame = Frame("(", self.owner, self.qualified, self.declaration)
        frame.index = self.index
        frame.argument = self.argument
        frame.at_start = False
        return frame


def has_words(text):
    return any(char.isalpha() for char in FORMAT_SPECIFIER.sub("", text))


def preview_ranges(tokens):
    """Returns {start: end} token ranges of the functions annotated with @Preview."""
    ranges = {}
    index = 0
    while index < len(tokens) - 1:
        if tokens[index].code == "@" and tokens[index + 1].kind == "id":
            cursor = index + 1
            while (
                cursor + 2 < len(tokens)
                and tokens[cursor + 1].code == "."
                and tokens[cursor + 2].kind == "id"
            ):
                cursor += 2
            name = tokens[cursor].text
            if name.startswith("Preview") and name != "PreviewParameter":
                end = function_end(tokens, cursor + 1)
                ranges[index] = end
                index = end
                continue
        index += 1
    return ranges


def function_end(tokens, start):
    """Returns the index just past the function declared at or after `start`."""
    index = start
    while index < len(tokens) and tokens[index].code != "fun":
        if tokens[index].code in ("class", "object", "interface"):
            return start
        index += 1
    depth = 0
    while index < len(tokens):
        code = tokens[index].code
        if code in ("(", "["):
            depth += 1
        elif code in (")", "]"):
            depth -= 1
        elif depth == 0 and code == "{":
            return matching_brace(tokens, index)
        elif depth == 0 and code == "=":
            return expression_end(tokens, index + 1)
        index += 1
    return index


def matching_brace(tokens, start):
    depth = 0
    for index in range(start, len(tokens)):
        depth += (tokens[index].code == "{") - (tokens[index].code == "}")
        if depth == 0:
            return index + 1
    return len(tokens)


def expression_end(tokens, start):
    depth = 0
    for index in range(start, len(tokens)):
        code = tokens[index].code
        if depth == 0 and code in DECLARATION_START:
            return index
        if code in ("(", "[", "{"):
            depth += 1
        elif code in (")", "]", "}"):
            depth -= 1
            if depth < 0:
                return index
    return len(tokens)


def excluded_argument(frame, name):
    owner = frame.owner
    if owner is None:
        return False
    if frame.qualified and owner[0].isupper():
        return True
    if NON_UI_CALL.fullmatch(owner):
        return True
    return name == "label" and ANIMATION_CALL.fullmatch(owner) is not None


def text_target(tokens, index, frame):
    """Returns what the string at `index` is passed to, or None if it is not UI text."""
    before = tokens[index - 1].code if index else ""
    after = tokens[index + 1].code if index + 1 < len(tokens) else ""
    if before in ("==", "!=") or after in ("==", "!="):
        return None
    if frame.bracket == "{":
        if (
            before == "="
            and index >= 2
            and tokens[index - 2].code in SEMANTIC_PROPERTIES
            and (index < 3 or tokens[index - 3].code not in ("val", "var"))
        ):
            return tokens[index - 2].text
        return None
    if frame.bracket != "(":
        return None
    if frame.argument is not None:
        if TEXT_ARGUMENT.fullmatch(frame.argument) and not excluded_argument(frame, frame.argument):
            return frame.argument
        return None
    if (
        POSITIONAL_TEXT.get(frame.owner) == frame.index
        and not frame.declaration
        and (not frame.qualified or frame.owner in QUALIFIED_TEXT_CALLS)
    ):
        return f"{frame.owner}(...)"
    return None


def open_frame(tokens, index, enclosing):
    bracket = tokens[index].code
    previous = tokens[index - 1] if index else None
    if (
        bracket == "("
        and enclosing.bracket == "("
        and previous is not None
        and previous.kind == "op"
        and previous.code not in CALLABLE_ENDS
    ):
        return enclosing.grouped()
    if (
        bracket != "("
        or previous is None
        or previous.kind not in ("id", "name")
        or previous.code in NON_CALLS
    ):
        return Frame(bracket)
    before = tokens[index - 2].code if index >= 2 else ""
    return Frame(
        bracket,
        owner=previous.text,
        qualified=before in (".", "?."),
        declaration=before in ("fun", "class"),
    )


def uses_compose(tokens):
    """Reads tokens, so comments and string contents never count."""
    codes = [token.code for token in tokens]
    for index, code in enumerate(codes):
        if code == "@" and codes[index + 1 : index + 2] == ["Composable"]:
            return True
        if code == "import" and any(
            codes[index + 1 : index + 1 + len(package)] == package for package in COMPOSE_PACKAGES
        ):
            return True
    return False


def find_hard_coded_text(source):
    """Returns (line, message) for each user-visible literal in Kotlin `source`."""
    tokens, comments = Tokenizer(source).run()
    if not uses_compose(tokens):
        return []

    skipped = preview_ranges(tokens)
    found = []
    stack = [Frame("{")]
    index = 0
    while index < len(tokens):
        if index in skipped:
            index = max(skipped[index], index + 1)
            continue
        token = tokens[index]
        frame = stack[-1]
        if token.kind == "string":
            frame.at_start = False
            if has_words(token.text):
                target = text_target(tokens, index, frame)
                if target and not ALLOW_COMMENT.search(comments.get(token.line, "")):
                    found.append(
                        (
                            token.line,
                            f"hard-coded text in {target}: use a string resource, "
                            "or `// strings-ok: <reason>` if it is never translated",
                        )
                    )
        elif token.code in ("(", "[", "{"):
            frame.at_start = False
            stack.append(open_frame(tokens, index, frame))
        elif token.code in (")", "]", "}"):
            if len(stack) > 1:
                stack.pop()
        elif token.code == "," and frame.bracket == "(":
            frame.index += 1
            frame.argument = None
            frame.at_start = True
        else:
            if (
                frame.bracket == "("
                and frame.at_start
                and token.kind == "id"
                and index + 1 < len(tokens)
                and tokens[index + 1].code == "="
            ):
                frame.argument = token.text
            frame.at_start = False
        index += 1
    return found


# Entry point


def run_checks(root):
    problems = []
    res_directories = set()
    for path in walk(root):
        relative = path.relative_to(root)
        parts = relative.parts
        if not is_shipped(parts):
            continue
        if path.suffix == ".kt":
            try:
                source = path.read_text(encoding="utf-8", errors="replace")
            except OSError as error:
                problems.append((relative.as_posix(), 1, f"cannot read the file: {error.strerror}"))
                continue
            for line, message in find_hard_coded_text(source):
                problems.append((relative.as_posix(), line, message))
        elif (
            path.suffix == ".xml"
            and path.parent.name in (ENGLISH, TURKISH)
            and path.parent.parent.name == "res"
        ):
            res_directories.add(path.parent.parent)
    for res in sorted(res_directories):
        check_res_directory(res, root, problems)
    return sorted(set(problems))


def main():
    parser = argparse.ArgumentParser(description="Check TR/EN strings and hard-coded UI text.")
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT, help="repository root")
    root = parser.parse_args().root.resolve()
    if not root.is_dir():
        print(f"check_strings: error: {root} is not a directory", file=sys.stderr)
        return 2
    problems = run_checks(root)
    for path, line, message in problems:
        print(f"{path}:{line}: {message}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
