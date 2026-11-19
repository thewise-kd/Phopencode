"""P15.6: `.claude/scripts/wrapper-guard.sh`, the Bash hook of the reviewer and security relays.

A relay may run only its own script with plain arguments, named either by the relative
`.claude/scripts/<script>` path or by the absolute path of that same script in this project.
"""
import json
import subprocess
import unittest

from repo_fixture import REPO_ROOT

SCRIPTS = REPO_ROOT / ".claude" / "scripts"
REVIEW = "run-review.sh"
SECURITY = "run-security.sh"
ARGS = " --dir /w/t --paths core/ssh app"


def guard(script, command):
    return subprocess.run(
        ["bash", str(SCRIPTS / "wrapper-guard.sh"), script],
        input=json.dumps({"tool_input": {"command": command}}),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        universal_newlines=True,
    )


class WrapperGuardTest(unittest.TestCase):
    def assert_passes(self, script, command):
        result = guard(script, command)
        self.assertEqual(result.returncode, 0, f"{command!r} was blocked: {result.stderr}")

    def assert_blocked(self, script, command):
        result = guard(script, command)
        self.assertEqual(result.returncode, 2, f"{command!r} passed")
        self.assertIn("Blocked", result.stderr)

    def test_relative_path_passes(self):
        for script in (REVIEW, SECURITY):
            self.assert_passes(script, f".claude/scripts/{script}{ARGS}")
            self.assert_passes(script, f".claude/scripts/{script}")
            self.assert_passes(script, f"  .claude/scripts/{script} --dir /w/t --commit abc123  ")

    def test_absolute_path_of_the_same_script_in_this_project_passes(self):
        for script in (REVIEW, SECURITY):
            self.assert_passes(script, f"{SCRIPTS / script}{ARGS}")
            self.assert_passes(script, f"{SCRIPTS / script}")
        self.assert_passes(REVIEW, f"{SCRIPTS / REVIEW} --dir /w/t --range HEAD~2..HEAD")

    def test_another_script_is_blocked(self):
        self.assert_blocked(REVIEW, f".claude/scripts/{SECURITY}{ARGS}")
        self.assert_blocked(REVIEW, f"{SCRIPTS / SECURITY}{ARGS}")
        self.assert_blocked(SECURITY, f"{SCRIPTS / REVIEW}{ARGS}")
        for other in ("wrapper-guard.sh", "lib-verdict.sh"):
            self.assert_blocked(REVIEW, f"{SCRIPTS / other} run-review.sh")
            self.assert_blocked(REVIEW, f".claude/scripts/{other}")

    def test_the_same_name_elsewhere_is_blocked(self):
        for command in (
            f"/tmp/x/.claude/scripts/{REVIEW}{ARGS}",
            f"/.claude/scripts/{REVIEW}{ARGS}",
            f"{REPO_ROOT}x/.claude/scripts/{REVIEW}{ARGS}",
            f"{REPO_ROOT}/sub/.claude/scripts/{REVIEW}{ARGS}",
            f"{SCRIPTS}/../scripts/{REVIEW}{ARGS}",
            f"{SCRIPTS}//{REVIEW}{ARGS}",
            f"{SCRIPTS / REVIEW}x{ARGS}",
            f"x{SCRIPTS / REVIEW}{ARGS}",
            f"~/.claude/scripts/{REVIEW}{ARGS}",
            f"./.claude/scripts/{REVIEW}{ARGS}",
            f"scripts/{REVIEW}{ARGS}",
            f"{REVIEW}{ARGS}",
        ):
            self.assert_blocked(REVIEW, command)

    def test_anything_but_plain_arguments_is_blocked(self):
        for prefix in (f".claude/scripts/{REVIEW}", str(SCRIPTS / REVIEW)):
            for tail in (
                " --dir /w/t; rm -rf b",
                " --dir /w/t && touch x",
                " --dir /w/t | tee out",
                " --dir /w/t > out",
                " --dir $(touch x)",
                " --dir `touch x`",
                " --dir '/w/t'",
                ' --dir "/w/t"',
                " --dir /w/t\ntouch x",
                " --dir /w/t &",
                " --dir $HOME",
            ):
                self.assert_blocked(REVIEW, prefix + tail)
        self.assert_blocked(REVIEW, f"bash {SCRIPTS / REVIEW}{ARGS}")
        self.assert_blocked(REVIEW, f"cd {REPO_ROOT} && .claude/scripts/{REVIEW}{ARGS}")
        self.assert_blocked(REVIEW, f"FOO=1 .claude/scripts/{REVIEW}{ARGS}")


if __name__ == "__main__":
    unittest.main()
