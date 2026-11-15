"""P12.11: every GitHub Actions workflow starts only by hand.

Actions costs money, so no workflow may run on its own. Each workflow keeps its jobs and only
`workflow_dispatch` in its `on:` block, and a comment beside `on:` says how to start it and which
triggers bring the automatic runs back.

The trigger checks read the workflows with PyYAML when it is installed (GitHub's Ubuntu runners
ship it). Without it they skip with the reason; the plain-text checks still run.
"""
import re
import unittest
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover - depends on the machine
    yaml = None

REPO_ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS_DIR = REPO_ROOT / ".github" / "workflows"
WORKFLOWS = ("ci.yml", "emulator.yml", "release.yml")
NEEDS_YAML = "PyYAML is not installed; the parsed trigger checks need it"

# The triggers each workflow had before P12.11; the comment names them so they can be restored.
RESTORE = {
    "ci.yml": ("push", "pull_request"),
    "emulator.yml": ("push", "pull_request"),
    "release.yml": ("push", "tags", "v*"),
}


def load(name):
    data = yaml.safe_load((WORKFLOWS_DIR / name).read_text(encoding="utf-8"))
    # YAML 1.1 reads the bare key `on` as the boolean True.
    if True in data:
        data["on"] = data.pop(True)
    return data


def on_comment(name):
    """The comment lines right above `on:` and on the `on:` line itself, joined."""
    lines = (WORKFLOWS_DIR / name).read_text(encoding="utf-8").splitlines()
    index = next((i for i, line in enumerate(lines) if re.match(r"on\s*:", line)), None)
    if index is None:
        return ""
    comment = []
    for line in reversed(lines[:index]):
        if not line.lstrip().startswith("#"):
            break
        comment.insert(0, line.lstrip()[1:].strip())
    inline = lines[index].partition("#")[2]
    if inline:
        comment.append(inline.strip())
    return " ".join(comment)


class Files(unittest.TestCase):
    def test_the_workflow_files_still_exist(self):
        for name in WORKFLOWS:
            with self.subTest(workflow=name):
                self.assertTrue((WORKFLOWS_DIR / name).is_file(), f"{name} is missing")

    def test_no_other_workflow_can_run_on_its_own(self):
        found = sorted(p.name for p in WORKFLOWS_DIR.iterdir() if p.suffix in (".yml", ".yaml"))
        self.assertEqual(found, sorted(WORKFLOWS))


@unittest.skipIf(yaml is None, NEEDS_YAML)
class Triggers(unittest.TestCase):
    def test_only_workflow_dispatch_starts_each_workflow(self):
        for name in WORKFLOWS:
            with self.subTest(workflow=name):
                triggers = load(name)["on"]
                self.assertIsInstance(triggers, dict)
                self.assertEqual(list(triggers), ["workflow_dispatch"])
                # No inputs: the ref chosen when starting the run is all a workflow gets.
                self.assertIn(triggers["workflow_dispatch"], (None, {}))

    def test_the_jobs_are_still_there(self):
        for name in WORKFLOWS:
            with self.subTest(workflow=name):
                self.assertTrue(load(name).get("jobs"))


class Comment(unittest.TestCase):
    def test_a_comment_beside_on_explains_the_manual_start(self):
        for name in WORKFLOWS:
            comment = on_comment(name)
            with self.subTest(workflow=name):
                self.assertTrue(comment, "no comment beside on:")
                lowered = comment.lower()
                self.assertIn("money", lowered)
                self.assertRegex(lowered, r"by hand|manual")
                self.assertIn("Actions page", comment)
                self.assertIn(f"gh workflow run {name} --ref", comment)

    def test_the_comment_names_the_triggers_to_restore(self):
        for name, words in RESTORE.items():
            comment = on_comment(name)
            with self.subTest(workflow=name):
                self.assertRegex(comment.lower(), r"restore|turn .* back on|re-?enable")
                for word in words:
                    self.assertIn(word, comment)


if __name__ == "__main__":
    unittest.main()
