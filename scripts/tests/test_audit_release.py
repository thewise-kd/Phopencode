"""P12.8: the release audit over temporary git repositories.

Every fake secret, address and SDK name is assembled at runtime and written only to temporary
directories: this file is in the public repository, in check_boundaries' scan and in the audit's
own history scan.
"""
import importlib.util
import subprocess
import sys
import unittest

try:
    import tomllib
except ImportError:  # Python older than 3.11
    tomllib = None

from repo_fixture import REPO_ROOT, SCRIPTS, GitRepoCase, TempRepoCase, clean_env


def join(*parts):
    return "".join(parts)


MIXED = "aB3dE6gH9jK2mN5pQ8sT1vW4yZ7cF0hJ"  # 32 characters, no meaning

GITHUB_TOKEN = join("gh", "p_", MIXED, "abcd")
AWS_KEY = join("AK", "IA", "ABCDEFGH", "23456789")
NTFY_TOPIC = join("Zq8", "xT2vLm9", "Rk4wPj7Ns")
PUBLIC_ADDRESS = ".".join(["11", "22", "33", "44"])
DOC_ADDRESS = ".".join(["203", "0", "113", "7"])
HIGH_ENTROPY = join("q7Xv", "P2mK9", "zR4tW8", "nL3cY6b")


def key_block():
    dashes = "-" * 5
    body = "\n".join([MIXED + MIXED] * 3)
    kind = join("OPENSSH ", "PRIV", "ATE KEY")
    return f"{dashes}{join('BEG', 'IN')} {kind}{dashes}\n{body}\n{dashes}END {kind}{dashes}\n"


MIT_LICENSE = """MIT License

Copyright (c) 2026 Someone

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.
"""

LICENSE_ASSETS = "feature/settings/ui/src/main/assets/licenses"
BUNDLE = "core/codeview/src/main/assets/codeview"

NOTICES = f"""# Third-party notices

| Library | Version | License |
|---|---|---|
| `com.squareup.okhttp3:okhttp-android` | 5.5.1 | Apache-2.0 |
| `com.hierynomus:sshj` | 0.41.2 | Apache-2.0 |

Text: [`Apache-2.0.txt`]({LICENSE_ASSETS}/Apache-2.0.txt).

| Directory | Upstream | License |
|---|---|---|
| `grammars/c/` (C) | upstream | MIT |
"""

CATALOG = """[versions]
okhttp = "5.5.1"
sshj = "0.41.2"
turbine = "1.2.1"

[libraries]
android-gradle-plugin = { module = "com.android.tools.build:gradle", version = "9" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-mockwebserver3 = { module = "com.squareup.okhttp3:mockwebserver3", version.ref = "okhttp" }
sshj = { module = "com.hierynomus:sshj", version.ref = "sshj" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version = "1" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "compose-bom" }
unused-fallback = { module = "com.example.fallback:fallback", version = "1" }
"""

CONVENTIONS = """package conventions

internal fun DependencyHandler.addLibraries(configuration: String, catalog: VersionCatalog, vararg aliases: String) {
    aliases.forEach { add(configuration, catalog.library(it)) }
}

fun apply() {
    val visited = mutableSetOf<String>()
    visited.add("component")
    freeCompilerArgs.add("-Xjdk-release=17")
    val bom = dependencies.platform(libs.library("compose-bom"))
    dependencies.add("implementation", bom)
    dependencies.add("implementation", dependencies.platform(libs.library("compose-bom")))
    dependencies.addLibraries(
        "testImplementation",
        libs,
        "turbine",
    )
    listOf(":core:api-http", ":core:ssh")
        .forEach { dependencies.add("api", dependencies.project(mapOf("path" to it))) }
    dependencies.add("testImplementation", dependencies.project(mapOf("path" to ":core:testing")))
}
"""

NETWORK_CONFIG = """<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
    </domain-config>
</network-security-config>
"""

MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:networkSecurityConfig="@xml/network_security_config"
        android:label="@string/app_name">
    </application>
