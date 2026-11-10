"""P12.5: the release workflow signs the APK without leaking the key and publishes it on a v* tag.

The workflow is read with PyYAML when it is installed (GitHub's Ubuntu runners ship it). Without
it the tests that need the parsed workflow skip with the reason; the plain-text checks still run.
"""
import re
import unittest
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover - depends on the machine
    yaml = None

REPO_ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = REPO_ROOT / ".github" / "workflows" / "release.yml"
DOC = REPO_ROOT / "docs" / "dev-setup" / "release.md"

SECRETS = (
    "RELEASE_KEYSTORE_BASE64",
    "RELEASE_KEYSTORE_PASSWORD",
    "RELEASE_KEY_ALIAS",
    "RELEASE_KEY_PASSWORD",
)
SECRET_REF = re.compile(r"\$\{\{\s*secrets\.([A-Za-z0-9_]+)\s*\}\}")
PINNED = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40}$")
KEYSTORE_PATH = re.compile(r"\$RUNNER_TEMP/[A-Za-z0-9_.-]+\.(?:jks|keystore|p12)")
XTRACE = re.compile(r"\bset\s+(?:-[A-Za-z]*x|-o\s+xtrace)|\bbash\s+-[A-Za-z]*x")
# `apksigner sign`, also as "$TOOLS/apksigner" sign.
APKSIGNER = r"\bapksigner\"?\s+"
IPV4 = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")
NEEDS_YAML = "PyYAML is not installed; the parsed workflow checks need it"


def load_workflow():
    data = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
    # YAML 1.1 reads the bare key `on` as the boolean True.
    if True in data:
        data["on"] = data.pop(True)
    return data


def steps_of(job):
    return job.get("steps") or []


def run_of(step):
    return step.get("run") or ""


def all_steps(workflow):
    for job_id, job in workflow["jobs"].items():
        for index, step in enumerate(steps_of(job)):
            yield job_id, index, step


def walk(node, path=()):
    """Yields (path, string) for every string in the parsed workflow."""
    if isinstance(node, dict):
        for key, value in node.items():
            yield from walk(value, path + (key,))
    elif isinstance(node, list):
        for index, value in enumerate(node):
            yield from walk(value, path + (index,))
    elif isinstance(node, str):
        yield path, node


def secret_env_names(step):
    """The env names of a step whose values come from secrets."""
    return {
        name
        for name, value in (step.get("env") or {}).items()
        if isinstance(value, str) and SECRET_REF.search(value)
    }


def is_release_step(step):
    return re.search(r"\bgh\s+release\b", run_of(step)) is not None


class Files(unittest.TestCase):
    def test_the_workflow_and_the_guide_exist(self):
        self.assertTrue(WORKFLOW.is_file(), f"{WORKFLOW} is missing")
        self.assertTrue(DOC.is_file(), f"{DOC} is missing")


class PlainText(unittest.TestCase):
    """Checks that need no YAML parser."""

    def setUp(self):
        if not WORKFLOW.is_file():
            self.fail(f"{WORKFLOW} is missing")
        self.text = WORKFLOW.read_text(encoding="utf-8")

    def test_every_action_line_carries_a_version_comment(self):
        lines = [line for line in self.text.splitlines() if re.match(r"\s*-?\s*uses:", line)]
        self.assertTrue(lines, "the workflow uses no actions")
        for line in lines:
            with self.subTest(line=line.strip()):
                self.assertRegex(line, r"@[0-9a-f]{40}\s+#\s*v\d")

    def test_no_apksigner_password_is_given_on_the_command_line(self):
        self.assertNotRegex(self.text, r"--(?:ks|key)-pass\s+pass:")
        self.assertRegex(self.text, r"--ks-pass\s+(?:env|file):")
        self.assertRegex(self.text, r"--key-pass\s+(?:env|file):")

    def test_no_xtrace_anywhere(self):
        self.assertIsNone(XTRACE.search(self.text))

    def test_no_addresses_in_the_workflow_or_the_guide(self):
        for path in (WORKFLOW, DOC):
            with self.subTest(path=path.name):
                self.assertEqual(IPV4.findall(path.read_text(encoding="utf-8")), [])

    def test_the_workflow_never_creates_or_pushes_tags(self):
        self.assertNotRegex(self.text, r"\bgit\s+(?:tag|push)\b")


@unittest.skipIf(yaml is None, NEEDS_YAML)
class Trigger(unittest.TestCase):
    def setUp(self):
        self.workflow = load_workflow()

    def test_only_a_push_of_a_v_tag_starts_it(self):
        self.assertEqual(self.workflow["on"], {"push": {"tags": ["v*"]}})

    def test_no_pull_request_trigger_can_hand_secrets_to_a_fork(self):
        triggers = self.workflow["on"]
        for event in ("pull_request", "pull_request_target", "workflow_run", "workflow_dispatch"):
            with self.subTest(event=event):
                self.assertNotIn(event, triggers)


