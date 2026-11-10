"""P12.6: the user documentation exists in English and Turkish, the two languages have the same
structure, every relative link resolves, and the version numbers they state match the code.

Headings and links inside fenced code blocks and inline code are ignored. Anchors are checked
against GitHub's heading IDs: lower case, punctuation other than `-` and `_` dropped, spaces
turned into `-`, and `-1`, `-2`, ... appended to repeats.
"""
import re
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

# (English, Turkish) pairs, relative to the repository root.
PAIRS = (
    ("README.md", "README.tr.md"),
    ("docs/INSTALL.md", "docs/INSTALL.tr.md"),
    ("docs/SECURITY-MODEL.md", "docs/SECURITY-MODEL.tr.md"),
)

FENCE = re.compile(r"^\s*(```|~~~)")
HEADING = re.compile(r"^(#{1,6})\s+(.+?)\s*#*\s*$")
INLINE_CODE = re.compile(r"`[^`\n]*`")
HTML_COMMENT = re.compile(r"<!--.*?-->", re.DOTALL)
MD_LINK = re.compile(r"!?\[[^\]\n]*\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)")
HTML_LINK = re.compile(r"\b(?:href|src)=\"([^\"]+)\"")
EXTERNAL = re.compile(r"^(?:[a-z][a-z0-9+.-]*:|//)", re.IGNORECASE)


def read(path):
    return (REPO_ROOT / path).read_text(encoding="utf-8")


def prose_lines(text):
    """The lines outside fenced code blocks and HTML comments, with inline code removed."""
    inside = False
    lines = []
    for line in HTML_COMMENT.sub("", text).splitlines():
        if FENCE.match(line):
            inside = not inside
            continue
        if not inside:
            lines.append(INLINE_CODE.sub("", line))
    return lines


def headings(text):
    """(level, text) of every Markdown heading outside code."""
    found = []
    for line in prose_lines(text):
        match = HEADING.match(line)
        if match:
            found.append((len(match.group(1)), match.group(2)))
    return found


def slug(title):
    """GitHub's heading ID for `title`, before repeats are numbered."""
    text = re.sub(r"<[^>]+>", "", title)
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = text.strip().lower()
    text = "".join(ch for ch in text if ch.isalnum() or ch in " -_")
    return text.replace(" ", "-")


def anchors(text):
    seen = {}
    result = set()
    for _, title in headings(text):
        base = slug(title)
        count = seen.get(base, 0)
        seen[base] = count + 1
        result.add(base if count == 0 else "%s-%d" % (base, count))
    return result


def links(text):
    targets = []
    for line in prose_lines(text):
        targets.extend(MD_LINK.findall(line))
        targets.extend(HTML_LINK.findall(line))
    return targets


def relative_links(path):
    return [t for t in links(read(path)) if not EXTERNAL.match(t)]


def resolve(source, target):
    """(file path relative to the root or None for the same file, anchor or None)."""
    file_part, _, anchor = target.partition("#")
    if not file_part:
        return None, anchor or None
    resolved = (REPO_ROOT / source).parent.joinpath(file_part).resolve()
    return resolved, anchor or None


def all_docs():
    return [doc for pair in PAIRS for doc in pair]


class PairsTest(unittest.TestCase):
    def test_every_document_exists(self):
        for doc in all_docs():
            with self.subTest(doc=doc):
                self.assertTrue((REPO_ROOT / doc).is_file(), doc + " is missing")

    def test_each_pair_has_the_same_heading_levels(self):
        for english, turkish in PAIRS:
            with self.subTest(pair=english):
                en = [level for level, _ in headings(read(english))]
                tr = [level for level, _ in headings(read(turkish))]
                self.assertTrue(en, english + " has no headings")
                self.assertEqual(en, tr, "%s and %s differ in structure" % (english, turkish))

    def test_each_pair_links_the_other_language(self):
        for english, turkish in PAIRS:
            with self.subTest(pair=english):
                en_targets = {resolve(english, t)[0] for t in relative_links(english)}
                tr_targets = {resolve(turkish, t)[0] for t in relative_links(turkish)}
                self.assertIn((REPO_ROOT / turkish).resolve(), en_targets, english + " does not link " + turkish)
                self.assertIn((REPO_ROOT / english).resolve(), tr_targets, turkish + " does not link " + english)

    def test_each_pair_has_the_same_number_of_code_blocks(self):
        for english, turkish in PAIRS:
            with self.subTest(pair=english):
                count = lambda doc: sum(1 for line in read(doc).splitlines() if FENCE.match(line))
                self.assertEqual(count(english), count(turkish))