</manifest>
"""

KT = "src/main/kotlin/io/github/thewisekd/phopencode"
SETTINGS_LICENSES = f"feature/settings/ui/{KT}/feature/settings/ui/licenses/LicenseTexts.kt"
NOTIFY_SETUP = f"feature/notify/ui/{KT}/feature/notify/ui/setup/SetupScreen.kt"
MARKDOWN_LINKS = f"core/markdown/{KT}/core/markdown/LinkConfirmation.kt"
FEATURE_FILE = f"feature/files/ui/{KT}/feature/files/ui/Viewer.kt"
FEATURE_TEST = "feature/files/ui/src/test/kotlin/io/github/thewisekd/phopencode/ViewerTest.kt"
APP_FILE = f"app/{KT}/MainActivity.kt"


class AuditCase(GitRepoCase):
    script = "audit_release.py"

    def commit(self, message="change"):
        self.git(
            "-c", "user.name=Audit Test",
            "-c", "user.email=audit@example.com",
            "-c", "commit.gpgsign=false",
            "commit", "--quiet", "--allow-empty", "-m", message,
        )

    def head(self):
        result = subprocess.run(
            ["git", "-C", str(self.root), "rev-parse", "HEAD"],
            env=clean_env(self.base),
            stdout=subprocess.PIPE,
            universal_newlines=True,
            check=True,
        )
        return result.stdout.strip()

    def remove(self, path):
        self.git("rm", "--quiet", "--", path)

    def make_clean_repo(self):
        self.track("LICENSE", MIT_LICENSE)
        self.track("THIRD_PARTY_NOTICES.md", NOTICES)
        self.track(f"{LICENSE_ASSETS}/Apache-2.0.txt", "Apache License\nVersion 2.0\n")
        self.track(f"{LICENSE_ASSETS}/textmate/grammars/c/LICENSE", "MIT License (C grammar)\n")
        self.track(f"{BUNDLE}/NOTICE.md", "| `grammars/c/` | `c.tmLanguage.json` | MIT |\n")
        self.track(f"{BUNDLE}/grammars/c/LICENSE", "MIT License (C grammar)\n")
        self.track(f"{BUNDLE}/grammars/c/c.tmLanguage.json", '{"name": "C"}\n')
        self.track("gradle/libs.versions.toml", CATALOG)
        self.track(
            "core/api-http/build.gradle.kts",
            "dependencies {\n"
            "    implementation(libs.okhttp)\n"
            "    testImplementation(libs.okhttp.mockwebserver3)\n"
            "    testImplementation(project(\":core:testing\"))\n"
            "}\n",
        )
        self.track("core/ssh/build.gradle.kts", "dependencies {\n    implementation(libs.sshj)\n}\n")
        self.track(
            "core/testing/build.gradle.kts",
            "dependencies {\n    api(libs.coroutines.test)\n    api(libs.okhttp)\n}\n",
        )
        self.track("build-logic/src/main/kotlin/conventions/Conventions.kt", CONVENTIONS)
        self.track(
            "build-logic/build.gradle.kts",
            "dependencies {\n    implementation(libs.android.gradle.plugin)\n}\n",
        )
        self.track("app/src/main/AndroidManifest.xml", MANIFEST)
        self.track("app/src/main/res/xml/network_security_config.xml", NETWORK_CONFIG)
        self.track(
            f"core/api-http/{KT}/core/api_http/Http.kt",
            "package x\n\nimport okhttp3.OkHttpClient\n\nconst val BASE = \"http://127.0.0.1:4096\"\n",
        )
        self.track(f"core/ssh/{KT}/core/ssh/Ssh.kt", "package x\n\nimport net.schmizz.sshj.SSHClient\n")
        self.track(
            f"core/testing/{KT}/core/testing/FakeServer.kt",
            "package x\n\nimport java.net.ServerSocket\n",
        )
        self.track(
            SETTINGS_LICENSES,
            "package x\n\nconst val SOURCE = \"https://github.com/someone/grammar\"\n",
        )
        self.track(
            NOTIFY_SETUP,
            "package x\n\nimport androidx.compose.ui.platform.LocalUriHandler\n\n"
            "const val PAGE = \"https://docs.ntfy.sh/subscribe/phone/\"\n",
        )
        self.track(
            FEATURE_FILE,
            "package x\n\n// See https://developer.android.com/guide for details.\n"
            "const val SAMPLE = \"https://files.example.com/a\"\n"
            "fun url(host: String) = \"https://$host/\"\n",
        )
        self.track(
            "feature/notify/ui/src/main/res/values/strings.xml",
            '<resources xmlns:tools="http://schemas.android.com/tools">\n'
            '    <string name="hint">For example https://ntfy.example.com</string>\n'
            "</resources>\n",
        )
        self.track(
            "helper/notifyd/ntfy.py",
            "import urllib.request\n\nDEFAULT_SERVER = \"https://ntfy.sh\"\n",
        )
        self.track(
            "helper/notifyd/sse.py",
            "import http.client\nimport socket\n\nEXAMPLE = \"http://127.0.0.1:4096\"\n",
        )
        self.track("helper/notifyd/config.py", "from urllib.parse import urlsplit\n")
        self.track("helper/install.sh", "#!/bin/sh\nmkdir -p \"$HOME/.local/share\"\n")
        self.track(
            "helper/tests/test_ntfy.py",
            "import socket\n\nURL = \"https://elsewhere.example.org/x\"\n",
        )
        self.commit("Initial commit")


class CleanRepository(AuditCase):
    def test_a_clean_repository_passes(self):
        self.make_clean_repo()
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(result.stdout, "")


class CliContract(TempRepoCase):
    script = "audit_release.py"

    def test_outside_a_git_checkout_is_a_clear_error(self):
        result = self.check()
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn("git", result.stderr)
        self.assertNotIn("Traceback", result.stderr)

    def test_a_missing_root_is_a_clear_error(self):
        result = self.run_script("--root", str(self.base / "nowhere"))
        self.assertEqual(result.returncode, 2)
        self.assertNotIn("Traceback", result.stderr)


class ShallowClone(AuditCase):
    def test_a_shallow_clone_cannot_be_audited(self):
        self.make_clean_repo()
        self.commit("second")
        shallow = self.base / "shallow"
        subprocess.run(
            ["git", "clone", "--quiet", "--depth", "1", f"file://{self.root}", str(shallow)],
            env=clean_env(self.base),
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        result = self.run_script("--root", str(shallow))
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn("shallow", result.stderr)
        skipped = self.run_script("--root", str(shallow), "--skip-history")
        self.assertEqual(skipped.returncode, 0, skipped.stdout + skipped.stderr)


class UnreadableFiles(AuditCase):
    """A file the audit must read but cannot is the documented `cannot run` error, exit 2."""

    def setUp(self):
        super().setUp()
        self.make_clean_repo()

    def assert_cannot_run(self, path):
        target = self.root / path
        target.unlink()
        target.mkdir()  # still tracked, now unreadable as a file
        (target / "inner.txt").write_text("not a license\n", encoding="utf-8")
        result = self.run_script("--root", str(self.root), "--skip-history")
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertNotIn("Traceback", result.stderr)
        self.assertIn("cannot read", result.stderr)
        self.assertIn(path, result.stderr)
        self.assertEqual(len(result.stderr.strip().splitlines()), 1, result.stderr)
        self.assertNotIn("not a license", result.stdout + result.stderr)

    def test_an_unreadable_shipped_license_copy(self):
        self.assert_cannot_run(f"{LICENSE_ASSETS}/textmate/grammars/c/LICENSE")

    def test_an_unreadable_bundled_license(self):
        self.assert_cannot_run(f"{BUNDLE}/grammars/c/LICENSE")

    def test_an_unreadable_catalog(self):
        self.assert_cannot_run("gradle/libs.versions.toml")

    def test_an_unreadable_manifest(self):
        self.assert_cannot_run("app/src/main/AndroidManifest.xml")

    def test_an_unreadable_source_file(self):
        self.assert_cannot_run(FEATURE_FILE)


class MissingTrackedFiles(AuditCase):
    """A tracked file deleted from the working tree is reported, never read as empty."""

    def setUp(self):
        super().setUp()
        self.make_clean_repo()

    def delete(self, *paths):
        for path in paths:
            (self.root / path).unlink()
        return self.run_script("--root", str(self.root), "--skip-history")

    def test_both_sides_of_a_bundled_license_missing_are_reported(self):
        source = f"{BUNDLE}/grammars/c/LICENSE"
        copy = f"{LICENSE_ASSETS}/textmate/grammars/c/LICENSE"
        result = self.delete(source, copy)
        self.assert_problem(source, "tracked but missing", result)
        self.assert_problem(copy, "tracked but missing", result)

    def test_one_side_of_a_bundled_license_missing_is_reported(self):
        copy = f"{LICENSE_ASSETS}/textmate/grammars/c/LICENSE"
        result = self.delete(copy)
        self.assert_problem(copy, "tracked but missing", result)

    def test_other_missing_tracked_files_are_reported(self):
        paths = [
            f"{BUNDLE}/NOTICE.md",
            "helper/notifyd/config.py",
            FEATURE_FILE,
            "app/src/main/res/xml/network_security_config.xml",
        ]
        result = self.delete(*paths)
        for path in paths:
            self.assert_problem(path, "tracked but missing", result)


class RealRepository(unittest.TestCase):
    def test_the_working_tree_rules_pass_on_the_real_repository(self):
        result = subprocess.run(
            [sys.executable, str(REPO_ROOT / "scripts" / "audit_release.py"), "--skip-history"],
            cwd=str(REPO_ROOT),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            universal_newlines=True,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


class HistorySecrets(AuditCase):
    def setUp(self):
        super().setUp()
        self.make_clean_repo()

    def assert_never_printed(self, result, *values):
        for value in values:
            self.assertNotIn(value, result.stdout + result.stderr)

    def test_a_private_key_removed_later_is_still_found(self):
        self.track("docs/notes.txt", key_block())
        self.commit("add notes")
        commit = self.head()[:12]
        self.remove("docs/notes.txt")
        self.commit("remove notes")
        result = self.check()
        self.assert_problem(f"{commit}:docs/notes.txt:1", "private key", result)
        self.assert_never_printed(result, MIXED)

    def test_a_token_on_a_branch_other_than_head_is_found(self):
        self.git("checkout", "--quiet", "-b", "side")
        self.track("config/aws.txt", f"key {AWS_KEY}\n")
        self.commit("side work")
        commit = self.head()[:12]
        self.git("checkout", "--quiet", "-")
        result = self.check()
        self.assert_problem(f"{commit}:config/aws.txt:1", "AWS", result)
        self.assert_never_printed(result, AWS_KEY)

    def test_a_token_in_a_commit_message_is_found(self):
        self.commit(f"Fix the build\n\nused {GITHUB_TOKEN} to test")
        commit = self.head()[:12]
        result = self.check()
        self.assert_problem(f"{commit}:(commit message):3", "GitHub", result)
        self.assert_never_printed(result, GITHUB_TOKEN)

    def test_a_token_in_an_annotated_tag_message_is_found(self):
        self.git(
            "-c", "user.name=Audit Test", "-c", "user.email=audit@example.com",
            "-c", "tag.gpgsign=false", "tag", "-a", "v1", "-m", f"Release\n\nkey {AWS_KEY}",
        )
        result = self.check()
        self.assert_problem("refs/tags/v1:(tag message):3", "AWS", result)
        self.assert_never_printed(result, AWS_KEY)

    def test_a_tag_named_like_a_branch_is_still_read(self):
        self.git("branch", "side")
        self.git(
            "-c", "user.name=Audit Test", "-c", "user.email=audit@example.com",
            "-c", "tag.gpgsign=false", "tag", "-a", "side", "-m", f"Release\n\nkey {AWS_KEY}",
        )
        result = self.check()
        self.assert_problem("refs/tags/side:(tag message):3", "AWS", result)
        self.assert_never_printed(result, AWS_KEY)

    def test_a_blob_only_a_ref_names_is_scanned(self):
        target = self.write("loose.txt", f"key {AWS_KEY}\n")
        blob = subprocess.run(
            ["git", "-C", str(self.root), "hash-object", "-w", str(target)],
            env=clean_env(self.base),
            stdout=subprocess.PIPE,
            universal_newlines=True,
            check=True,
        ).stdout.strip()
        target.unlink()
        self.git("tag", "loose", blob)
        result = self.check()
        self.assert_problem("refs/tags/loose:(blob named by refs/tags/loose):1", "AWS", result)
        self.assert_never_printed(result, AWS_KEY)

    def rev(self, name):
        return subprocess.run(
            ["git", "-C", str(self.root), "rev-parse", name],
            env=clean_env(self.base),
            stdout=subprocess.PIPE,
            universal_newlines=True,
            check=True,
        ).stdout.strip()

    def test_a_commit_hidden_by_a_replace_ref_is_still_scanned(self):
        initial = self.head()
        self.track("docs/secret.txt", f"key {AWS_KEY}\n")
        self.commit("leak")
        leak = self.head()
        self.remove("docs/secret.txt")
        self.commit("cleanup")
        self.git("replace", leak, initial)  # history now reads as if the leak never happened
        result = self.check()
        self.assert_problem(f"{leak[:12]}:docs/secret.txt:1", "AWS", result)
        self.assert_never_printed(result, AWS_KEY)

    def test_a_replacement_object_is_scanned_as_a_ref(self):
        self.git("checkout", "--quiet", "-b", "temp")
        self.track("docs/secret.txt", f"key {AWS_KEY}\n")
        self.commit("replacement with a secret")
        replacement = self.head()
        self.git("checkout", "--quiet", "-")
        self.git("branch", "-D", "temp")
        self.commit("other")
        self.git("replace", self.head(), replacement)
        result = self.check()
        self.assert_problem(f"{replacement[:12]}:docs/secret.txt:1", "AWS", result)

    def test_a_grafts_file_is_reported(self):
        self.track("docs/secret.txt", f"key {AWS_KEY}\n")
        self.commit("leak")
        self.remove("docs/secret.txt")
        self.commit("cleanup")
        (self.root / ".git" / "info").mkdir(exist_ok=True)
        (self.root / ".git" / "info" / "grafts").write_text(self.head() + "\n", encoding="utf-8")
        result = self.check()
        self.assert_problem(".git/info/grafts", "grafts", result)
        self.assertIn("AWS", result.stdout)

    def test_keystore_files_ever_committed_are_found(self):
        for name in ("release.jks", "upload.keystore", "cert.p12"):
            self.track(f"app/{name}", b"\x00\x01binary")
        self.commit("add signing")
        commit = self.head()[:12]
        self.git("rm", "--quiet", "app/release.jks", "app/upload.keystore", "app/cert.p12")
        self.commit("remove signing")
        result = self.check()
        for name in ("release.jks", "upload.keystore", "cert.p12"):
            self.assert_problem(f"{commit}:app/{name}", "keystore", result)

    def test_local_properties_and_env_files_are_found(self):
        self.track("local.properties", "sdk.dir=/opt/android\n")
        self.track("helper/.env", "MODE=dev\n")
        self.commit("local files")
        commit = self.head()[:12]
        result = self.check()
        self.assert_problem(f"{commit}:local.properties", "local.properties", result)
        self.assert_problem(f"{commit}:helper/.env", ".env", result)

    def test_a_high_entropy_token_assignment_is_found(self):
        self.track("tools/run.sh", join("tok", "en=", HIGH_ENTROPY, "\n"))
        self.commit("script")
        commit = self.head()[:12]
        result = self.check()
        self.assert_problem(f"{commit}:tools/run.sh:1", "high-entropy", result)
        self.assert_never_printed(result, HIGH_ENTROPY)

    def test_low_entropy_and_placeholder_assignments_pass(self):
        self.track(
            "tools/run.sh",
            join("tok", "en=<value>\n", "tok", "en=$NTFY_TOKEN\n", "tok", "en = tokenizer.nextToken()\n"),
        )
        self.track(
            "core/ssh/src/main/kotlin/Keys.kt",
            join("val sec", "ret = Ed25519PrivateKey", "Parameters(random)\n"),
        )
        self.commit("script")
        self.assert_clean()

    def test_the_allowlist_covers_one_value_at_one_path(self):
        version = ".".join(["1", "2", "3", "4"])  # allowlisted at this path: a version string
        path = "scripts/tests/test_check_boundaries.py"
        self.track(path, f'CASES = ["version = {version}"]\n')
        self.commit("tests")
        self.assert_clean()
        self.track(path, f'CASES = ["version = {version}", "host {PUBLIC_ADDRESS}"]\n')
        self.commit("more tests")
        commit = self.head()[:12]
        result = self.check()
        self.assert_problem(f"{commit}:{path}:1", "public IP", result)
        self.track("docs/other.md", f"version = {version}\n")
        self.commit("elsewhere")
        commit = self.head()[:12]
        self.assert_problem(f"{commit}:docs/other.md:1", "public IP")

    def test_allowlisted_content_copied_to_another_path_is_reported_there(self):
        version = ".".join(["1", "2", "3", "4"])  # allowlisted only at test_check_boundaries.py
        content = f'CASES = ["version = {version}"]\n'
        self.track("scripts/tests/test_check_boundaries.py", content)
        self.commit("tests")
        self.track("docs/copy.py", content)  # the very same blob at a second path
        self.commit("copy")
        commit = self.head()[:12]
        result = self.check()
        self.assert_problem(f"{commit}:docs/copy.py:1", "public IP", result)
        self.assertNotIn("test_check_boundaries.py", result.stdout)

    def test_the_same_blob_at_a_second_path_is_reported_at_both(self):
        self.track("docs/a.txt", f"key {AWS_KEY}\n")
        self.commit("a")
        first = self.head()[:12]
        self.track("docs/b.txt", f"key {AWS_KEY}\n")
        self.commit("b")
        second = self.head()[:12]
        result = self.check()
        self.assert_problem(f"{first}:docs/a.txt:1", "AWS", result)
        self.assert_problem(f"{second}:docs/b.txt:1", "AWS", result)

    def test_an_ntfy_url_with_a_real_topic_is_found(self):
        self.track("docs/notify.md", join("Subscribe at https://ntfy", ".sh/", NTFY_TOPIC, "\n"))
        self.commit("docs")
        commit = self.head()[:12]
        result = self.check()
        self.assert_problem(f"{commit}:docs/notify.md:1", "ntfy", result)
        self.assert_never_printed(result, NTFY_TOPIC)

    def test_an_ntfy_subscription_link_with_a_real_topic_is_found(self):
        self.track("docs/notify.md", join("Open ntfy", "://ntfy.example.org/", NTFY_TOPIC, "\n"))
        self.commit("docs")
        commit = self.head()[:12]
        result = self.check()
        self.assert_problem(f"{commit}:docs/notify.md:1", "ntfy", result)
        self.assert_never_printed(result, NTFY_TOPIC)

    def test_a_public_ip_address_is_found(self):
        self.track("docs/setup.md", f"Connect to {PUBLIC_ADDRESS} with ssh.\n")
        self.commit("docs")
        commit = self.head()[:12]
        result = self.check()
        self.assert_problem(f"{commit}:docs/setup.md:1", "public IP", result)
        self.assert_never_printed(result, PUBLIC_ADDRESS)

    def test_tailscale_cgnat_addresses_are_found(self):
        low = ".".join(["100", "64", "0", "9"])
        high = ".".join(["100", "127", "250", "3"])
        self.track("docs/tailnet.md", f"Phone at {low}\nServer at {high}\n")
        self.commit("docs")
        commit = self.head()[:12]
        result = self.check()
        self.assert_problem(f"{commit}:docs/tailnet.md:1", "Tailscale/CGNAT", result)
        self.assert_problem(f"{commit}:docs/tailnet.md:2", "Tailscale/CGNAT", result)
        self.assert_never_printed(result, low, high)

    def test_a_network_written_as_its_base_cidr_passes(self):
        cgnat = ".".join(["100", "64", "0", "0"]) + "/" + "10"
        public = ".".join(["11", "22", "0", "0"]) + "/" + "16"
        self.track("tools/mask.py", f'NET = "{cgnat}"\nOTHER = "{public}"\n')
        self.commit("tools")
        self.assert_clean()

    def test_a_device_address_written_with_a_host_prefix_is_found(self):
        device = ".".join(["100", "101", "7", "9"])
        public = ".".join(["11", "22", "33", "0"])
        self.track("docs/tailnet.md", f"Phone {device}/32\nServer {public}/25\n")
        self.commit("docs")
        commit = self.head()[:12]
        result = self.check()
        self.assert_problem(f"{commit}:docs/tailnet.md:1", "Tailscale/CGNAT", result)
        self.assert_problem(f"{commit}:docs/tailnet.md:2", "public IP", result)
        self.assert_never_printed(result, device, public)

    def test_a_cidr_with_host_bits_set_is_found(self):
        device = ".".join(["100", "101", "7", "9"])
        public = ".".join(["11", "22", "33", "44"])
        self.track("docs/tailnet.md", f"Phone {device}/10\nServer {public}/16\n")
        self.commit("docs")
        commit = self.head()[:12]
        result = self.check()
        self.assert_problem(f"{commit}:docs/tailnet.md:1", "Tailscale/CGNAT", result)
        self.assert_problem(f"{commit}:docs/tailnet.md:2", "public IP", result)
        self.assert_never_printed(result, device, public)

    def test_addresses_just_outside_the_cgnat_range_are_not_cgnat(self):
        below = ".".join(["100", "63", "255", "254"])
        self.track("docs/tailnet.md", f"Gateway {below}\n")
        self.commit("docs")
        result = self.check()
        self.assertNotIn("Tailscale/CGNAT", result.stdout)
        self.assertIn("public IP", result.stdout)

    def test_documentation_loopback_and_private_addresses_and_versions_pass(self):
        self.track(
            "docs/setup.md",
            f"Use {DOC_ADDRESS}, 127.0.0.1, 10.0.0.5 or 192.168.1.20.\n"
            "Library 2.4.100 and grammar 1.13.2, protobuf v36.1.\n",
        )
        self.commit("docs")
        self.assert_clean()


class LicenseRules(AuditCase):
    def setUp(self):
        super().setUp()
        self.make_clean_repo()

    def test_a_missing_license_is_reported(self):
        self.remove("LICENSE")
        self.commit()
        self.assert_problem("LICENSE", "missing")

    def test_a_license_other_than_mit_is_reported(self):
        self.track("LICENSE", "Apache License\nVersion 2.0, January 2004\n")
        self.commit()
        self.assert_problem("LICENSE", "MIT")

    def test_missing_third_party_notices_are_reported(self):
        self.remove("THIRD_PARTY_NOTICES.md")
        self.commit()
        self.assert_problem("THIRD_PARTY_NOTICES.md", "missing")

    def test_a_shipped_library_missing_from_the_notices_is_reported(self):
        self.track(
            "gradle/libs.versions.toml",
            CATALOG + 'gson = { module = "com.google.code.gson:gson", version = "2" }\n',
        )
        self.track("core/ssh/build.gradle.kts", "dependencies {\n    implementation(libs.sshj)\n    api(libs.gson)\n}\n")
        self.commit()
        self.assert_problem("gradle/libs.versions.toml:15", "com.google.code.gson:gson")

    def test_a_library_added_by_a_convention_plugin_counts_as_shipped(self):
        self.track(
            "gradle/libs.versions.toml",
            CATALOG + 'gson = { module = "com.google.code.gson:gson", version = "2" }\n',
        )
        self.track(
            "build-logic/src/main/kotlin/conventions/More.kt",
            'fun more() {\n    dependencies.addLibraries(\n        "implementation",\n        libs,\n'
            '        "gson",\n    )\n}\n',
        )
        self.commit()
        self.assert_problem("gradle/libs.versions.toml:15", "com.google.code.gson:gson")

    def test_an_unclassifiable_catalog_reference_is_reported(self):
        self.track("core/ssh/build.gradle.kts", "dependencies {\n    implementation(libs.sshj)\n    val x = libs.okhttp\n}\n")
        self.commit()
        self.assert_problem("core/ssh/build.gradle.kts:3", "classify")

    def test_an_unknown_configuration_is_reported(self):
        self.track("core/ssh/build.gradle.kts", "dependencies {\n    implementation(libs.sshj)\n    bundled(libs.okhttp)\n}\n")
        self.commit()
        self.assert_problem("core/ssh/build.gradle.kts:3", "configuration")

    def test_a_library_shipped_through_a_bundle_is_checked(self):
        self.track(
            "gradle/libs.versions.toml",
            CATALOG
            + 'gson = { module = "com.google.code.gson:gson", version = "2" }\n'
            + '\n[bundles]\nnetwork = [\n    "sshj",\n    "gson",\n]\n',
        )
        self.track(
            "core/ssh/build.gradle.kts",
            "dependencies {\n    implementation(libs.sshj)\n    implementation(libs.bundles.network)\n}\n",
        )
        self.commit()
        self.assert_problem("gradle/libs.versions.toml:15", "com.google.code.gson:gson")

    def test_a_bundle_with_single_quoted_members_is_resolved(self):
        self.track(
            "gradle/libs.versions.toml",
            CATALOG
            + "gson = { module = 'com.google.code.gson:gson', version = '2' }  # single quotes\n"
            + "\n[bundles]\nnetwork = [\n    'sshj',  # the SSH client\n    'gson',\n]\n",
        )
        self.track(
            "core/ssh/build.gradle.kts",
            "dependencies {\n    implementation(libs.sshj)\n    implementation(libs.bundles.network)\n}\n",
        )
        self.commit()
        self.assert_problem("gradle/libs.versions.toml:15", "com.google.code.gson:gson")

    def test_an_empty_bundle_is_reported(self):
        self.track("gradle/libs.versions.toml", CATALOG + "\n[bundles]\nnothing = [ ]\n")
        self.commit()
        self.assert_problem("gradle/libs.versions.toml:17", "empty bundle")

    def test_an_invalid_catalog_is_reported(self):
        self.track("gradle/libs.versions.toml", CATALOG + "broken = { module = \n")
        self.commit()
        self.assert_problem("gradle/libs.versions.toml", "TOML")

    def test_a_bundle_used_only_in_tests_does_not_ship(self):
        self.track(
            "gradle/libs.versions.toml",
            CATALOG
            + 'gson = { module = "com.google.code.gson:gson", version = "2" }\n'
            + '\n[bundles]\ntesting = ["gson", "turbine"]\n',
        )
        self.track(
            "core/ssh/build.gradle.kts",
            "dependencies {\n    implementation(libs.sshj)\n    testImplementation(libs.bundles.testing)\n}\n",
        )
        self.commit()
        self.assert_clean()

    def test_an_unknown_bundle_is_reported(self):
        self.track(
            "core/ssh/build.gradle.kts",
            "dependencies {\n    implementation(libs.sshj)\n    implementation(libs.bundles.missing)\n}\n",
        )
        self.commit()
        self.assert_problem("core/ssh/build.gradle.kts:3", "bundle")

    def test_an_unknown_library_alias_is_reported(self):
        self.track(
            "core/ssh/build.gradle.kts",
            "dependencies {\n    implementation(libs.sshj)\n    implementation(libs.not.in.catalog)\n}\n",
        )
        self.commit()
        self.assert_problem("core/ssh/build.gradle.kts:3", "not in the catalog")

    def with_gson(self):
        self.track(
            "gradle/libs.versions.toml",
            CATALOG + 'gson = { module = "com.google.code.gson:gson", version = "2" }\n',
        )

    def test_groovy_catalog_references_are_checked(self):
        self.with_gson()
        self.track("feature/files/ui/build.gradle", "dependencies {\n    implementation libs.gson\n}\n")
        self.commit()
        self.assert_problem("gradle/libs.versions.toml:15", "com.google.code.gson:gson")

    def test_groovy_parenthesised_and_platform_references_are_checked(self):
        self.with_gson()
        self.track(
            "feature/files/ui/build.gradle",
            "dependencies {\n    implementation platform(libs.compose.bom)\n    api(libs.gson)\n}\n",
        )
        self.commit()
        self.assert_problem("gradle/libs.versions.toml:15", "com.google.code.gson:gson")

    def test_a_groovy_script_in_build_logic_is_checked(self):
        self.with_gson()
        self.track("build-logic/src/main/groovy/conventions.gradle", "dependencies {\n    implementation libs.gson\n}\n")
        self.commit()
        self.assert_problem("gradle/libs.versions.toml:15", "com.google.code.gson:gson")

    def test_a_direct_coordinate_dependency_is_checked(self):
        self.track(
            "feature/files/ui/build.gradle",
            "dependencies {\n    implementation 'com.example.direct:direct-lib:1.0'\n"
            "    testImplementation 'junit:junit:4.13'\n}\n",
        )
        self.track(
            "feature/git/ui/build.gradle.kts",
            'dependencies {\n    implementation("com.example.other:other-lib:2.0")\n}\n',
        )
        self.track(
            "feature/chat/ui/build.gradle",
            "dependencies {\n    implementation group: 'com.example.mapped', name: 'mapped-lib', version: '1'\n}\n",
        )
        self.commit()
        result = self.check()
        self.assert_problem("feature/files/ui/build.gradle:2", "com.example.direct:direct-lib", result)
        self.assert_problem("feature/git/ui/build.gradle.kts:2", "com.example.other:other-lib", result)
        self.assert_problem("feature/chat/ui/build.gradle:2", "com.example.mapped:mapped-lib", result)
        self.assertNotIn("junit:junit", result.stdout)

    def test_a_listed_direct_coordinate_passes(self):
        self.track(
            "feature/files/ui/build.gradle",
            "dependencies {\n    implementation 'com.hierynomus:sshj:0.41.2'\n}\n",
        )
        self.commit()
        self.assert_clean()

    def test_a_local_file_dependency_is_reported(self):
        self.track(
            "feature/files/ui/build.gradle",
            "dependencies {\n    implementation files('libs/vendor.jar')\n}\n",
        )
        self.commit()
        self.assert_problem("feature/files/ui/build.gradle:2", "local file dependency")

    def test_groovy_test_support_project_dependencies_are_checked(self):
        self.track(
            "feature/files/ui/build.gradle",
            "dependencies {\n    implementation project(':core:testing')\n"
            "    api project(path: ':core:testing')\n    testImplementation project(':core:testing')\n}\n",
        )
        self.commit()
        result = self.check()
        self.assert_problem("feature/files/ui/build.gradle:2", "test-support module", result)
        self.assert_problem("feature/files/ui/build.gradle:3", "test-support module", result)
        self.assertNotIn("feature/files/ui/build.gradle:4", result.stdout)

    def test_an_unknown_groovy_configuration_is_reported(self):
        self.track("feature/files/ui/build.gradle", "dependencies {\n    bundled libs.okhttp\n}\n")
        self.commit()
        self.assert_problem("feature/files/ui/build.gradle:2", "configuration")

    def test_an_unclassifiable_groovy_catalog_reference_is_reported(self):
        self.track("feature/files/ui/build.gradle", "def x = libs.okhttp\n")
        self.commit()
        self.assert_problem("feature/files/ui/build.gradle:1", "classify")

    def test_a_license_text_only_mentioned_is_reported(self):
        self.track(f"{LICENSE_ASSETS}/MIT-Other.txt", "MIT License\n")
        self.track("THIRD_PARTY_NOTICES.md", NOTICES + "\nThe MIT text is MIT-Other.txt in the licenses folder.\n")
        self.commit()
        self.assert_problem(f"{LICENSE_ASSETS}/MIT-Other.txt", "THIRD_PARTY_NOTICES.md")

    def test_a_reference_style_license_link_passes(self):
        self.track(f"{LICENSE_ASSETS}/MIT-Other.txt", "MIT License\n")
        self.track(
            "THIRD_PARTY_NOTICES.md",
            NOTICES + f"\nSee [the MIT text][mit].\n\n[mit]: {LICENSE_ASSETS}/MIT-Other.txt\n",
        )
        self.commit()
        self.assert_clean()

    def test_a_link_to_a_missing_license_text_is_reported(self):
        self.track("THIRD_PARTY_NOTICES.md", NOTICES + f"\n[`Gone.txt`]({LICENSE_ASSETS}/Gone.txt)\n")
        self.commit()
        self.assert_problem("THIRD_PARTY_NOTICES.md", "Gone.txt")

    def test_dependency_handler_add_forms_in_a_convention_plugin_are_checked(self):
        self.with_gson()
        self.track(
            "build-logic/src/main/kotlin/conventions/More.kt",
            "fun more() {\n"
            '    dependencies.add("implementation", "com.example.direct:direct-lib:1")\n'
            '    dependencies.add("implementation", libs.findLibrary("gson").get())\n'
            "}\n",
        )
        self.commit()
        result = self.check()
        self.assert_problem(
            "build-logic/src/main/kotlin/conventions/More.kt:2", "com.example.direct:direct-lib", result
        )
        self.assert_problem("gradle/libs.versions.toml:15", "com.google.code.gson:gson", result)

    def test_string_configuration_calls_in_a_convention_plugin_are_checked(self):
        self.with_gson()
        self.track(
            "build-logic/src/main/kotlin/conventions/More.kt",
            "fun more() {\n    dependencies {\n"
            '        "implementation"(libs.findLibrary("gson").get())\n'
            '        "implementation"("com.example.direct:direct-lib:1")\n'
            "    }\n}\n",
        )
        self.commit()
        result = self.check()
        self.assert_problem("gradle/libs.versions.toml:15", "com.google.code.gson:gson", result)
        self.assert_problem(
            "build-logic/src/main/kotlin/conventions/More.kt:4", "com.example.direct:direct-lib", result
        )

    def test_add_and_string_configuration_forms_in_build_scripts_are_checked(self):
        self.with_gson()
        self.track(
            "feature/files/ui/build.gradle.kts",
            "dependencies {\n"
            '    "implementation"(libs.gson)\n'
            '    "implementation"("com.example.other:other-lib:2")\n'
            '    add("implementation", "com.example.added:added-lib:3")\n'
            '    "implementation"(project(":core:testing"))\n'
            "}\n",
        )
        self.track(
            "feature/git/ui/build.gradle",
            "dependencies {\n    add('implementation', libs.findLibrary('gson').get())\n}\n",
        )
        self.commit()
        result = self.check()
        self.assert_problem("gradle/libs.versions.toml:15", "com.google.code.gson:gson", result)
        self.assert_problem("feature/files/ui/build.gradle.kts:3", "com.example.other:other-lib", result)
        self.assert_problem("feature/files/ui/build.gradle.kts:4", "com.example.added:added-lib", result)
        self.assert_problem("feature/files/ui/build.gradle.kts:5", "test-support module", result)

    def test_dependency_calls_the_audit_cannot_classify_are_reported(self):
        self.track(
            "build-logic/src/main/kotlin/conventions/More.kt",
            "fun more(extra: Any) {\n"
            '    dependencies.add("implementation", extra)\n'
            '    dependencies.add(pickConfiguration(), libs.library("sshj"))\n'
            '    dependencies { "implementation"(somethingElse()) }\n'
            "}\n",
        )
        self.track(
            "feature/files/ui/build.gradle.kts",
            'dependencies {\n    "implementation"(extraDependency)\n    add("api", extraDependency)\n}\n',
        )
        self.commit()
        result = self.check()
        for location in (
            "build-logic/src/main/kotlin/conventions/More.kt:2",
            "build-logic/src/main/kotlin/conventions/More.kt:3",
            "build-logic/src/main/kotlin/conventions/More.kt:4",
            "feature/files/ui/build.gradle.kts:2",
            "feature/files/ui/build.gradle.kts:3",
        ):
            self.assert_problem(location, "classify", result)

    def test_a_configuration_call_with_an_unknown_argument_is_reported(self):
        self.track(
            "feature/files/ui/build.gradle.kts",
            "dependencies {\n    implementation(extraDependency)\n    api(other)\n"
            "    testImplementation(gradleTestKit())\n"
            "}\nconfigurations {\n    implementation {\n        exclude(group = \"x\")\n    }\n}\n",
        )
        self.track(
            "feature/git/ui/build.gradle",
            "dependencies {\n    implementation dependencies.create('com.example:x:1')\n}\n",
        )
        self.commit()
        result = self.check()
        self.assert_problem("feature/files/ui/build.gradle.kts:2", "classify", result)
        self.assert_problem("feature/files/ui/build.gradle.kts:3", "classify", result)
        self.assert_problem("feature/git/ui/build.gradle:2", "classify", result)
        self.assertNotIn("feature/files/ui/build.gradle.kts:4", result.stdout)  # a test configuration
        self.assertNotIn("feature/files/ui/build.gradle.kts:7", result.stdout)  # a configuration block

    def test_groovy_block_comments_inside_an_interpolation_do_not_nest(self):
        tracking = join("com.google.fire", "base:fire", "base-analytics:1")
        self.track(
            "feature/files/ui/build.gradle",
            'def v = "${1 /* /* */ }"\n'
            "// removed: implementation(libs.removed.alias), see https://collector.tracking-host.net/\n"
            "dependencies {\n"
            "    implementation 'com.example.direct:direct-lib:1'\n"
            f"    implementation '{tracking}'\n"
            "}\n",
        )
        self.commit()
        result = self.check()
        self.assertNotIn("feature/files/ui/build.gradle:2:", result.stdout)
        self.assert_problem("feature/files/ui/build.gradle:4", "com.example.direct:direct-lib", result)
        self.assert_problem("feature/files/ui/build.gradle:5", "analytics", result)

    def test_a_test_support_module_used_outside_tests_is_reported(self):
        self.track(
            "core/ssh/build.gradle.kts",
            "dependencies {\n    implementation(libs.sshj)\n    implementation(project(\":core:testing\"))\n}\n",
        )
        self.commit()
        self.assert_problem("core/ssh/build.gradle.kts:3", "test-support module")

    def test_a_bundled_directory_without_a_license_is_reported(self):
        self.track(f"{BUNDLE}/grammars/go/go.tmLanguage.json", "{}\n")
        self.commit()
        self.assert_problem(f"{BUNDLE}/grammars/go/", "LICENSE")

    def test_a_bundled_directory_missing_from_the_records_is_reported(self):
        self.track(f"{BUNDLE}/grammars/go/go.tmLanguage.json", "{}\n")
        self.track(f"{BUNDLE}/grammars/go/LICENSE", "MIT License (Go grammar)\n")
        self.track(f"{LICENSE_ASSETS}/textmate/grammars/go/LICENSE", "MIT License (Go grammar)\n")
        self.commit()
        result = self.check()
        self.assert_problem(f"{BUNDLE}/grammars/go/", "NOTICE.md", result)
        self.assert_problem(f"{BUNDLE}/grammars/go/", "THIRD_PARTY_NOTICES.md", result)

    def test_a_bundled_license_not_shipped_in_the_app_is_reported(self):
        self.track(f"{LICENSE_ASSETS}/textmate/grammars/c/LICENSE", "something else\n")
        self.track(f"{BUNDLE}/grammars/c/LICENSE.vscode", "MIT License (VS Code)\n")
        self.commit()
        result = self.check()
        self.assert_problem(f"{BUNDLE}/grammars/c/LICENSE", "differs", result)
        self.assert_problem(f"{BUNDLE}/grammars/c/LICENSE.vscode", "not shipped", result)

    def test_a_linked_license_text_that_is_missing_is_reported(self):
        self.remove(f"{LICENSE_ASSETS}/Apache-2.0.txt")
        self.commit()
        self.assert_problem("THIRD_PARTY_NOTICES.md", "Apache-2.0.txt")

    def test_a_license_text_not_listed_in_the_notices_is_reported(self):
        self.track(f"{LICENSE_ASSETS}/MIT-Other.txt", "MIT License\n")
        self.commit()
        self.assert_problem(f"{LICENSE_ASSETS}/MIT-Other.txt", "THIRD_PARTY_NOTICES.md")

    def test_a_stray_third_party_license_file_is_reported(self):
        self.track("app/src/main/vendor/LICENSE", "BSD License\n")
        self.commit()
        self.assert_problem("app/src/main/vendor/LICENSE", "license file")


class AnalyticsRules(AuditCase):
    def setUp(self):
        super().setUp()
        self.make_clean_repo()

    def test_an_analytics_library_in_the_catalog_is_reported(self):
        group = join("com.google", ".fire", "base")
        self.track(
            "gradle/libs.versions.toml",
            CATALOG + f'tracking = {{ module = "{group}:{join("fire", "base-analytics")}", version = "1" }}\n',
        )
        self.commit()
        self.assert_problem("gradle/libs.versions.toml:15", "analytics")

    def test_variants_of_denied_play_services_artifacts_are_reported(self):
        group = join("com.google.android", ".gms")
        lines = [
            f'ads = {{ module = "{group}:{join("play-services-", "ads-lite")}", version = "1" }}',
            f'measure = {{ module = "{group}:{join("play-services-", "measurement-api")}", version = "1" }}',
            f'base = {{ group = "{group}", name = "{join("play-services-", "ads-base")}", version = "1" }}',
        ]
        self.track("gradle/libs.versions.toml", CATALOG + "\n".join(lines) + "\n")
        self.commit()
        result = self.check()
        for line in (15, 16, 17):
            self.assert_problem(f"gradle/libs.versions.toml:{line}", "analytics", result)

    def test_single_quoted_catalog_fields_are_checked(self):
        group = join("com.google.android", ".gms")
        lines = [
            f"ads = {{ group = '{group}', name = '{join('play-services-', 'ads-lite')}', version = '1' }}",
            f"measure = {{ module = 'com.example.relocated:{join('play-services-', 'measurement-api')}', version = '1' }}",
            "",
            "[plugins]",
            f"crash = {{ id = '{join('com.google.fire', 'base.crashlytics')}', version = '1' }}",
        ]
        self.track("gradle/libs.versions.toml", CATALOG + "\n".join(lines) + "\n")
        self.commit()
        result = self.check()
        for line in (15, 16, 19):
            self.assert_problem(f"gradle/libs.versions.toml:{line}", "analytics", result)

    def test_a_single_quoted_groovy_name_field_is_checked(self):
        self.track(
            "feature/files/ui/build.gradle",
            "dependencies {\n    implementation group: 'com.example.relocated', "
            f"name: '{join('play-services-', 'ads-lite')}', version: '1'\n}}\n",
        )
        self.commit()
        self.assert_problem("feature/files/ui/build.gradle:2", "analytics")

    def test_harmless_play_services_artifacts_pass(self):
        group = join("com.google.android", ".gms")
        self.track(
            "gradle/libs.versions.toml",
            CATALOG + f'auth = {{ module = "{group}:play-services-auth", version = "1" }}\n',
        )
        self.commit()
        self.assert_clean()

    def test_a_crash_reporting_plugin_in_a_build_file_is_reported(self):
        plugin = join("io.sen", "try.android.gradle")
        self.track("app/build.gradle.kts", f'plugins {{\n    id("{plugin}")\n}}\n')
        self.commit()
        self.assert_problem("app/build.gradle.kts:2", "analytics")

    def test_an_analytics_import_in_source_is_reported(self):
        package = join("com.mix", "panel.android.mpmetrics")
        self.track(APP_FILE, f"package x\n\nimport {package}.MixpanelAPI\n")
        self.track(FEATURE_TEST, f"package x\n\nimport {join('com.amp', 'litude')}.android.Amplitude\n")
        self.commit()
        result = self.check()
        self.assert_problem(f"{APP_FILE}:3", "analytics", result)
        self.assert_problem(f"{FEATURE_TEST}:3", "analytics", result)

    def test_an_sdk_loaded_by_name_in_a_string_is_reported(self):
        name = join("io.sen", "try.Sentry")
        self.track(APP_FILE, f'package x\n\nval c = Class.forName("{name}")\n// {name} in a comment\n')
        self.commit()
        result = self.check()
        self.assert_problem(f"{APP_FILE}:3", "analytics", result)
        self.assertNotIn(f"{APP_FILE}:4", result.stdout)

    def test_every_advertising_permission_is_reported(self):
        services = join("android.permission.ACCESS_AD", "SERVICES_")
        permissions = [
            join("com.google.android.gms", ".permission.AD", "_ID"),
            services + "AD_ID",
            services + "ATTRIBUTION",
            services + "TOPICS",
            services + "CUSTOM_AUDIENCE",
        ]
        body = "".join(f'    <uses-permission android:name="{p}" />\n' for p in permissions)
        self.track(
            "feature/files/ui/src/main/AndroidManifest.xml",
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n' + body + "</manifest>\n",
        )
        self.commit()
        result = self.check()
        for line in range(2, 2 + len(permissions)):
            self.assert_problem(f"feature/files/ui/src/main/AndroidManifest.xml:{line}", "advertising", result)

    def test_an_analytics_import_in_python_is_reported(self):
        self.track("helper/notifyd/rules.py", f"import {join('sentry', '_sdk')}\n")
        self.commit()
        self.assert_problem("helper/notifyd/rules.py:1", "analytics")

    def test_the_advertising_id_permission_is_reported(self):
        permission = join("com.google.android.gms", ".permission.AD", "_ID")
        self.track(
            "feature/files/ui/src/main/AndroidManifest.xml",
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n'
            f'    <uses-permission android:name="{permission}" />\n</manifest>\n',
        )
        self.commit()
        self.assert_problem("feature/files/ui/src/main/AndroidManifest.xml:2", "advertising")


class NetworkRules(AuditCase):
    def setUp(self):
        super().setUp()
        self.make_clean_repo()

    def test_cleartext_by_default_is_reported(self):
        self.track(
            "app/src/main/res/xml/network_security_config.xml",
            NETWORK_CONFIG.replace('base-config cleartextTrafficPermitted="false"', "base-config"),
        )
        self.commit()
        self.assert_problem("app/src/main/res/xml/network_security_config.xml", "cleartext")

    def test_cleartext_to_another_domain_is_reported(self):
        self.track(
            "app/src/main/res/xml/network_security_config.xml",
            NETWORK_CONFIG.replace("127.0.0.1</domain>", "127.0.0.1</domain>\n<domain>localhost</domain>"),
        )
        self.commit()
        self.assert_problem("app/src/main/res/xml/network_security_config.xml", "cleartext")

    def test_a_missing_network_security_config_is_reported(self):
        self.remove("app/src/main/res/xml/network_security_config.xml")
        self.commit()
        self.assert_problem("app/src/main/AndroidManifest.xml:4", "does not resolve")

    def test_a_manifest_pointing_at_a_permissive_config_is_reported(self):
        self.track(
            "app/src/main/AndroidManifest.xml",
            MANIFEST.replace("@xml/network_security_config", "@xml/open_config"),
        )
        self.track(
            "app/src/main/res/xml/open_config.xml",
            '<network-security-config>\n    <base-config cleartextTrafficPermitted="true" />\n'
            "</network-security-config>\n",
        )
        self.commit()
        self.assert_problem("app/src/main/res/xml/open_config.xml", "cleartext")

    def test_a_library_manifest_with_its_own_config_is_reported(self):
        self.track(
            "feature/files/ui/src/main/AndroidManifest.xml",
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <application android:networkSecurityConfig="@xml/files_config" />\n</manifest>\n',
        )
        self.track(
            "feature/files/ui/src/main/res/xml/files_config.xml",
            '<network-security-config>\n    <domain-config cleartextTrafficPermitted="true">\n'
            "        <domain>files.example.org</domain>\n    </domain-config>\n</network-security-config>\n",
        )
        self.commit()
        result = self.check()
        self.assert_problem("feature/files/ui/src/main/res/xml/files_config.xml", "cleartext", result)
        self.assertIn("more than one network security config", result.stdout)

    def test_an_overriding_config_in_another_source_set_is_audited(self):
        self.track(
            "app/src/release/res/xml/network_security_config.xml",
            '<network-security-config>\n    <base-config cleartextTrafficPermitted="true" />\n'
            "</network-security-config>\n",
        )
        self.commit()
        self.assert_problem("app/src/release/res/xml/network_security_config.xml", "cleartext")

    def test_an_unresolvable_config_reference_is_reported(self):
        self.track(
            "app/src/main/AndroidManifest.xml",
            MANIFEST.replace("@xml/network_security_config", "${securityConfig}"),
        )
        self.commit()
        self.assert_problem("app/src/main/AndroidManifest.xml:4", "does not resolve")

    def test_a_single_quoted_config_reference_is_followed(self):
        self.track(
            "app/src/main/AndroidManifest.xml",
            MANIFEST.replace('android:networkSecurityConfig="@xml/network_security_config"',
                             "android:networkSecurityConfig = '@xml/open_config'"),
        )
        self.track(
            "app/src/main/res/xml/open_config.xml",
            "<network-security-config>\n    <base-config cleartextTrafficPermitted='true' />\n"
            "</network-security-config>\n",
        )
        self.commit()
        self.assert_problem("app/src/main/res/xml/open_config.xml", "cleartext")

    def test_a_config_reference_under_another_namespace_prefix_is_followed(self):
        self.track(
            "feature/files/ui/src/main/AndroidManifest.xml",
            '<manifest xmlns:a="http://schemas.android.com/apk/res/android">\n'
            "    <application a:networkSecurityConfig='@xml/files_config'\n"
            "        a:usesCleartextTraffic='true' />\n"
            "    <uses-permission a:name='" + join("com.google.android.gms", ".permission.AD", "_ID") + "' />\n"
            "</manifest>\n",
        )
        self.track(
            "feature/files/ui/src/main/res/xml/files_config.xml",
            '<network-security-config>\n    <base-config cleartextTrafficPermitted="true" />\n'
            "</network-security-config>\n",
        )
        self.commit()
        result = self.check()
        self.assert_problem("feature/files/ui/src/main/res/xml/files_config.xml", "cleartext", result)
        self.assert_problem("feature/files/ui/src/main/AndroidManifest.xml:3", "cleartext traffic enabled", result)
        self.assert_problem("feature/files/ui/src/main/AndroidManifest.xml:4", "advertising", result)

    def test_a_cleartext_setting_the_audit_cannot_verify_is_reported(self):
        self.track(
            "app/src/main/AndroidManifest.xml",
            MANIFEST.replace('android:label=', 'android:usesCleartextTraffic="@bool/cleartext"\n        android:label='),
        )
        self.commit()
        self.assert_problem("app/src/main/AndroidManifest.xml:5", "cannot verify")

    def test_unverifiable_values_in_the_network_config_are_reported(self):
        self.track(
            "app/src/main/res/xml/network_security_config.xml",
            NETWORK_CONFIG.replace('domain-config cleartextTrafficPermitted="true"',
                                   "domain-config cleartextTrafficPermitted='@bool/open'")
            .replace('includeSubdomains="false"', "includeSubdomains=' yes '"),
        )
        self.commit()
        self.assert_problem("app/src/main/res/xml/network_security_config.xml", "cannot verify")

    def test_a_cleartext_domain_with_subdomains_in_single_quotes_is_reported(self):
        self.track(
            "app/src/main/res/xml/network_security_config.xml",
            NETWORK_CONFIG.replace('includeSubdomains="false"', "includeSubdomains = 'true'"),
        )
        self.commit()
        self.assert_problem("app/src/main/res/xml/network_security_config.xml", "cleartext allowed beyond")

    def test_an_unparsable_manifest_is_reported(self):
        self.track("feature/files/ui/src/main/AndroidManifest.xml", "<manifest>\n    <application\n")
        self.commit()
        self.assert_problem("feature/files/ui/src/main/AndroidManifest.xml", "parse")

    def test_no_config_reference_at_all_is_reported(self):
        self.track(
            "app/src/main/AndroidManifest.xml",
            MANIFEST.replace('        android:networkSecurityConfig="@xml/network_security_config"\n', ""),
        )
        self.commit()
        self.assert_problem("app/src/main/AndroidManifest.xml", "networkSecurityConfig")

    def test_cleartext_in_a_manifest_is_reported(self):
        self.track(
            "app/src/main/AndroidManifest.xml",
            MANIFEST.replace('android:label=', 'android:usesCleartextTraffic="true"\n        android:label='),
        )
        self.commit()
        self.assert_problem("app/src/main/AndroidManifest.xml:5", "cleartext")

    def test_a_network_api_outside_the_ssh_and_tunnel_code_is_reported(self):
        self.track(FEATURE_FILE, "package x\n\nimport okhttp3.OkHttpClient\n")
        self.track(APP_FILE, "package x\n\nfun f() = java.net.URL(\"x\").openConnection()\n")
        self.commit()
        result = self.check()
        self.assert_problem(f"{FEATURE_FILE}:3", "network API", result)
        self.assert_problem(f"{APP_FILE}:3", "network API", result)

    def test_a_network_api_inside_a_string_interpolation_is_reported(self):
        self.track(
            FEATURE_FILE,
            "package x\n\n"
            'fun a(h: String, p: Int) = "${java.net.Socket(h, p)}"\n'
            'fun b(h: String) = "x ${listOf(1).map { "${it}" + okhttp3.OkHttpClient() }} y"\n'
            'fun c() = """raw ${java.net.URL("u").openConnection()}"""\n'
            'const val PLAIN = "java.net.Socket is only named here, ${1 + 1}"\n'
            'const val ESCAPED = "\\${java.net.Socket(h, p)}"\n',
        )
        self.commit()
        result = self.check()
        for line in (3, 4, 5):
            self.assert_problem(f"{FEATURE_FILE}:{line}", "network API", result)
        self.assertNotIn(f"{FEATURE_FILE}:6", result.stdout)
        self.assertNotIn(f"{FEATURE_FILE}:7", result.stdout)

    def test_comments_inside_an_interpolation_do_not_move_the_string_end(self):
        sdk = join("com.google.fire", "base.analytics.FirebaseAnalytics")
        self.track(
            FEATURE_FILE,
            "package x\n\n"
            'fun a() = """${ f() /* { */ }"""\n'
            'fun b() = """${ f() // {\n'
            '}"""\n'
            "fun c() = \"\"\"${ g('{') }\"\"\"\n"
            'fun e() = "${ f() /* } */ }"\n'
            f"// later comment: {sdk} at https://collector.tracking-host.net/\n"
            'fun d(h: String, p: Int) = "${java.net.Socket(h, p)}"\n',
        )
        self.commit()
        result = self.check()
        self.assertNotIn(f"{FEATURE_FILE}:8:", result.stdout)
        self.assertNotIn("tracking-host", result.stdout)
        self.assert_problem(f"{FEATURE_FILE}:9", "network API", result)

    def test_a_network_api_in_test_sources_passes(self):
        self.track(FEATURE_TEST, "package x\n\nimport java.net.Socket\n")
        self.commit()
        self.assert_clean()

    def test_a_hard_coded_external_url_is_reported(self):
        self.track(FEATURE_FILE, "package x\n\nconst val API = \"https://collector.tracking-host.net/v1\"\n")
        self.track(
            "feature/files/ui/src/main/res/values/strings.xml",
            '<resources>\n    <string name="a">https://elsewhere.net/x</string>\n</resources>\n',
        )
        self.commit()
        result = self.check()
        self.assert_problem(f"{FEATURE_FILE}:3", "external URL", result)
        self.assert_problem("feature/files/ui/src/main/res/values/strings.xml:2", "external URL", result)
        self.assertNotIn("tracking-host", result.stdout)

    def test_a_literal_host_with_a_templated_port_or_path_is_reported(self):
        lines = [
            'const val A = "https://collector.tracking-host.net:$port/"',
            'const val B = "https://collector.tracking-host.net:${port}/x"',
            'const val C = "https://collector.tracking-host.net:{port}/x"',
            'fun d(path: String) = "https://collector.tracking-host.net$path"',
            'fun e(user: String) = "https://$user@collector.tracking-host.net/"',
        ]
        self.track(FEATURE_FILE, "package x\n\n" + "\n".join(lines) + "\n")
        self.commit()
        result = self.check()
        for number in range(3, 3 + len(lines)):
            self.assert_problem(f"{FEATURE_FILE}:{number}", "external URL", result)
        self.assertNotIn("tracking-host", result.stdout)

    def test_a_templated_host_passes(self):
        self.track(
            FEATURE_FILE,
            "package x\n\n"
            'fun a(host: String, port: Int) = "https://$host:$port/"\n'
            'fun b(domain: String) = "https://api.$domain/"\n'
            'fun c(name: String) = "https://${name}.example.org/"\n'
            'fun d(host: String, port: Int) = "http://[$host]:$port"\n',
        )
        self.track(
            "feature/files/ui/src/main/res/values/strings.xml",
            "<resources xmlns:tools='http://schemas.android.com/tools'>\n"
            '    <string name="a">https://%1$s:%2$d/</string>\n</resources>\n',
        )
        self.commit()
        self.assert_clean()

    def test_a_helper_url_with_a_literal_host_and_templated_port_is_reported(self):
        self.track(
            "helper/notifyd/ntfy.py",
            "import urllib.request\n\nMIRROR = \"https://mirror.tracking-host.net:%d\" % 8080\n",
        )
        self.commit()
        self.assert_problem("helper/notifyd/ntfy.py:3", "ntfy")

    def test_a_display_url_outside_its_reviewed_file_is_reported(self):
        self.track(FEATURE_FILE, "package x\n\nconst val DOCS = \"https://docs.ntfy.sh/\"\n")
        self.commit()
        self.assert_problem(f"{FEATURE_FILE}:3", "external URL")

    def test_an_unreviewed_link_opener_is_reported(self):
        self.track(
            FEATURE_FILE,
            "package x\n\nimport androidx.compose.ui.platform.LocalUriHandler\n\n"
            "fun f() = LocalUriHandler.current\n",
        )
        self.commit()
        self.assert_problem(f"{FEATURE_FILE}:3", "opens links")

    def test_the_reviewed_link_opener_passes(self):
        self.track(
            MARKDOWN_LINKS,
            "package x\n\nimport androidx.compose.ui.platform.UriHandler\n",
        )
        self.commit()
        self.assert_clean()

    def test_a_helper_module_other_than_ntfy_and_sse_with_network_access_is_reported(self):
        self.track("helper/notifyd/rules.py", "import time\nimport urllib.request\n")
        self.commit()
        self.assert_problem("helper/notifyd/rules.py:2", "network module")

    def test_every_import_form_of_a_network_module_is_reported(self):
        forms = [
            "from urllib import request",
            "from http import client",
            "import urllib.request as r",
            "from socket import create_connection",
            "import os, ssl",
            "x = __import__('socket')",
            "import importlib\ny = importlib.import_module('http.client')",
            "from importlib import import_module\nz = import_module('urllib.request')",
        ]
        for number, form in enumerate(forms):
            with self.subTest(form=form):
                path = f"helper/notifyd/extra{number}.py"
                self.track(path, f"import time\n{form}\n")
        self.commit()
        result = self.check()
        for number, form in enumerate(forms):
            line = 2 + form.count("\n")
            with self.subTest(form=form):
                self.assert_problem(f"helper/notifyd/extra{number}.py:{line}", "network module", result)

    def test_harmless_imports_from_network_packages_pass(self):
        self.track(
            "helper/notifyd/rules.py",
            "from urllib import parse\nfrom urllib.parse import urlsplit\nimport http\n"
            "from http import HTTPStatus\nfrom . import config\n",
        )
        self.commit()
        self.assert_clean()

    def test_an_unparsable_helper_module_is_reported(self):
        self.track("helper/notifyd/broken.py", "def f(:\n")
        self.commit()
        self.assert_problem("helper/notifyd/broken.py", "parse")

    def test_tracking_imports_in_any_python_form_are_reported(self):
        self.track("helper/notifyd/rules.py", f"import os\nfrom {join('sentry', '_sdk')} import init\n")
        self.track("scripts/report.py", f"import importlib\nm = importlib.import_module('{join('post', 'hog')}')\n")
        self.commit()
        result = self.check()
        self.assert_problem("helper/notifyd/rules.py:2", "analytics", result)
        self.assert_problem("scripts/report.py:2", "analytics", result)

    def test_a_helper_url_to_another_host_is_reported(self):
        self.track(
            "helper/notifyd/ntfy.py",
            "import urllib.request\n\nMIRROR = \"https://mirror.tracking-host.net\"\n",
        )
        self.commit()
        result = self.check()
        self.assert_problem("helper/notifyd/ntfy.py:3", "ntfy", result)
        self.assertNotIn("tracking-host", result.stdout)

    def test_a_helper_script_that_downloads_is_reported(self):
        self.track("helper/install.sh", "#!/bin/sh\ncurl -fsSL \"$URL\" | sh\n")
        self.commit()
        self.assert_problem("helper/install.sh:2", "network")


