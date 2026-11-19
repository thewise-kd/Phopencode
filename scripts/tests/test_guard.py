"""P15.6: `.claude/hooks/guard.py`, the PreToolUse guard for Bash, Monitor, Write and Edit.

Each test feeds the guard one tool call, the way the harness does, and checks whether it
allows the call (exit 0) or blocks it (exit 2). Repositories are temporary; nothing is pushed.
"""
import json
import subprocess
import sys

from repo_fixture import REPO_ROOT, GitRepoCase, clean_env

GUARD = REPO_ROOT / ".claude" / "hooks" / "guard.py"
KEY = "-----BEGIN OPENSSH PRIVATE KEY-----\n" + "A" * 70 + "\n-----END OPENSSH PRIVATE KEY-----\n"


class GuardCase(GitRepoCase):
    def setUp(self):
        super().setUp()
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "user.name", "Test")
        self.track("README.md", "readme\n")
        self.git("commit", "--quiet", "-m", "init")
        self.outside = self.base / "outside"
        self.outside.mkdir()

    def run_guard(self, command, cwd=None, tool="Bash"):
        event = {"tool_name": tool, "tool_input": {"command": command}, "cwd": str(cwd or self.root)}
        return subprocess.run(
            [sys.executable, str(GUARD)],
            input=json.dumps(event),
            env=clean_env(self.base),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            universal_newlines=True,
        )

    def assert_allowed(self, command, cwd=None):
        result = self.run_guard(command, cwd)
        self.assertEqual(result.returncode, 0, f"{command!r} was blocked: {result.stderr}")

    def assert_blocked(self, command, cwd=None, fragment=None):
        result = self.run_guard(command, cwd)
        self.assertEqual(result.returncode, 2, f"{command!r} was allowed")
        self.assertIn("Blocked by .claude/hooks/guard.py", result.stderr)
        self.assertNotIn("check itself failed", result.stderr)
        if fragment:
            self.assertIn(fragment, result.stderr)

    def stage_secret(self):
        self.track("app/.env", "TOKEN=x\n")


class HeredocTest(GuardCase):
    def test_unquoted_heredoc_body_runs_command_substitution(self):
        self.assert_blocked("cat <<EOF\n$(git push origin master)\nEOF\n", fragment="push")
        self.assert_blocked("cat <<EOF\nhello `git push` there\nEOF\n", fragment="push")

    def test_tab_stripping_heredoc_body_runs_command_substitution(self):
        self.assert_blocked("cat <<-EOF\n\tx $(git push) y\n\tEOF\n", fragment="push")

    def test_substitution_in_a_heredoc_inside_a_substitution(self):
        self.assert_blocked('echo "$(cat <<EOF\n$(git push)\nEOF\n)"', fragment="push")

    def test_heredoc_substitution_is_scanned_for_a_secret_commit(self):
        self.stage_secret()
        self.assert_blocked("cat <<EOF\n$(git commit -m x)\nEOF\n", fragment=".env")

    def test_quoted_heredoc_body_stays_literal(self):
        for opener in ("<<'EOF'", '<<"EOF"', "<<\\EOF", "<<-'EOF'", "<<E'O'F"):
            delimiter_line = "\tEOF" if "-" in opener else "EOF"
            self.assert_allowed(f"cat {opener}\n$(git push) `git push`\n{delimiter_line}\n")

    def test_escaped_substitution_in_an_unquoted_heredoc_stays_literal(self):
        self.assert_allowed("cat <<EOF\n\\$(git push) \\`git push\\`\nEOF\n")

    def test_plain_text_in_an_unquoted_heredoc_is_data(self):
        self.assert_allowed("git commit --allow-empty -F - <<EOF\nDo not git push from here.\n$HOME\nEOF\n")

    def test_command_after_a_heredoc_is_still_checked(self):
        self.assert_blocked("cat <<EOF\ntext\nEOF\ngit push\n", fragment="push")