@unittest.skipIf(yaml is None, NEEDS_YAML)
class Permissions(unittest.TestCase):
    def setUp(self):
        self.workflow = load_workflow()

    def test_the_workflow_default_is_read_only_or_nothing(self):
        self.assertIn(self.workflow.get("permissions"), ({}, {"contents": "read"}))

    def test_only_the_job_that_makes_the_release_may_write(self):
        release_jobs = {
            job_id for job_id, _, step in all_steps(self.workflow) if is_release_step(step)
        }
        self.assertEqual(len(release_jobs), 1, "exactly one job publishes the release")
        for job_id, job in self.workflow["jobs"].items():
            permissions = job.get("permissions")
            with self.subTest(job=job_id):
                if job_id in release_jobs:
                    self.assertEqual(permissions, {"contents": "write"})
                else:
                    self.assertIn(permissions, (None, {}, {"contents": "read"}))

    def test_the_writing_job_never_sees_the_signing_secrets(self):
        for job_id, job in self.workflow["jobs"].items():
            if (job.get("permissions") or {}).get("contents") != "write":
                continue
            refs = set()
            for _, text in walk(job):
                refs.update(SECRET_REF.findall(text))
            with self.subTest(job=job_id):
                self.assertEqual(refs - {"GITHUB_TOKEN"}, set())

    def test_checkout_does_not_keep_the_token(self):
        for job_id, index, step in all_steps(self.workflow):
            if str(step.get("uses", "")).startswith("actions/checkout@"):
                with self.subTest(job=job_id, step=index):
                    self.assertIs((step.get("with") or {}).get("persist-credentials"), False)


@unittest.skipIf(yaml is None, NEEDS_YAML)
class Pinning(unittest.TestCase):
    def test_every_action_is_pinned_to_a_full_commit(self):
        uses = [step["uses"] for _, _, step in all_steps(load_workflow()) if "uses" in step]
        self.assertTrue(uses)
        for ref in uses:
            with self.subTest(uses=ref):
                self.assertRegex(ref, PINNED)


@unittest.skipIf(yaml is None, NEEDS_YAML)
class Secrets(unittest.TestCase):
    def setUp(self):
        self.workflow = load_workflow()

    def test_secrets_appear_only_as_whole_step_env_values(self):
        seen = set()
        for path, text in walk(self.workflow):
            names = SECRET_REF.findall(text)
            if not names:
                self.assertNotIn("secrets.", text, f"secret reference at {path}")
                continue
            with self.subTest(path=path):
                # jobs.<job>.steps.<n>.env.<NAME>
                self.assertEqual(len(path), 6)
                self.assertEqual((path[0], path[2], path[4]), ("jobs", "steps", "env"))
                self.assertRegex(text.strip(), r"^\$\{\{\s*secrets\.[A-Za-z0-9_]+\s*\}\}$")
            seen.update(names)
        self.assertTrue(set(SECRETS) <= seen, f"missing: {set(SECRETS) - seen}")

    def test_no_expression_is_spliced_into_a_shell_script(self):
        for job_id, index, step in all_steps(self.workflow):
            with self.subTest(job=job_id, step=index):
                self.assertNotIn("${{", run_of(step))

    def test_the_shell_is_bash_and_no_step_changes_it(self):
        self.assertEqual(self.workflow["defaults"]["run"]["shell"], "bash")
        for job_id, index, step in all_steps(self.workflow):
            with self.subTest(job=job_id, step=index):
                self.assertNotIn("shell", step)

    def test_no_secret_is_echoed_and_printf_only_feeds_base64(self):
        for job_id, index, step in all_steps(self.workflow):
            names = secret_env_names(step)
            for line in run_of(step).splitlines():
                mentioned = [n for n in names if re.search(r"\$\{?" + n + r"\b", line)]
                if not mentioned:
                    continue
                with self.subTest(job=job_id, step=index, line=line.strip()):
                    self.assertNotRegex(line, r"\becho\b")
                    if re.search(r"\bprintf\b", line):
                        self.assertRegex(line, r"\|\s*base64\s+(?:-d|--decode)\b")

    def test_gradle_never_runs_where_the_signing_secrets_are(self):
        for job_id, index, step in all_steps(self.workflow):
            if secret_env_names(step) & set(SECRETS):
                with self.subTest(job=job_id, step=index):
                    self.assertNotIn("gradlew", run_of(step))

    def test_a_missing_secret_fails_by_name(self):
        steps = [step for _, _, step in all_steps(self.workflow) if secret_env_names(step) & set(SECRETS)]
        self.assertTrue(steps)
        first = run_of(steps[0])
        for name in SECRETS:
            with self.subTest(secret=name):
                self.assertIn(name, first)
        self.assertRegex(first, r"\$\{!\w+(?::-)?\}|-z\s+\"\$\{?\w+")
        self.assertRegex(first, r"exit\s+1")


