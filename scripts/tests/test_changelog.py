"""P12.9, P13.19: CHANGELOG.md and the app's version agree.

The release workflow refuses a tag that is not `v` + versionName (docs/dev-setup/release.md,
section 4), so the newest changelog entry must name the versionName that app/build.gradle.kts
sets, and every version heading must link to its release.

Changes that are not released yet go under one `## [Unreleased]` heading, which must come before
every version entry (Keep a Changelog). It is not a version: it is skipped when the newest version
is compared with versionName, and its link reference, if any, compares the newest version's tag
with `HEAD`.
"""
import re
import unittest
from datetime import date
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CHANGELOG = REPO_ROOT / "CHANGELOG.md"
APP_BUILD = REPO_ROOT / "app" / "build.gradle.kts"

UNRELEASED_LABEL = "Unreleased"
# Any second-level heading that starts with `[`: a version entry or Unreleased, well formed or not.
BRACKET_HEADING = re.compile(r"^##\s+\[(?P<label>[^\]]*)\](?P<rest>.*)$")
UNRELEASED_HEADING = "## [Unreleased]"
WELL_FORMED = re.compile(r"^## \[(\d+\.\d+\.\d+)\] - (\d{4}-\d{2}-\d{2})$")
LINK_REFERENCE = re.compile(r"^\[([^\]]+)\]:\s+(\S+)\s*$")
VERSION_NAME = re.compile(r'^\s*versionName\s*=\s*"([^"]*)"', re.MULTILINE)
VERSION_CODE = re.compile(r"^\s*versionCode\s*=\s*(\S+)", re.MULTILINE)
FENCE = re.compile(r"^\s*(```|~~~)")


def prose_lines(text):
    """The lines of `text` outside fenced code blocks."""
    inside = False
    lines = []
    for line in text.splitlines():
        if FENCE.match(line):
            inside = not inside
            continue
        if not inside:
            lines.append(line)
    return lines


def is_unreleased(heading):
    """`heading` is a bracket heading labelled Unreleased, in any case, well formed or not."""
    match = BRACKET_HEADING.match(heading)
    return bool(match) and match.group("label").strip().lower() == UNRELEASED_LABEL.lower()


def bracket_headings(text):
    """Every `## [...]` heading outside code, in order."""
    return [line for line in prose_lines(text) if BRACKET_HEADING.match(line)]


def version_headings(text):
    """The bracket headings that are not Unreleased: the version entries."""
    return [heading for heading in bracket_headings(text) if not is_unreleased(heading)]


def label_key(label):
    """How Markdown matches a reference label: case-insensitively, with runs of whitespace as one."""
    return " ".join(label.split()).casefold()


def link_definitions(text):
    """(label, url) of every link reference definition outside code, in order."""
    definitions = []
    for line in prose_lines(text):
        match = LINK_REFERENCE.match(line)
        if match:
            definitions.append((match.group(1), match.group(2)))
    return definitions


def link_references(text):
    """{label_key: url}; as in Markdown, the first definition of a label wins."""
    references = {}
    for label, url in link_definitions(text):
        references.setdefault(label_key(label), url)
    return references


def reference_problems(text):
    """Labels defined more than once, which Markdown treats as one label whatever their case."""
    seen = set()
    problems = []
    for label, _ in link_definitions(text):
        key = label_key(label)
        message = "the link reference [%s] is defined more than once" % key
        if key in seen and message not in problems:
            problems.append(message)
        seen.add(key)
    return problems


def unreleased_problems(text):
    """What is wrong with the Unreleased heading of `text`; empty when it is fine or absent."""
    headings = bracket_headings(text)
    unreleased = [index for index, heading in enumerate(headings) if is_unreleased(heading)]
    problems = []
    if len(unreleased) > 1:
        problems.append("more than one Unreleased heading")
    for index in unreleased:
        if headings[index] != UNRELEASED_HEADING:
            problems.append("expected exactly `%s`, found `%s`" % (UNRELEASED_HEADING, headings[index]))
        if index != 0:
            problems.append("Unreleased is not the first entry")
    if unreleased:
        link = link_references(text).get(label_key(UNRELEASED_LABEL))
        versions = version_headings(text)
        newest = BRACKET_HEADING.match(versions[0]).group("label") if versions else None
        if link is not None:
            if not link.startswith("https://"):
                problems.append("the link for Unreleased is not an https URL")
            if newest is not None and not link.endswith("/compare/v%s...HEAD" % newest):
                problems.append("the link for Unreleased does not compare v%s with HEAD" % newest)
    return problems


def heading_problems(text):
    """Version headings of `text` that are not `## [X.Y.Z] - YYYY-MM-DD` with a real date."""
    problems = []
    for heading in version_headings(text):
        match = WELL_FORMED.match(heading)
        if match is None:
            problems.append("expected `## [X.Y.Z] - YYYY-MM-DD`, found `%s`" % heading)
            continue
        try:
            date.fromisoformat(match.group(2))
        except ValueError:
            problems.append("not a calendar date in `%s`" % heading)
    return problems