class LinksTest(unittest.TestCase):
    def test_relative_links_resolve(self):
        for doc in all_docs():
            for target in relative_links(doc):
                with self.subTest(doc=doc, target=target):
                    path, anchor = resolve(doc, target)
                    file = path if path is not None else (REPO_ROOT / doc).resolve()
                    self.assertTrue(file.exists(), "%s links a missing %s" % (doc, target))
                    if anchor is not None:
                        self.assertEqual(file.suffix, ".md", "%s: anchor into a non-Markdown file" % doc)
                        self.assertIn(anchor, anchors(file.read_text(encoding="utf-8")),
                                      "%s links a missing anchor %s" % (doc, target))

    def test_readmes_link_the_user_guides_in_their_language(self):
        expected = {
            "README.md": ("docs/INSTALL.md", "docs/SECURITY-MODEL.md", "SECURITY.md"),
            "README.tr.md": ("docs/INSTALL.tr.md", "docs/SECURITY-MODEL.tr.md", "SECURITY.md"),
        }
        for readme, docs in expected.items():
            targets = {resolve(readme, t)[0] for t in relative_links(readme)}
            for doc in docs:
                with self.subTest(readme=readme, doc=doc):
                    self.assertIn((REPO_ROOT / doc).resolve(), targets)

    def test_guides_link_their_own_language_and_security_policy(self):
        for english, turkish in PAIRS[1:]:
            for doc, other_guides in ((english, [e for e, _ in PAIRS[1:]]), (turkish, [t for _, t in PAIRS[1:]])):
                targets = {resolve(doc, t)[0] for t in relative_links(doc)}
                with self.subTest(doc=doc):
                    self.assertIn((REPO_ROOT / "SECURITY.md").resolve(), targets, doc + " does not link SECURITY.md")
                    for other in other_guides:
                        if other != doc:
                            self.assertIn((REPO_ROOT / other).resolve(), targets, "%s does not link %s" % (doc, other))


class FactsTest(unittest.TestCase):
    """The numbers the guides state come from the code, so a change there fails here."""

    def code_value(self, path, pattern):
        match = re.search(pattern, read(path))
        self.assertIsNotNone(match, "%s no longer matches %s" % (path, pattern))
        return match

    def test_minimum_opencode_version(self):
        version = self.code_value(
            "core/api/src/main/kotlin/io/github/thewisekd/phopencode/core/api/OpencodeClient.kt",
            r'MIN_SERVER_VERSION: String = "([0-9.]+)"',
        ).group(1)
        for doc in ("README.md", "README.tr.md", "docs/INSTALL.md", "docs/INSTALL.tr.md"):
            with self.subTest(doc=doc):
                self.assertRegex(read(doc), r"opencode\]?(?:\([^)]*\))? %s\b" % re.escape(version))

    def test_minimum_android_version(self):
        api = self.code_value("gradle/libs.versions.toml", r'android-minSdk\s*=\s*"(\d+)"').group(1)
        for doc in ("README.md", "README.tr.md", "docs/INSTALL.md", "docs/INSTALL.tr.md"):
            with self.subTest(doc=doc):
                self.assertIn("API %s" % api, read(doc))

    def test_minimum_python_version(self):
        major, minor = self.code_value(
            "core/remote/src/main/kotlin/io/github/thewisekd/phopencode/core/remote/notify/NotifyCommands.kt",
            r"MIN_PYTHON = PythonVersion\((\d+), (\d+)\)",
        ).groups()
        for doc in ("docs/INSTALL.md", "docs/INSTALL.tr.md"):
            with self.subTest(doc=doc):
                self.assertIn("%s.%s" % (major, minor), read(doc))

    def test_default_ntfy_server(self):
        default = self.code_value("helper/notifyd/config.py", r'DEFAULT_NTFY_SERVER = "https://([^"]+)"').group(1)
        for doc in ("docs/INSTALL.md", "docs/INSTALL.tr.md"):
            with self.subTest(doc=doc):
                self.assertIn(default, read(doc))


class HelpersTest(unittest.TestCase):
    def test_slug_matches_github(self):
        self.assertEqual(slug("Building from source"), "building-from-source")
        self.assertEqual(slug("Kaynak koddan derleme"), "kaynak-koddan-derleme")
        self.assertEqual(slug("3. Connect: SSH and the host key"), "3-connect-ssh-and-the-host-key")
        self.assertEqual(slug("Sunucu gereksinimleri (özet)"), "sunucu-gereksinimleri-özet")

    def test_anchors_number_repeats(self):
        self.assertEqual(anchors("# A\n## A\n"), {"a", "a-1"})

    def test_code_is_ignored(self):
        text = "```\n# not a heading\n[x](missing.md)\n```\n`[y](missing.md)`\n<!--\n[z](missing.md)\n-->\n# Real\n"
        self.assertEqual(headings(text), [(1, "Real")])
        self.assertEqual(links(text), [])


if __name__ == "__main__":
    unittest.main()