@unittest.skipIf(yaml is None, NEEDS_YAML)
class Signing(unittest.TestCase):
    def setUp(self):
        self.workflow = load_workflow()
        self.signing_job = None
        for job_id, job in self.workflow["jobs"].items():
            if any(re.search(APKSIGNER + "sign", run_of(step)) for step in steps_of(job)):
                self.signing_job = job
        self.assertIsNotNone(self.signing_job, "no job runs apksigner sign")
        self.steps = steps_of(self.signing_job)

    def index_of(self, pattern):
        for index, step in enumerate(self.steps):
            if re.search(pattern, run_of(step)):
                return index
        self.fail(f"no step runs {pattern!r}")

    def test_the_release_build_is_aligned_signed_verified_and_hashed_in_order(self):
        build = self.index_of(r":app:assembleRelease\b")
        align = self.index_of(r"\bzipalign\"?\s+-f\b")
        sign = self.index_of(APKSIGNER + "sign")
        verify = self.index_of(APKSIGNER + "verify")
        digest = self.index_of(r"\bsha256sum\b")
        self.assertLess(build, align)
        self.assertLessEqual(align, sign)
        self.assertLessEqual(sign, verify)
        self.assertLessEqual(verify, digest)

    def test_the_keystore_is_written_privately_and_removed_in_an_always_step(self):
        decode = next(
            (i for i, s in enumerate(self.steps) if "RELEASE_KEYSTORE_BASE64" in (s.get("env") or {})),
            None,
        )
        self.assertIsNotNone(decode, "no step receives the keystore")
        script = run_of(self.steps[decode])
        self.assertIn("umask 077", script)
        paths = set(KEYSTORE_PATH.findall(script))
        self.assertEqual(len(paths), 1, "the keystore goes to one file under $RUNNER_TEMP")
        keystore = paths.pop()

        cleanups = [
            i
            for i, s in enumerate(self.steps)
            if i > decode
            and "always()" in str(s.get("if", ""))
            and re.search(r"\brm\b[^\n]*" + re.escape(keystore), run_of(s))
        ]
        self.assertTrue(cleanups, "no if: always() step removes the keystore")

    def test_the_signing_job_only_reads(self):
        self.assertIn(self.signing_job.get("permissions"), (None, {}, {"contents": "read"}))


@unittest.skipIf(yaml is None, NEEDS_YAML)
class Release(unittest.TestCase):
    def setUp(self):
        self.workflow = load_workflow()
        found = [(job_id, step) for job_id, _, step in all_steps(self.workflow) if is_release_step(step)]
        self.assertEqual(len(found), 1, "one step publishes the release")
        self.job_id, self.step = found[0]
        self.script = run_of(self.step)

    def test_the_signed_apk_and_its_sha256_are_attached(self):
        self.assertRegex(self.script, r"\.apk\b")
        self.assertRegex(self.script, r"\.sha256\b")
        self.assertRegex(self.script, r"\bgh\s+release\s+(?:create|upload)\b")

    def test_the_release_uses_the_job_token(self):
        env = self.step.get("env") or {}
        self.assertRegex(str(env.get("GH_TOKEN", "")), r"secrets\.GITHUB_TOKEN")

    def test_a_new_release_requires_the_pushed_tag_to_exist(self):
        if re.search(r"\bgh\s+release\s+create\b", self.script):
            self.assertIn("--verify-tag", self.script)


class Guide(unittest.TestCase):
    def setUp(self):
        if not DOC.is_file():
            self.fail(f"{DOC} is missing")
        self.text = DOC.read_text(encoding="utf-8")

    def test_it_covers_the_key_the_secrets_the_tag_and_the_checks(self):
        for needle in (
            "keytool",
            "RSA",
            "4096",
            "base64",
            "gh secret set",
            "git tag",
            "sha256sum",
            "apksigner verify --print-certs",
            *SECRETS,
        ):
            with self.subTest(needle=needle):
                self.assertIn(needle, self.text)

    def test_the_key_files_always_have_their_full_path_outside_the_checkout(self):
        names = re.findall(r"(\S*)phopencode-release\.jks(?:\.b64)?", self.text)
        self.assertTrue(names, "the guide names no keystore file")
        for prefix in names:
            with self.subTest(prefix=prefix):
                self.assertTrue(prefix.endswith("~/phopencode-signing/"), prefix)
        self.assertNotRegex(self.text, r"(?m)^\s*cd\s")

    def test_secrets_are_set_for_a_named_repository_from_any_folder(self):
        commands = re.findall(r"gh secret set \S+[^\n]*", self.text)
        self.assertEqual(len(commands), 4)
        for command in commands:
            with self.subTest(command=command):
                self.assertIn("--repo", command)

    def test_the_base64_copy_is_deleted_after_use(self):
        self.assertRegex(
            self.text, r"(?:shred -u|rm(?: -P| -f)?) ~/phopencode-signing/phopencode-release\.jks\.b64"
        )

    def test_it_warns_about_the_backup(self):
        lowered = self.text.lower()
        self.assertIn("offline", lowered)
        self.assertIn("never commit", lowered)
        self.assertRegex(lowered, r"lose|lost")


if __name__ == "__main__":
    unittest.main()