def order_problems(text):
    """Repeated versions, or versions that are not newest first."""
    versions = []
    for heading in version_headings(text):
        match = WELL_FORMED.match(heading)
        if match:
            versions.append(tuple(int(part) for part in match.group(1).split(".")))
    problems = []
    if len(versions) != len(set(versions)):
        problems.append("a version appears twice")
    if versions != sorted(versions, reverse=True):
        problems.append("versions are not newest first")
    return problems


def link_problems(text):
    """Version entries without an https link reference to their tag."""
    references = link_references(text)
    problems = []
    for heading in version_headings(text):
        label = BRACKET_HEADING.match(heading).group("label")
        url = references.get(label_key(label))
        if url is None:
            problems.append("no `[%s]: <url>` link reference" % label)
        elif not url.startswith("https://"):
            problems.append("the link for %s is not an https URL" % label)
        elif not url.endswith("/v%s" % label):
            problems.append("the link for %s does not point at the tag v%s" % (label, label))
    return problems


def newest_version(text):
    """The label of the first version entry, skipping Unreleased; None without one."""
    versions = version_headings(text)
    return BRACKET_HEADING.match(versions[0]).group("label") if versions else None


def all_problems(text):
    return reference_problems(text) + unreleased_problems(text) + heading_problems(text) + order_problems(text) + link_problems(text)


def changelog_text():
    return CHANGELOG.read_text(encoding="utf-8")


def build_text():
    return APP_BUILD.read_text(encoding="utf-8")


class ChangelogTest(unittest.TestCase):
    def test_changelog_exists(self):
        self.assertTrue(CHANGELOG.is_file(), "CHANGELOG.md is missing")

    def test_has_a_version_entry(self):
        self.assertTrue(version_headings(changelog_text()), "CHANGELOG.md has no `## [X.Y.Z] - YYYY-MM-DD` entry")

    def test_unreleased_is_single_and_first(self):
        self.assertEqual(unreleased_problems(changelog_text()), [])

    def test_headings_are_well_formed(self):
        self.assertEqual(heading_problems(changelog_text()), [])

    def test_no_link_reference_is_defined_twice(self):
        self.assertEqual(reference_problems(changelog_text()), [])

    def test_versions_are_unique_and_newest_first(self):
        self.assertEqual(order_problems(changelog_text()), [])

    def test_every_version_has_a_link_reference(self):
        self.assertEqual(link_problems(changelog_text()), [])

    def test_newest_version_equals_version_name(self):
        names = VERSION_NAME.findall(build_text())
        self.assertEqual(len(names), 1, "app/build.gradle.kts must set versionName exactly once")
        newest = newest_version(changelog_text())
        self.assertIsNotNone(newest, "CHANGELOG.md has no version entry")
        self.assertEqual(newest, names[0], "the newest CHANGELOG entry is not the app's versionName")

    def test_version_code_is_a_positive_integer(self):
        codes = VERSION_CODE.findall(build_text())
        self.assertEqual(len(codes), 1, "app/build.gradle.kts must set versionCode exactly once")
        self.assertRegex(codes[0], r"^[1-9]\d*$", "versionCode is not a positive integer literal")


RELEASES = "https://github.com/example/app/releases/tag/"
COMPARE = "https://github.com/example/app/compare/"


def sample(*entries, references=None):
    """A changelog made of `entries` (heading lines, each followed by a bullet) and references."""
    body = ["# Changelog", ""]
    for heading in entries:
        body += [heading, "", "- A change.", ""]
    body += references if references is not None else [
        "[Unreleased]: %sv0.2.0...HEAD" % COMPARE,
        "[0.2.0]: %sv0.2.0" % RELEASES,
        "[0.1.0]: %sv0.1.0" % RELEASES,
    ]
    return "\n".join(body) + "\n"