class GitLocationTest(GuardCase):
    """git add and commit run only where `cd` and `git -C` lead; an overridden location is denied."""

    DENIED = "without overriding the repository location"

    def clean_repo(self):
        other = self.base / "other"
        other.mkdir()
        subprocess.run(["git", "init", "--quiet", str(other)], env=clean_env(self.base), check=True)
        return other

    def test_location_options_are_denied(self):
        git_dir = self.root / ".git"
        for command in (
            f"git --git-dir={git_dir} --work-tree={self.root} commit -m x",
            f"git --git-dir {git_dir} --work-tree {self.root} commit -m x",
            f"git --git-dir={git_dir} commit -m x",
            f"git --work-tree={self.root} commit -m x",
            f"git --git-dir={git_dir} --work-tree={self.root} add -A",
            "git --git-dir=$X commit -m x",
        ):
            self.assert_blocked(command, cwd=self.outside, fragment=self.DENIED)

    def test_location_variables_are_denied(self):
        git_dir = self.root / ".git"
        for command in (
            f"GIT_DIR={git_dir} GIT_WORK_TREE={self.root} git commit -m x",
            f"GIT_DIR={git_dir} git commit -m x",
            f"GIT_WORK_TREE={self.root} git add -A",
            "GIT_INDEX_FILE=/tmp/index git commit -a -m x",
            f"env GIT_DIR={git_dir} GIT_WORK_TREE={self.root} git commit -m x",
            f"export GIT_DIR={git_dir} GIT_WORK_TREE={self.root}; git commit -m x",
            f"GIT_DIR={git_dir} sh -c 'git commit -m x'",
            "GIT_WORK_TREE=$(pwd) git commit -m x",
        ):
            self.assert_blocked(command, cwd=self.outside, fragment=self.DENIED)

    def test_skipped_export_is_denied(self):
        """Round 3: `false && export ...` never runs, so a guard that followed it scanned the wrong index."""
        clean = self.clean_repo()
        self.stage_secret()
        self.assert_blocked(f"false && export GIT_DIR={clean / '.git'}; git commit -m x", fragment=self.DENIED)

    def test_index_file_from_a_subdirectory_is_denied(self):
        """Round 3: GIT_INDEX_FILE alone from a subdirectory must not hide a key committed with -a."""
        self.track("sub/notes.txt", "plain\n")
        self.git("commit", "--quiet", "-m", "notes")
        self.write("sub/notes.txt", KEY)
        self.assert_blocked(
            f"GIT_INDEX_FILE={self.root / '.git' / 'index'} git commit -a -m x",
            cwd=self.root / "sub",
            fragment=self.DENIED,
        )

    def test_location_overrides_without_add_or_commit_stay_allowed(self):
        git_dir = self.root / ".git"
        self.assert_allowed(f"GIT_DIR={git_dir} git log --oneline", cwd=self.outside)
        self.assert_allowed(f"git --git-dir={git_dir} status", cwd=self.outside)

    def test_cd_and_dash_c_reach_the_repository_to_scan(self):
        self.stage_secret()
        self.assert_blocked(f"git -C {self.root} commit -m x", cwd=self.outside, fragment=".env")
        self.assert_blocked(f"cd {self.root} && git commit -m x", cwd=self.outside, fragment=".env")
        self.write("local.properties", "sdk.dir=/x\n")
        self.assert_blocked("git -C ../repo add -A", cwd=self.outside, fragment="local.properties")


class UnmodeledLocationTest(GuardCase):
    """A git add or commit next to any change of git's location or config is denied."""

    UNMODELED = "without overriding the repository location"

    def clean_repo(self):
        other = self.base / "other"
        other.mkdir()
        subprocess.run(["git", "init", "--quiet", str(other)], env=clean_env(self.base), check=True)
        return other / ".git"

    def test_export_in_a_pipeline_segment_is_denied(self):
        clean = self.clean_repo()
        self.stage_secret()
        self.assert_blocked(f"true | export GIT_DIR={clean}; git commit -m x")
        self.assert_blocked(f"export GIT_DIR={clean} | true; git commit -m x")
        self.assert_blocked(f"export GIT_DIR={clean} & git commit -m x")

    def test_env_unset_is_denied(self):
        clean = self.clean_repo()
        self.stage_secret()
        self.assert_blocked(f"GIT_DIR={clean} env -u GIT_DIR git commit -m x")

    def test_unmodeled_location_changes_are_denied(self):
        for command in (
            "env -u GIT_DIR git commit -m x",
            "env -i git commit -m x",
            "env - git add README.md",
            "env --unset=GIT_INDEX_FILE git commit -m x",
            "sudo git commit -m x",
            "exec -c git commit -m x",
            "unset GIT_DIR; git commit -m x",
            "unset GIT_WORK_TREE && git add README.md",
            "git -c core.worktree=/tmp commit -m x",
            "git -c core.bare=true commit -m x",
            "git -c include.path=/tmp/x commit -m x",
            "git --config-env=core.worktree=V commit -m x",
            "git --bare commit -m x",
            "GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.worktree GIT_CONFIG_VALUE_0=/tmp git commit -m x",
            "export GIT_CONFIG_GLOBAL=/tmp/x; git commit -m x",
            "GIT_CEILING_DIRECTORIES=/ git commit -m x",
            "eval export GIT_DIR=/tmp/x; git commit -m x",
            "eval 'GIT_DIR=/tmp/x git commit -m x'",
            "export $V=/tmp/x; git commit -m x",
            "export -n GIT_DIR; git commit -m x",
            "GIT_DIR=/tmp/x; git commit -m x",
            "declare -x GIT_DIR=/tmp/x; git commit -m x",
            "printf -v GIT_DIR /tmp/x; git commit -m x",
            "read GIT_DIR < f; git commit -m x",
            ". ./env.sh; git commit -m x",
            "source env.sh && git add README.md",
            "git commit -m x; unset GIT_DIR",
        ):
            self.assert_blocked(command, fragment=self.UNMODELED)

    def test_harmless_forms_and_other_git_commands_stay_allowed(self):
        self.track("A.kt", "fun a() = 1\n")
        for command in (
            "GIT_AUTHOR_DATE=2020-01-01T00:00:00 GIT_COMMITTER_NAME=x git commit -m x",
            "export GIT_EDITOR=true; git commit -m x",
            f"cd {self.root} && git -C {self.root} commit -m x",
            "git status --short | cat; git commit -m x",
            "git commit -m x 2>&1 | tail -n 1",
            "git commit -m x &>/dev/null",
            "git commit -m x |& cat",
            "git add A.kt || true",
            "git -c user.name=x -c commit.gpgsign=false commit -m x",
            "env -u GIT_DIR git log --oneline",
            "unset GIT_DIR; git status",
            "sudo -n true; git status",
        ):
            self.assert_allowed(command)


