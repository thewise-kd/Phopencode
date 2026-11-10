"""P12.9: CHANGELOG.md and the app's version agree.

The release workflow refuses a tag that is not `v` + versionName (docs/dev-setup/release.md,
section 4), so the newest changelog entry must name the versionName that app/build.gradle.kts
sets, and every version heading must link to its release.
"""
import re
import unittest
from datetime import date
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CHANGELOG = REPO_ROOT / "CHANGELOG.md"
APP_BUILD = REPO_ROOT / "app" / "build.gradle.kts"

# Any second-level heading that starts with `[`: a version entry, well formed or not.
VERSION_HEADING = re.compile(r"^##\s+\[(?P<label>[^\]]*)\](?P<rest>.*)$")
WELL_FORMED = re.compile(r"^## \[(\d+\.\d+\.\d+)\] - (\d{4}-\d{2}-\d{2})$")
LINK_REFERENCE = re.compile(r"^\[([^\]]+)\]:\s+(\S+)\s*$")
VERSION_NAME = re.compile(r'^\s*versionName\s*=\s*"([^"]*)"', re.MULTILINE)
VERSION_CODE = re.compile(r"^\s*versionCode\s*=\s*(\S+)", re.MULTILINE)
FENCE = re.compile(r"^\s*(```|~~~)")


def changelog_lines():
    """The changelog's lines outside fenced code blocks."""
    inside = False
    lines = []
    for line in CHANGELOG.read_text(encoding="utf-8").splitlines():
        if FENCE.match(line):
            inside = not inside
            continue
        if not inside:
            lines.append(line)
    return lines


def version_headings():
    return [line for line in changelog_lines() if VERSION_HEADING.match(line)]


def build_text():
    return APP_BUILD.read_text(encoding="utf-8")


class ChangelogTest(unittest.TestCase):
    def test_changelog_exists(self):
        self.assertTrue(CHANGELOG.is_file(), "CHANGELOG.md is missing")

    def test_has_a_version_entry(self):
        self.assertTrue(version_headings(), "CHANGELOG.md has no `## [X.Y.Z] - YYYY-MM-DD` entry")

    def test_headings_are_well_formed(self):
        for heading in version_headings():
            with self.subTest(heading=heading):
                match = WELL_FORMED.match(heading)
                self.assertIsNotNone(match, "expected `## [X.Y.Z] - YYYY-MM-DD`")
                date.fromisoformat(match.group(2))  # a real calendar date

    def test_versions_are_unique_and_newest_first(self):
        versions = []
        for heading in version_headings():
            match = WELL_FORMED.match(heading)
            if match:
                versions.append(tuple(int(part) for part in match.group(1).split(".")))
        self.assertEqual(len(versions), len(set(versions)), "a version appears twice")
        self.assertEqual(versions, sorted(versions, reverse=True), "versions are not newest first")

    def test_every_version_has_a_link_reference(self):
        references = {}
        for line in changelog_lines():
            match = LINK_REFERENCE.match(line)
            if match:
                references[match.group(1)] = match.group(2)
        for heading in version_headings():
            label = VERSION_HEADING.match(heading).group("label")
            with self.subTest(version=label):
                self.assertIn(label, references, f"no `[{label}]: <url>` link reference")
                self.assertTrue(
                    references[label].startswith("https://"),
                    f"the link for {label} is not an https URL",
                )
                self.assertTrue(
                    references[label].endswith(f"/v{label}"),
                    f"the link for {label} does not point at the tag v{label}",
                )

    def test_newest_version_equals_version_name(self):
        names = VERSION_NAME.findall(build_text())
        self.assertEqual(len(names), 1, "app/build.gradle.kts must set versionName exactly once")
        headings = version_headings()
        self.assertTrue(headings, "CHANGELOG.md has no version entry")
        newest = VERSION_HEADING.match(headings[0]).group("label")
        self.assertEqual(newest, names[0], "the newest CHANGELOG entry is not the app's versionName")

    def test_version_code_is_a_positive_integer(self):
        codes = VERSION_CODE.findall(build_text())
        self.assertEqual(len(codes), 1, "app/build.gradle.kts must set versionCode exactly once")
        self.assertRegex(codes[0], r"^[1-9]\d*$", "versionCode is not a positive integer literal")


if __name__ == "__main__":
    unittest.main()