class SampleTest(unittest.TestCase):
    """The rules on hand-made changelogs, so that each one is shown to catch its fault."""

    V2 = "## [0.2.0] - 2026-12-01"
    V1 = "## [0.1.0] - 2026-11-11"

    def test_a_leading_unreleased_is_accepted(self):
        text = sample(UNRELEASED_HEADING, self.V2, self.V1)
        self.assertEqual(all_problems(text), [])
        self.assertEqual(newest_version(text), "0.2.0")

    def test_no_unreleased_is_accepted(self):
        text = sample(self.V2, self.V1, references=["[0.2.0]: %sv0.2.0" % RELEASES, "[0.1.0]: %sv0.1.0" % RELEASES])
        self.assertEqual(all_problems(text), [])

    def test_unreleased_without_a_link_reference_is_accepted(self):
        text = sample(UNRELEASED_HEADING, self.V1, references=["[0.1.0]: %sv0.1.0" % RELEASES])
        self.assertEqual(all_problems(text), [])

    def test_two_unreleased_headings_are_refused(self):
        text = sample(UNRELEASED_HEADING, UNRELEASED_HEADING, self.V2, self.V1)
        self.assertIn("more than one Unreleased heading", unreleased_problems(text))

    def test_unreleased_after_a_version_is_refused(self):
        text = sample(self.V2, UNRELEASED_HEADING, self.V1)
        self.assertIn("Unreleased is not the first entry", unreleased_problems(text))

    def test_unreleased_with_a_date_or_other_case_is_refused(self):
        for heading in ("## [Unreleased] - 2026-12-01", "## [unreleased]", "##  [Unreleased]"):
            with self.subTest(heading=heading):
                problems = unreleased_problems(sample(heading, self.V2, self.V1))
                self.assertTrue(any(p.startswith("expected exactly") for p in problems), problems)

    def test_unreleased_link_must_compare_the_newest_tag_with_head(self):
        for link in ("%sv0.1.0...HEAD" % COMPARE, "%sv0.2.0...main" % COMPARE, "http://example.com/compare/v0.2.0...HEAD"):
            with self.subTest(link=link):
                references = ["[Unreleased]: " + link, "[0.2.0]: %sv0.2.0" % RELEASES, "[0.1.0]: %sv0.1.0" % RELEASES]
                self.assertNotEqual(unreleased_problems(sample(UNRELEASED_HEADING, self.V2, self.V1, references=references)), [])

    def test_a_lowercase_unreleased_link_is_still_checked(self):
        # Markdown matches reference labels case-insensitively, so this one links the heading.
        references = ["[unreleased]: http://example.com/wrong", "[0.2.0]: %sv0.2.0" % RELEASES, "[0.1.0]: %sv0.1.0" % RELEASES]
        problems = unreleased_problems(sample(UNRELEASED_HEADING, self.V2, self.V1, references=references))
        self.assertIn("the link for Unreleased is not an https URL", problems)
        self.assertIn("the link for Unreleased does not compare v0.2.0 with HEAD", problems)

    def test_a_differently_cased_unreleased_link_is_accepted_when_right(self):
        references = ["[UNRELEASED]: %sv0.2.0...HEAD" % COMPARE, "[0.2.0]: %sv0.2.0" % RELEASES, "[0.1.0]: %sv0.1.0" % RELEASES]
        self.assertEqual(all_problems(sample(UNRELEASED_HEADING, self.V2, self.V1, references=references)), [])

    def test_a_duplicate_label_is_refused(self):
        for duplicate in ("[Unreleased]: %sv0.2.0...HEAD" % COMPARE, "[unreleased]: http://example.com/wrong"):
            with self.subTest(duplicate=duplicate):
                references = [
                    "[Unreleased]: %sv0.2.0...HEAD" % COMPARE, duplicate,
                    "[0.2.0]: %sv0.2.0" % RELEASES, "[0.1.0]: %sv0.1.0" % RELEASES,
                ]
                problems = reference_problems(sample(UNRELEASED_HEADING, self.V2, self.V1, references=references))
                self.assertIn("the link reference [unreleased] is defined more than once", problems)
                self.assertNotEqual(all_problems(sample(UNRELEASED_HEADING, self.V2, self.V1, references=references)), [])

    def test_a_duplicate_version_label_is_refused(self):
        references = ["[0.1.0]: %sv0.1.0" % RELEASES, "[0.1.0]: http://example.com/v0.1.0"]
        text = sample(self.V1, references=references)
        self.assertIn("the link reference [0.1.0] is defined more than once", reference_problems(text))

    def test_a_wrong_version_link_with_another_case_is_still_checked(self):
        text = sample("## [0.1.0-RC] - 2026-11-11", references=["[0.1.0-rc]: http://example.com/v0.1.0-rc"])
        self.assertIn("the link for 0.1.0-RC is not an https URL", link_problems(text))

    def test_versions_after_unreleased_are_still_checked(self):
        faulty = {
            "## [0.2] - 2026-12-01": heading_problems,
            "## [0.2.0] - 2026-02-30": heading_problems,
            "## [0.2.0]": heading_problems,
        }
        for heading, check in faulty.items():
            with self.subTest(heading=heading):
                self.assertNotEqual(check(sample(UNRELEASED_HEADING, heading, self.V1)), [])

    def test_order_and_repeats_after_unreleased_are_still_checked(self):
        self.assertIn("versions are not newest first", order_problems(sample(UNRELEASED_HEADING, self.V1, self.V2)))
        self.assertIn("a version appears twice", order_problems(sample(UNRELEASED_HEADING, self.V1, self.V1)))

    def test_a_version_without_its_link_is_still_refused(self):
        text = sample(UNRELEASED_HEADING, self.V2, self.V1, references=["[0.1.0]: %sv0.1.0" % RELEASES])
        self.assertIn("no `[0.2.0]: <url>` link reference", link_problems(text))

    def test_only_unreleased_has_no_newest_version(self):
        text = sample(UNRELEASED_HEADING, references=[])
        self.assertIsNone(newest_version(text))
        self.assertEqual(version_headings(text), [])

    def test_headings_in_code_blocks_are_ignored(self):
        text = sample(UNRELEASED_HEADING, self.V2, self.V1) + "```\n## [Unreleased]\n```\n"
        self.assertEqual(all_problems(text), [])


if __name__ == "__main__":
    unittest.main()
