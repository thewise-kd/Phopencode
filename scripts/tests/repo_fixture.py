"""Temporary repositories and script runners shared by the check script tests."""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = REPO_ROOT / "scripts"

# A hook that runs these tests must not leak its own repository into `git -C`.
GIT_ENV_TO_DROP = ("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_PREFIX")


def clean_env(ceiling):
    env = {key: value for key, value in os.environ.items() if key not in GIT_ENV_TO_DROP}
    env["GIT_CEILING_DIRECTORIES"] = str(ceiling)
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    return env


class TempRepoCase(unittest.TestCase):
    """Gives each test an empty directory and a way to run a script against it."""

    script = ""

    def setUp(self):
        self.base = Path(tempfile.mkdtemp(prefix="phopencode-check-")).resolve()
        self.addCleanup(shutil.rmtree, self.base, ignore_errors=True)
        self.root = self.base / "repo"
        self.root.mkdir()

    def write(self, path, content):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(content, bytes):
            target.write_bytes(content)
        else:
            target.write_text(content, encoding="utf-8")
        return target

    def run_script(self, *arguments, cwd=None):
        return subprocess.run(
            [sys.executable, str(SCRIPTS / self.script), *arguments],
            cwd=str(cwd or self.base),
            env=clean_env(self.base),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            universal_newlines=True,
            encoding="utf-8",
        )

    def check(self):
        return self.run_script("--root", str(self.root))

    def assert_clean(self, result=None):
        result = result or self.check()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def assert_problem(self, location, fragment, result=None):
        """Asserts a nonzero exit and a `location: ...fragment...` line."""
        result = result or self.check()
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertNotIn("Traceback", result.stderr)
        lines = result.stdout.splitlines()
        matching = [line for line in lines if line.startswith(location + ": ")]
        self.assertTrue(matching, f"nothing at {location} in:\n{result.stdout}")
        self.assertTrue(
            any(fragment in line for line in matching),
            f"{fragment!r} not at {location} in:\n{result.stdout}",
        )


class GitRepoCase(TempRepoCase):
    """A TempRepoCase whose directory is a real git checkout."""

    def setUp(self):
        super().setUp()
        self.git("init", "--quiet")

    def git(self, *arguments):
        subprocess.run(
            ["git", "-C", str(self.root), *arguments],
            env=clean_env(self.base),
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

    def track(self, path, content):
        target = self.write(path, content)
        self.git("add", "--force", "--", path)
        return target
