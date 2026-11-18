"""P15.1: the whole-code audit mode of the review and security relay scripts.

No test calls a model: every run uses `--print-input`, and `codex` and `claude` on PATH are
stubs that record a call, which each test asserts never happened.
"""
import hashlib
import json
import os
import re
import resource
import subprocess
import unittest
from pathlib import Path

from repo_fixture import REPO_ROOT, GitRepoCase, clean_env

RELAYS = REPO_ROOT / ".claude" / "scripts"
REVIEW = "run-review.sh"
SECURITY = "run-security.sh"
REVIEW_CAP = 1000000
SECURITY_CAP = 400000

COMMITTED = "fun committed() = 1\n"
EDITED = "fun edited() = 2\n"


class RelayCase(GitRepoCase):
    def setUp(self):
        super().setUp()
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "user.name", "Test")
        self.stubs = self.base / "stubs"
        self.stubs.mkdir()
        self.calls = self.base / "model-calls"
        for tool in ("codex", "claude"):
            stub = self.stubs / tool
            stub.write_text(f'#!/bin/sh\necho {tool} >> "{self.calls}"\nexit 99\n', encoding="utf-8")
            stub.chmod(0o755)
        self.addCleanup(self.assert_no_model_call)

    def assert_no_model_call(self):
        self.assertFalse(self.calls.exists(), "a relay script called a model")

    def commit(self, message="commit"):
        self.git("commit", "--quiet", "--allow-empty", "-m", message)

    def relay(self, script, *arguments, memory_limit=None):
        def limit_memory():
            if memory_limit:
                resource.setrlimit(resource.RLIMIT_AS, (memory_limit, memory_limit))

        env = clean_env(self.base)
        env["PATH"] = f"{self.stubs}{os.pathsep}{env.get('PATH', '')}"
        env["GLM_SETTINGS"] = str(self.base / "no-such-glm.json")
        env["HOME"] = str(self.base / "home")
        return subprocess.run(
            ["bash", str(RELAYS / script), "--dir", str(self.root), *arguments],
            cwd=str(self.base),
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            universal_newlines=True,
            encoding="utf-8",
            errors="replace",
            preexec_fn=limit_memory,
        )

    def assert_exit(self, result, code):
        self.assertEqual(result.returncode, code, result.stdout + result.stderr)

    def audited(self, script, *paths):
        result = self.relay(script, "--print-input", "--paths", *paths)
        self.assert_exit(result, 0)
        return result.stdout

    def headers(self, text):
        return re.findall(r"^=== FILE (.+?) \(\d+ lines?\) ===$", text, re.MULTILINE)

    def standard_repo(self):
        """Tracked, untracked, ignored, staged and binary files around `src/`."""
        self.track(".gitignore", "secret.properties\n*.jks\n")
        self.track("src/Main.kt", COMMITTED)
        self.track("src/nested/Util.kt", "line one\nline two\nline three\n")
        self.track("src/logo.png", b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR")
        self.track("other/Other.kt", "fun other() = 3\n")
        self.track("srcfoo/Near.kt", "fun near() = 4\n")
        self.commit()
        self.write("src/Main.kt", EDITED)
        self.write("src/Untracked.kt", "fun untracked() = 5\n")
        self.write("src/secret.properties", "password=hunter2\n")
        self.write("src/release.jks", "keystore\n")
        self.track("src/Staged.kt", "fun staged() = 6\n")


class SelectionTest(RelayCase):
    def test_review_gives_head_content_of_tracked_text_files_only(self):
        self.standard_repo()
        out = self.audited(REVIEW, "src")
        self.assertEqual(self.headers(out), ["src/Main.kt", "src/nested/Util.kt"])
        self.assertIn("fun committed() = 1", out)
        self.assertNotIn("fun edited", out)
        for absent in ("untracked", "hunter2", "keystore", "staged", "PNG", "near", "other"):
            self.assertNotIn(absent, out)

    def test_security_gives_head_content_of_tracked_text_files_only(self):
        self.standard_repo()
        out = self.audited(SECURITY, "src")
        payload = out.split("<<<BEGIN UNTRUSTED CODE>>>", 1)[1]
        self.assertEqual(self.headers(payload), ["src/Main.kt", "src/nested/Util.kt"])
        self.assertIn("fun committed() = 1", payload)
        for absent in ("edited", "untracked", "hunter2", "keystore", "staged", "near"):
            self.assertNotIn(absent, out)

    def test_lines_are_numbered(self):
        self.standard_repo()
        out = self.audited(REVIEW, "src/nested")
        self.assertRegex(out, r"(?m)^\s*1\tline one$")
        self.assertRegex(out, r"(?m)^\s*3\tline three$")

    def test_line_numbers_follow_newlines_only(self):
        self.track("src/Odd.kt", "one\x0cstill one\u2028still one\r\ntwo\nthree")
        self.commit()
        out = self.audited(REVIEW, "src")
        self.assertIn("=== FILE src/Odd.kt (3 lines) ===", out)
        self.assertRegex(out, r"(?m)^\s*2\ttwo$")
        self.assertRegex(out, r"(?m)^\s*3\tthree$")

    def test_several_paths_and_repeated_flag(self):
        self.standard_repo()
        expected = ["other/Other.kt", "src/nested/Util.kt"]
        self.assertEqual(self.headers(self.audited(REVIEW, "src/nested", "other/")), expected)
        result = self.relay(REVIEW, "--paths", "other", "--print-input", "--paths", "./src/nested")
        self.assert_exit(result, 0)
        self.assertEqual(self.headers(result.stdout), expected)

    def test_dot_selects_the_whole_head_tree(self):
        self.standard_repo()
        self.assertEqual(
            self.headers(self.audited(REVIEW, ".")),
            [".gitignore", "other/Other.kt", "src/Main.kt", "src/nested/Util.kt", "srcfoo/Near.kt"],
        )

    def test_single_file_path(self):
        self.standard_repo()
        self.assertEqual(self.headers(self.audited(REVIEW, "src/Main.kt")), ["src/Main.kt"])

    def test_dir_inside_the_repo_still_resolves_paths_from_the_root(self):
        self.standard_repo()
        result = self.relay(REVIEW, "--dir", str(self.root / "src"), "--print-input", "--paths", "other")
        self.assert_exit(result, 0)
        self.assertEqual(self.headers(result.stdout), ["other/Other.kt"])

    def test_symlinks_are_skipped(self):
        self.track("src/Real.kt", "fun real() = 1\n")
        os.symlink("../../outside", self.root / "src" / "link")
        self.git("add", "src/link")
        self.commit()
        self.assertEqual(self.headers(self.audited(REVIEW, "src")), ["src/Real.kt"])

    def test_security_tree_holds_head_files_without_agent_config(self):
        self.track(".claude/agents/x.md", "agent\n")
        self.track("CLAUDE.md", "instructions\n")
        self.track("app/.env", "TOKEN=x\n")
        self.standard_repo()
        out = self.audited(SECURITY, "src")
        listing = out.split("Files the auditor can read:", 1)[1]
        files = dict(
            (path, digest)
            for digest, path in re.findall(r"(?m)^([0-9a-f]{64})  \./(.+)$", listing)
        )
        self.assertEqual(
            sorted(files),
            [".gitignore", "other/Other.kt", "src/Main.kt", "src/logo.png",
             "src/nested/Util.kt", "srcfoo/Near.kt"],
        )
        self.assertEqual(files["src/Main.kt"], hashlib.sha256(COMMITTED.encode()).hexdigest())


class PromptTest(RelayCase):
    def test_review_prompt_is_an_audit_of_existing_code(self):
        self.standard_repo()
        out = self.audited(REVIEW, "src")
        self.assertIn("not a diff", out)
        for word in ("crashes", "races", "resource leaks", "error handling"):
            self.assertIn(word, out)
        self.assertIn("REVIEWED <token>", out)
        self.assertRegex(out, r"<<<END UNTRUSTED CODE token=[0-9a-f]{32}>>>\s*$")

    def test_security_prompt_covers_the_audit_areas(self):
        self.standard_repo()
        out = self.audited(SECURITY, "src")
        self.assertIn("not a diff", out)
        for word in ("secrets", "host-key", "auth", "injection", "WebView", "exported",
                     "storage", "logging", "TLS", "supply chain"):
            self.assertIn(word, out)


class RefusalTest(RelayCase):
    def test_no_matching_tracked_file_exits_3(self):
        self.standard_repo()
        for script in (REVIEW, SECURITY):
            for path in ("missing", "src/Untracked.kt", "src/secret.properties", "src/Staged.kt"):
                result = self.relay(script, "--paths", path)
                self.assert_exit(result, 3)
                self.assertIn("no tracked file", result.stderr)

    def test_only_binary_files_exits_3(self):
        self.standard_repo()
        for script in (REVIEW, SECURITY):
            result = self.relay(script, "--paths", "src/logo.png")
            self.assert_exit(result, 3)
            self.assertIn("binary", result.stderr)

    def test_nul_anywhere_marks_a_file_binary(self):
        self.track("src/late.bin", "a" * 9000 + "\0" + "b\n")
        self.track("src/End.kt", "fun end() = 1\n" + "x" * 20000 + "\0")
        self.commit()
        for script in (REVIEW, SECURITY):
            result = self.relay(script, "--paths", "src")
            self.assert_exit(result, 3)
            self.assertIn("binary", result.stderr)

    def test_large_binary_is_skipped_without_loading_it(self):
        mib = 1 << 20
        big = self.root / "assets" / "huge.bin"
        big.parent.mkdir(parents=True)
        with open(big, "wb") as handle:
            for _ in range(160):
                handle.write(b"\0" * mib)
        self.git("add", "assets/huge.bin")
        self.track("assets/Small.kt", "fun small() = 1\n")
        self.commit()
        for script in (REVIEW, SECURITY):
            result = self.relay(script, "--print-input", "--paths", "assets", memory_limit=320 * mib)
            self.assert_exit(result, 0)
            self.assertEqual(self.headers(result.stdout.split("Files the auditor")[0]), ["assets/Small.kt"])

    def test_large_text_file_over_the_cap_exits_4(self):
        self.track("src/Huge.kt", "val x = 1\n" * (REVIEW_CAP // 5))
        self.commit()
        self.assert_exit(self.relay(REVIEW, "--paths", "src"), 4)

    def test_oversized_selection_exits_4_before_any_model(self):
        line = "val x = 1\n"
        self.track("big/A.kt", line * (SECURITY_CAP // len(line)))
        self.track("huge/B.kt", line * (REVIEW_CAP // len(line)))
        self.commit()
        for script, path in ((SECURITY, "big"), (REVIEW, "huge")):
            result = self.relay(script, "--paths", path)
            self.assert_exit(result, 4)
            self.assertIn("split", result.stderr)
        self.assert_exit(self.relay(REVIEW, "--print-input", "--paths", "big"), 0)

    def test_bad_arguments_exit_2(self):
        self.standard_repo()
        for script in (REVIEW, SECURITY):
            for arguments in (["--paths"], ["--paths", "--print-input"], ["--paths", "../x"],
                              ["--paths", "/etc"], ["--paths", "src/../../x"]):
                result = self.relay(script, *arguments)
                self.assert_exit(result, 2)

    def test_repo_without_commits_exits_2(self):
        self.write("src/A.kt", "fun a() = 1\n")
        self.assert_exit(self.relay(REVIEW, "--paths", "src"), 2)


class ExistingModesTest(RelayCase):
    def test_uncommitted_review_still_sends_the_patch_with_untracked_files(self):
        self.standard_repo()
        result = self.relay(REVIEW, "--print-input")
        self.assert_exit(result, 0)
        self.assertIn("<<<BEGIN UNTRUSTED CHANGE SET>>>", result.stdout)
        self.assertIn("+fun edited() = 2", result.stdout)
        self.assertIn("+fun untracked() = 5", result.stdout)
        self.assertIn("Review only the change set", result.stdout)

    def test_commit_review_still_works(self):
        self.track("A.kt", "fun a() = 1\n")
        self.commit()
        result = self.relay(REVIEW, "--print-input", "--commit", "HEAD")
        self.assert_exit(result, 0)
        self.assertIn("+fun a() = 1", result.stdout)

    def test_security_uncommitted_check_still_works(self):
        self.standard_repo()
        result = self.relay(SECURITY, "--print-input")
        self.assert_exit(result, 0)
        self.assertIn("Audit only the change set", result.stdout)
        self.assertIn("+fun edited() = 2", result.stdout)

    def test_security_without_glm_settings_still_fails_with_5(self):
        self.standard_repo()
        self.assert_exit(self.relay(SECURITY), 5)
        self.assert_exit(self.relay(SECURITY, "--paths", "src"), 5)

    def test_empty_change_set_still_exits_3(self):
        self.track("A.kt", "x\n")
        self.commit()
        self.assert_exit(self.relay(REVIEW), 3)


class RelayAgentTest(unittest.TestCase):
    def guard(self, script, command):
        return subprocess.run(
            ["bash", str(RELAYS / "wrapper-guard.sh"), script],
            input=json.dumps({"tool_input": {"command": command}}),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            universal_newlines=True,
        ).returncode

    def test_wrapper_guard_allows_the_audit_commands(self):
        self.assertEqual(self.guard(SECURITY, ".claude/scripts/run-security.sh --dir /w/t --paths core/ssh app"), 0)
        self.assertEqual(self.guard(REVIEW, ".claude/scripts/run-review.sh --dir /w/t --paths core/ssh/src"), 0)
        self.assertEqual(self.guard(SECURITY, ".claude/scripts/run-security.sh --paths a; rm -rf b"), 2)

    def test_security_agent_passes_an_optional_scope(self):
        text = (REPO_ROOT / ".claude" / "agents" / "security.md").read_text(encoding="utf-8")
        self.assertIn("`.claude/scripts/run-security.sh --dir <directory> [scope]`", text)
        self.assertIn("--paths", text)
        review = (REPO_ROOT / ".claude" / "agents" / "reviewer.md").read_text(encoding="utf-8")
        self.assertIn("--paths", review)


if __name__ == "__main__":
    unittest.main()