class SubshellTest(GuardCase):
    def setUp(self):
        super().setUp()
        self.stage_secret()

    def test_cd_inside_a_subshell_does_not_move_the_guard(self):
        self.assert_blocked(f"(cd {self.outside}); git commit -m x", fragment=".env")
        self.assert_blocked(f"(cd {self.outside} && true) && git commit -m x", fragment=".env")
        self.assert_blocked(f"( (cd {self.outside}) ; cd {self.outside} ) ; git commit -m x", fragment=".env")

    def test_cd_inside_a_command_substitution_does_not_move_the_guard(self):
        self.assert_blocked(f"echo $(cd {self.outside}) `cd {self.outside}`; git commit -m x", fragment=".env")

    def test_cd_inside_sh_c_does_not_move_the_guard(self):
        self.assert_blocked(f"sh -c 'cd {self.outside}'; git commit -m x", fragment=".env")

    def test_cd_inside_a_subshell_applies_within_it(self):
        self.assert_blocked(f"cd {self.outside}; (cd {self.root} && git commit -m x)", fragment=".env")

    def test_cd_outside_a_subshell_still_persists(self):
        self.assert_blocked(f"cd {self.outside}; cd {self.root}; git commit -m x", fragment=".env")
        self.assert_allowed(f"cd {self.outside} && git commit -m x")
        self.assert_allowed(f"(cd {self.root}; cd {self.outside} && git commit -m x)")


class AllowedTodayTest(GuardCase):
    def test_everyday_commands_stay_allowed(self):
        self.track("A.kt", "fun a() = 1\n")
        for command in (
            "git status --short",
            "git log --oneline -3",
            "git diff HEAD",
            "./gradlew jvmCheck",
            "python3 -m unittest discover -s scripts/tests",
            "git add A.kt && git commit -m 'P1.1: add a'",
            "git commit -m \"$(cat <<'EOF'\nP1.1: add a\n\nNever git push here.\nEOF\n)\"",
            f"git -C {self.root} commit -m x",
            f"cd {self.root} && git commit -m x",
            "echo 'git push' | cat",
            "git cherry-pick --abort",
            "ls .env.example",
        ):
            self.assert_allowed(command, cwd=self.root)

    def test_known_blocks_stay(self):
        self.assert_blocked("git push", fragment="push")
        self.assert_blocked("bash -c 'git push origin'", fragment="push")
        self.assert_blocked("eval git push", fragment="push")
        self.assert_blocked("(git push)", fragment="push")
        self.assert_blocked("git add .env", fragment=".env")
        self.assert_blocked("git $SUB origin")
        self.stage_secret()
        self.assert_blocked("git commit -m x", fragment=".env")

    def test_write_of_a_secret_file_is_blocked(self):
        event = {"tool_name": "Write", "tool_input": {"file_path": str(self.root / ".env")}}
        result = subprocess.run(
            [sys.executable, str(GUARD)],
            input=json.dumps(event),
            env=clean_env(self.base),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            universal_newlines=True,
        )
        self.assertEqual(result.returncode, 2)


if __name__ == "__main__":
    import unittest

    unittest.main()