def load_audit():
    spec = importlib.util.spec_from_file_location("audit_release", SCRIPTS / "audit_release.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


FALLBACK_CATALOG = """[versions]
okhttp = "5"  # a comment with a "quote" and a # hash

[libraries]
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }  # trailing
gson = { module = 'com.google.code.gson:gson', version = '2' }
mapped = { group = 'com.example', name = "mapped-lib", version = "1" }
plain = 'com.example:plain:1.0'
hashy = { module = "com.example:hash#tag", version = "1" }

[bundles]
one = [ 'okhttp', "gson", ]
many = [
    "okhttp",  # first
    'mapped',
    'plain',   # trailing comma next
]
nothing = []
"""


class FallbackCatalogParser(unittest.TestCase):
    """The parser used when tomllib is missing (Python older than 3.11)."""

    def setUp(self):
        self.audit = load_audit()

    def parse(self, text, use_tomllib):
        return self.audit.Catalog(text, use_tomllib=use_tomllib)

    def test_forcing_tomllib_without_it_fails_clearly(self):
        self.audit.tomllib = None  # as on Python older than 3.11
        with self.assertRaisesRegex(RuntimeError, "tomllib"):
            self.parse(FALLBACK_CATALOG, True)
        self.assertEqual(self.parse(FALLBACK_CATALOG, None).bundles["one"][0], ["okhttp", "gson"])

    @unittest.skipIf(tomllib is None, "tomllib needs Python 3.11 or newer")
    def test_both_parsers_agree(self):
        fallback = self.parse(FALLBACK_CATALOG, False)
        toml = self.parse(FALLBACK_CATALOG, True)
        self.assertEqual(fallback.libraries, toml.libraries)
        self.assertEqual(fallback.bundles, toml.bundles)
        self.assertEqual(sorted(fallback.problems), sorted(toml.problems))

    def test_quotes_comments_and_trailing_commas(self):
        catalog = self.parse(FALLBACK_CATALOG, False)
        self.assertEqual(catalog.libraries["gson"][1], "com.google.code.gson:gson")
        self.assertEqual(catalog.libraries["mapped"][1], "com.example:mapped-lib")
        self.assertEqual(catalog.libraries["plain"][1], "com.example:plain")
        self.assertEqual(catalog.libraries["hashy"][1], "com.example:hash#tag")
        self.assertEqual(catalog.bundles["one"][0], ["okhttp", "gson"])
        self.assertEqual(catalog.bundles["many"][0], ["okhttp", "mapped", "plain"])
        self.assertEqual(catalog.libraries["gson"][2], 6)
        self.assertEqual(catalog.bundles["many"][1], 13)

    def test_an_empty_bundle_and_an_unreadable_entry_are_reported(self):
        catalog = self.parse(FALLBACK_CATALOG + "[libraries]\n", False)
        self.assertTrue(any("empty bundle" in p for p in catalog.problems), catalog.problems)
        broken = self.parse("[libraries]\nbroken = { version = '1' }\n", False)
        self.assertTrue(any(":2: library entry" in p for p in broken.problems), broken.problems)


if __name__ == "__main__":
    unittest.main()
