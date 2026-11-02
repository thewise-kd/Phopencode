"""Fake secrets are assembled at runtime and written only to temporary directories.

No sample here may appear as one literal: this file is itself in the public
repository and in the scan.
"""
import importlib.util
import re
import unittest

from repo_fixture import REPO_ROOT, SCRIPTS, GitRepoCase, TempRepoCase

SOURCE = "core/model/src/main/kotlin/io/example/Model.kt"

MIXED = "aB3dE6gH9jK2mN5pQ8sT1vW4yZ7cF0hJ"  # 32 characters, no meaning


def join(*parts):
    return "".join(parts)


def key_block(kind="OPENSSH "):
    dashes = "-" * 5
    body = "\n".join([MIXED + MIXED] * 3)
    return (
        f"{dashes}{join('BEG', 'IN')} {kind}{join('PRIV', 'ATE')} KEY{dashes}\n"
        f"{body}\n"
        f"{dashes}END {kind}{join('PRIV', 'ATE')} KEY{dashes}\n"
    )


def key_header(kind="OPENSSH "):
    dashes = "-" * 5
    return f"{dashes}{join('BEG', 'IN')} {kind}{join('PRIV', 'ATE')} KEY{dashes}"


TOKENS = {
    "GitHub personal": join("gh", "p_", MIXED, "abcd"),
    "GitHub fine-grained": join("github", "_pat_", MIXED),
    "GitHub actions": join("gh", "s_", MIXED, "abcd"),
    "AWS": join("AK", "IA", "ABCDEFGH", "23456789"),
    "Anthropic": join("sk-", "ant-", "api03-", MIXED),
    "OpenAI": join("sk-", "proj-", MIXED),
    "OpenAI legacy": join("sk-", MIXED),
    "Slack": join("xo", "xb-", "123456789012-", MIXED),
    "Google": join("AI", "za", MIXED, "abc"),
    "Stripe": join("sk", "_live_", MIXED),
    "npm": join("np", "m_", MIXED, "abcd"),
    "GitLab": join("gl", "pat-", MIXED[:20]),
    "ntfy access": join("t", "k_", "abcdefghij", "0123456789", "abcdefghij"),
    "Z.AI": join("0123456789abcdef", "0123456789abcdef", ".", "AbCdEfGh", "IjKlMnOp"),
    "JWT": join("ey", "JhbGciOiJIUzI1NiJ9.", "ey", "JzdWIiOiIxMjM0In0.", MIXED),
}


def word(*parts):
    return join(*parts)


PASSWORD = word("pass", "word")
REAL_VALUE = join("Tr0ub4dor", "-x9!")


class CliContract(TempRepoCase):
    script = "check_boundaries.py"

    def test_outside_a_git_checkout_is_a_clear_error(self):
        result = self.check()
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn("git", result.stderr)
        self.assertNotIn("Traceback", result.stderr)

    def test_a_missing_root_is_a_clear_error(self):
        result = self.run_script("--root", str(self.base / "nowhere"))
        self.assertEqual(result.returncode, 2)
        self.assertNotIn("Traceback", result.stderr)

    def test_the_real_repository_passes_with_no_arguments(self):
        self.assert_clean(self.run_script(cwd=REPO_ROOT))

    def test_the_default_root_does_not_depend_on_the_working_directory(self):
        self.assert_clean(self.run_script(cwd=self.base))


class AndroidImports(GitRepoCase):
    script = "check_boundaries.py"

    def test_an_empty_repository_passes(self):
        self.assert_clean()

    def test_android_imports_are_reported_in_every_jvm_module(self):
        modules = (
            "core/model", "core/api", "core/api-http", "core/session", "core/ssh",
            "core/remote", "core/files", "core/git", "core/storage-api",
            "core/navigation", "core/testing", "feature/chat/logic", "feature/git/logic",
        )
        for module in modules:
            with self.subTest(module=module):
                path = f"{module}/src/main/kotlin/Model.kt"
                self.track(path, "package x\n\nimport android.os.Bundle\n")
                self.assert_problem(f"{path}:3", "android.os.Bundle")
                self.track(path, "package x\n")

    def test_every_import_form_is_reported(self):
        forms = {
            "android": "import android.os.Bundle",
            "androidx": "import androidx.compose.runtime.Composable",
            "star": "import android.os.*",
            "alias": "import android.util.Log as AndroidLog",
            "backticks": "import `android`.os.Bundle",
            "indented": "    import android.os.Bundle",
            "after a semicolon": "package x; import android.os.Bundle",
            "java": "import android.os.Bundle;",
            "java static": "import static android.os.Build.VERSION.SDK_INT;",
        }
        for name, line in forms.items():
            with self.subTest(name):
                self.track(SOURCE, f"// header\n{line}\n")
                self.assert_problem(f"{SOURCE}:2", "android")

    def test_a_comment_separates_tokens_of_an_import(self):
        for line in (
            "import/*c*/android.os.Bundle;",
            "import android/*c*/.os.Bundle",
            "import android./*c*/os.Bundle",
            "import android.os/*c*/.Bundle",
            "import androidx/*c*/.compose.runtime.Composable",
            "import/*c*/static/*c*/android.os.Build.VERSION;",
            "/*c*/import android.os.Bundle",
            'package x/*c*/; import android.os.Bundle',
        ):
            with self.subTest(line):
                self.track(SOURCE, f"// header\n{line}\n")
                self.assert_problem(f"{SOURCE}:2", "android")

    def test_an_import_may_span_whitespace_and_lines(self):
        for text in (
            "import android . os . Bundle",
            "import\nandroid.os.Bundle",
            "import android\n    .os.Bundle",
            "import /*a\nb*/ android.os.Bundle",
            "import // c\nandroid.os.Bundle",
        ):
            with self.subTest(text):
                self.track(SOURCE, f"// header\n{text}\n")
                self.assert_problem(f"{SOURCE}:2", "android.os.Bundle")

    def test_a_comment_never_joins_tokens_into_a_forbidden_name(self):
        for line in (
            "import androidxfoo.Bar",
            "import/*c*/androidxfoo.Bar",
            "import android/*c*/x.Bar",
            "import android/*c*/Foo.Bar",
            "import android_extras/*c*/.Foo",
            "import foo/*android.os*/.Bar",
            "import foo.android/*c*/.Bar",
        ):
            with self.subTest(line):
                self.track(SOURCE, f"{line}\n")
                self.assert_clean()

    def test_java_block_comments_do_not_nest(self):
        path = "core/model/src/main/java/A.java"
        self.track(path, "/* /* */ import android.os.Bundle;\n")
        self.assert_problem(f"{path}:1", "android")

    def test_test_sources_of_a_jvm_module_are_checked(self):
        path = "core/model/src/test/kotlin/ModelTest.kt"
        self.track(path, "import android.os.Bundle\n")
        self.assert_problem(f"{path}:1", "android")

    def test_java_and_script_sources_are_checked(self):
        for path in ("core/model/src/main/java/A.java", "core/model/build.gradle.kts"):
            with self.subTest(path):
                self.track(path, "import android.os.Bundle;\n")
                self.assert_problem(f"{path}:1", "android")

    def test_android_modules_may_import_android(self):
        for path in (
            "app/src/main/kotlin/Main.kt",
            "core/ui/src/main/kotlin/Theme.kt",
            "core/storage/src/main/kotlin/Store.kt",
            "core/markdown/src/main/kotlin/Markdown.kt",
            "core/codeview/src/main/kotlin/Code.kt",
            "feature/chat/ui/src/main/kotlin/Chat.kt",
        ):
            self.track(path, "import android.os.Bundle\n")
        self.assert_clean()

    def test_lookalike_imports_pass(self):
        self.track(
            SOURCE,
            "import android_extras.Foo\n"
            "import com.android.tools.Foo\n"
            "import io.example.android.Foo\n"
            "import androidxish.Foo\n",
        )
        self.assert_clean()

    def test_text_that_only_mentions_an_import_passes(self):
        self.track(
            SOURCE,
            "// import android.os.Bundle\n"
            "/*\n"
            "import android.os.Bundle\n"
            "/* nested */\n"
            "import android.os.Bundle\n"
            "*/\n"
            'val sample = """\n'
            "import android.os.Bundle\n"
            '"""\n'
            'val quote = \'"\'\n'
            "val text = \"import android.os.Bundle\"\n",
        )
        self.assert_clean()

    def test_an_import_after_a_multiline_file_annotation_is_reported(self):
        self.track(
            SOURCE,
            '@file:Suppress(\n    "x",\n)\n\npackage y\n\nimport android.os.Bundle\n',
        )
        self.assert_problem(f"{SOURCE}:7", "android")

    def test_a_non_utf8_source_does_not_hide_an_import(self):
        self.track(SOURCE, b"// caf\xe9\nimport android.os.Bundle\n")
        self.assert_problem(f"{SOURCE}:2", "android")


class ModuleMapDrift(unittest.TestCase):
    def test_every_jvm_module_in_settings_is_recognised(self):
        spec = importlib.util.spec_from_file_location(
            "check_boundaries", SCRIPTS / "check_boundaries.py"
        )
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)

        settings = (REPO_ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
        jvm_block = re.search(r"^\s+jvm\((.*?)^\s+\)", settings, re.S | re.M).group(1)
        android_block = re.search(r"^\s+android\((.*?)^\s+\)", settings, re.S | re.M).group(1)
        jvm = re.findall(r'":([^"]+)"', jvm_block)
        android = re.findall(r'":([^"]+)"', android_block)
        self.assertEqual(len(jvm) + len(android), 32)
        for name in jvm:
            path = name.replace(":", "/") + "/src/main/kotlin/A.kt"
            self.assertTrue(module.is_jvm_module(path), path)
        for name in android:
            path = name.replace(":", "/") + "/src/main/kotlin/A.kt"
            self.assertFalse(module.is_jvm_module(path), path)


class PrivateKeys(GitRepoCase):
    script = "check_boundaries.py"

    def test_key_blocks_are_reported(self):
        for kind in ("", "RSA ", "EC ", "OPENSSH ", "ENCRYPTED "):
            with self.subTest(kind=kind):
                self.track("notes.txt", "first\n" + key_block(kind))
                self.assert_problem("notes.txt:2", "private key")

    def test_a_key_wrapped_into_short_lines_is_reported(self):
        body = "\n".join(MIXED[i:i + 16] for i in range(0, 32, 16)) * 3
        self.track("notes.txt", f"{key_header()}\n{body}\n")
        self.assert_problem("notes.txt:1", "private key")

    def test_a_header_alone_is_a_format_constant(self):
        self.track(SOURCE, f'const val HEADER = "{key_header()}"\nval next = 1\n')
        self.assert_clean()

    def test_a_placeholder_body_passes(self):
        self.track("docs/keys.md", f"{key_header()}\n<your key here>\n")
        self.assert_clean()


class TokenFormats(GitRepoCase):
    script = "check_boundaries.py"

    def test_token_formats_are_reported_without_echoing_the_value(self):
        for name, token in TOKENS.items():
            with self.subTest(name):
                self.track("notes.txt", f"first\nvalue {token} here\n")
                result = self.check()
                self.assert_problem("notes.txt:2", "token", result)
                self.assertNotIn(token, result.stdout)
                self.assertNotIn(token, result.stderr)

    def test_an_authorization_header_is_reported(self):
        value = join("dXNlcjpwYXNz", "d29yZDEyMzQ1Ng==")
        for scheme in ("Basic", "Bearer"):
            with self.subTest(scheme):
                self.track("notes.txt", f'"Authorization": "{scheme} {value}"\n')
                self.assert_problem("notes.txt:1", "Authorization")

    def test_a_computed_authorization_header_passes(self):
        self.track(SOURCE, 'header("Authorization", "Basic $encoded")\n')
        self.track("a.txt", "Authorization: Bearer <token>\n")
        self.assert_clean()

    def test_lookalike_words_pass(self):
        self.track(
            "notes.txt",
            "task-sk-is-a-kebab-case-name-without-digits\n"
            "sk-some-long-kebab-case-identifier-here\n"
            "ghp_short\n"
            "AKIAlowercase\n"
            "risk-0123456789012345678901234\n",
        )
        self.assert_clean()

    def test_gibberish_in_binary_files_passes(self):
        self.track("gradle/wrapper/gradle-wrapper.jar", bytes(range(256)) * 200)
        self.assert_clean()

    def test_a_token_in_a_binary_file_is_still_reported(self):
        token = TOKENS["GitHub personal"]
        self.track("blob.bin", b"\x00\x01\xff\n\n" + token.encode() + b"\n")
        self.assert_problem("blob.bin:3", "token")

    def test_a_key_block_in_a_binary_file_is_still_reported(self):
        self.track("blob.bin", b"\x00\xff\n" + key_block().encode())
        self.assert_problem("blob.bin:2", "private key")

    def test_a_token_in_a_non_utf8_text_file_is_reported(self):
        token = TOKENS["GitHub personal"]
        self.track("latin1.txt", b"caf\xe9\n" + token.encode() + b"\n")
        self.assert_problem("latin1.txt:2", "token")


class UrlCredentials(GitRepoCase):
    script = "check_boundaries.py"

    def test_real_credentials_are_reported_without_echoing_them(self):
        for url in (
            f"https://admin:{REAL_VALUE}@server.io/path",
            f"http://admin:{REAL_VALUE}@127.0.0.1:4096",
            f"ssh://root:{REAL_VALUE}@server.io",
            f"https://admin:{join('p@', 'ss9w')}@server.io",
        ):
            with self.subTest(url):
                self.track("notes.txt", f"see {url} now\n")
                result = self.check()
                self.assert_problem("notes.txt:1", "URL", result)
                self.assertNotIn(REAL_VALUE, result.stdout)

    def test_placeholders_pass(self):
        for url in (
            f"https://user:{PASSWORD}@server.io",
            "https://user:pass@server.io",
            "https://user:<password>@server.io",
            "https://user:$PASSWORD@server.io",
            "https://user:${PASSWORD}@server.io",
            'https://$user:${profile.secret}@$host',
            "https://user:%s@server.io",
            "https://user:***@server.io",
            "https://user:xxxx@server.io",
            f"https://user:{REAL_VALUE}@example.com/x",
            f"https://user:{REAL_VALUE}@api.example.org",
            f"https://user:{REAL_VALUE}@host.example",
        ):
            with self.subTest(url):
                self.track("notes.txt", f"see {url} now\n")
                self.assert_clean()

    def test_a_placeholder_shape_is_not_a_loophole(self):
        for value in (f"${REAL_VALUE}", f"<{REAL_VALUE}", f"xxx{REAL_VALUE}"):
            with self.subTest(value):
                self.track("notes.txt", f"https://u:{value}@server.io\n")
                self.assert_problem("notes.txt:1", "URL")

    def test_urls_without_credentials_pass(self):
        self.track(
            "notes.txt",
            "https://server.io:8080/a@b\n"
            "git@github.com:thewise-kd/phopencode.git\n"
            "ssh://git@github.com:22/x.git\n",
        )
        self.assert_clean()

    def test_an_example_host_lookalike_is_reported(self):
        self.track("notes.txt", f"https://u:{REAL_VALUE}@example.com.evil.io\n")
        self.assert_problem("notes.txt:1", "URL")


class NtfyTopics(GitRepoCase):
    script = "check_boundaries.py"

    def host(self):
        return join("ntfy", ".sh")

    def test_real_topics_are_reported_without_echoing_them(self):
        topic = join("phop-", "a8f3c1d97b2e")
        for form in (
            f"{self.host()}/{topic}",
            f"https://{self.host()}/{topic}",
            f"ntfy://{self.host()}/{topic}",
            f"curl -d hi {self.host()}/{topic}/publish",
        ):
            with self.subTest(form):
                self.track("notes.txt", f"{form}\n")
                result = self.check()
                self.assert_problem("notes.txt:1", "ntfy", result)
                self.assertNotIn(topic, result.stdout)

    def test_a_short_real_looking_topic_is_reported(self):
        self.track("notes.txt", f"{self.host()}/alerts\n")
        self.assert_problem("notes.txt:1", "ntfy")

    def test_a_topic_assignment_is_reported(self):
        topic = join("phop-", "a8f3c1d97b2e")
        self.track("config.py", f'NTFY_TOPIC = "{topic}"\n')
        self.assert_problem("config.py:1", "ntfy")

    def test_placeholders_and_site_paths_pass(self):
        for text in (
            f"{self.host()}/<topic>",
            f"{self.host()}/<random-topic>",
            f"ntfy://{self.host()}/<topic>",
            f"https://{self.host()}/$topic",
            f"https://{self.host()}/${{topic}}",
            f"https://{self.host()}/topic",
            f"https://{self.host()}/your-topic",
            f"https://{self.host()}/example-topic",
            f"https://{self.host()}/",
            f"https://{self.host()}",
            f"https://{self.host()}/docs/publish",
            f"https://{self.host()}/app",
            f"https://docs.{self.host()}/subscribe/api",
            'NTFY_TOPIC = "<topic>"',
        ):
            with self.subTest(text):
                self.track("notes.txt", f"{text}\n")
                self.assert_clean()


class PasswordAssignments(GitRepoCase):
    script = "check_boundaries.py"

    def test_quoted_assignments_are_reported_without_echoing_the_value(self):
        for line in (
            f'val {PASSWORD} = "{REAL_VALUE}"',
            f'val {PASSWORD}: String = "{REAL_VALUE}"',
            f'const val SERVER_{PASSWORD.upper()} = "{REAL_VALUE}"',
            f'connect(serverPassword = "{REAL_VALUE}")',
            f'"{PASSWORD}": "{REAL_VALUE}"',
            f"{PASSWORD}: '{REAL_VALUE}'",
            f'val passphrase = "{REAL_VALUE}"',
            f'val apiKey = "{REAL_VALUE}"',
            f'val clientSecret = "{REAL_VALUE}"',
            f'ANTHROPIC_AUTH_TOKEN="{REAL_VALUE}"',
            f'"ANTHROPIC_AUTH_TOKEN": "{REAL_VALUE}"',
        ):
            with self.subTest(line):
                self.track("notes.txt", f"first\n{line}\n")
                result = self.check()
                self.assert_problem("notes.txt:2", "hard-coded", result)
                self.assertNotIn(REAL_VALUE, result.stdout)

    def test_unquoted_shell_assignments_are_reported(self):
        for line in (
            f"OPENCODE_SERVER_{PASSWORD.upper()}={REAL_VALUE}",
            f"export OPENCODE_SERVER_{PASSWORD.upper()}={REAL_VALUE}",
            f"ANTHROPIC_AUTH_TOKEN={REAL_VALUE} opencode serve",
        ):
            with self.subTest(line):
                self.track("start.sh", f"{line}\n")
                self.assert_problem("start.sh:1", "hard-coded")

    def test_unquoted_config_values_are_reported(self):
        for path, line in (
            ("settings.yml", f"{PASSWORD}: {REAL_VALUE}"),
            ("app.properties", f"storePassword={REAL_VALUE}"),
            (".env.example", f"db_{PASSWORD}={REAL_VALUE}"),
        ):
            with self.subTest(path):
                self.track(path, f"{line}\n")
                self.assert_problem(f"{path}:1", "hard-coded")

    def test_placeholders_pass(self):
        for line in (
            f'val {PASSWORD} = ""',
            f'val {PASSWORD} = "{PASSWORD}"',
            f'val {PASSWORD} = "changeme"',
            f'val {PASSWORD} = "test-{PASSWORD}"',
            f'val {PASSWORD} = "fake_secret"',
            f'val {PASSWORD} = "testPassword"',
            f'val {PASSWORD} = "your-{PASSWORD}-here"',
            f'val {PASSWORD} = "example"',
            f'val {PASSWORD} = "***"',
            f'val {PASSWORD} = "<{PASSWORD}>"',
            f'val {PASSWORD} = "$value"',
            f'val {PASSWORD} = "${{config.value}}"',
            f'{PASSWORD}: "{{{{ secrets.VALUE }}}}"',
            '"ANTHROPIC_AUTH_TOKEN": "<Z.AI key>"',
        ):
            with self.subTest(line):
                self.track("notes.txt", f"{line}\n")
                self.assert_clean()

    def test_a_placeholder_word_is_not_a_loophole(self):
        for value in (f"test-{REAL_VALUE}", f"fake{REAL_VALUE}", "test1234", f"<{REAL_VALUE}"):
            with self.subTest(value):
                self.track("notes.txt", f'val {PASSWORD} = "{value}"\n')
                self.assert_problem("notes.txt:1", "hard-coded")

    LITERALS_SHAPED_LIKE_PLACEHOLDERS = (
        "$ecret123",
        "$ecret-123",
        "<abc123>",
        "<abc123",
        "<abc>xyz123",
        "{abc123}",
        "{abc123",
        "{abc}xyz123",
        "${abc123}",
        "${abc123",
        "${name}xyz123",
        "${X:-Tr0ub4dor}",
        "{{abc123}}",
    )

    def test_a_first_character_is_not_a_loophole_in_config_values(self):
        for path, separator in (("settings.yml", ": "), ("app.properties", "=")):
            for value in self.LITERALS_SHAPED_LIKE_PLACEHOLDERS:
                with self.subTest(path=path, value=value):
                    self.track(path, f"{PASSWORD}{separator}{value}\n")
                    self.assert_problem(f"{path}:1", "hard-coded")

    def test_a_first_character_is_not_a_loophole_in_shell_values(self):
        for value in self.LITERALS_SHAPED_LIKE_PLACEHOLDERS:
            with self.subTest(value=value):
                self.track("start.sh", f"{PASSWORD.upper()}={value}\n")
                self.assert_problem("start.sh:1", "hard-coded")

    def test_a_first_character_is_not_a_loophole_in_quoted_values(self):
        for value in self.LITERALS_SHAPED_LIKE_PLACEHOLDERS:
            with self.subTest(value=value):
                self.track("notes.txt", f'val {PASSWORD} = "{value}"\n')
                self.assert_problem("notes.txt:1", "hard-coded")

    def test_a_first_character_is_not_a_loophole_in_url_passwords(self):
        for value in ("$ecret123", "${abc123}", "<abc123>", "{abc123}"):
            with self.subTest(value=value):
                self.track("notes.txt", f"https://u:{value}@server.io\n")
                self.assert_problem("notes.txt:1", "URL")

    def test_a_config_value_is_not_a_command(self):
        for path, line in (
            ("settings.yml", f"{PASSWORD}: $(abc123)"),
            ("app.properties", f"{PASSWORD}=$(abc123)"),
        ):
            with self.subTest(path):
                self.track(path, f"{line}\n")
                self.assert_problem(f"{path}:1", "hard-coded")

    def test_an_unbalanced_command_is_not_a_command(self):
        self.track("start.sh", f"{PASSWORD.upper()}=$(abc123\n")
        self.assert_problem("start.sh:1", "hard-coded")

    def test_placeholder_forms_pass_in_config_values(self):
        for value in (
            "$NAME",
            "${name}",
            "<name>",
            "<your value here>",
            "{name}",
            "{{ secrets.VALUE }}",
            "${{ secrets.VALUE_2 }}",
        ):
            for path, separator in (("settings.yml", ": "), ("app.properties", "=")):
                with self.subTest(path=path, value=value):
                    self.track(path, f"{PASSWORD}{separator}{value}  # note\n")
                    self.assert_clean()

    def test_placeholder_forms_pass_in_shell_values(self):
        for value in (
            "$NAME",
            "${name}",
            "<name>",
            "<your value here>",
            "{name}",
            "$(cat file)",
            "$(cat $(ls dir))",
            "$(od -An -N16 /dev/urandom | tr -d ' \\n')",
            "${NAME:-}",
            "${NAME:?not set}",
        ):
            with self.subTest(value=value):
                self.track("start.sh", f"{PASSWORD.upper()}={value}\n")
                self.assert_clean()

    def test_a_literal_next_to_a_command_is_reported(self):
        for value in (
            "$(true)literal123",
            "$(cat file)literal123",
            '"$(true)literal123"',
            '"$(true)"literal123',
            '$(true)"literal123"',
            "$(true)$(true)",
            "literal123$(true)",
            '"literal123$(true)"',
            "'$(true)'",
            "'$(cat file)'",
        ):
            with self.subTest(value=value):
                self.track("start.sh", f"{PASSWORD.upper()}={value}\n")
                self.assert_problem("start.sh:1", "hard-coded")

    def test_a_quoted_command_outside_a_shell_statement_is_reported(self):
        for path, line in (
            ("settings.yml", f'{PASSWORD}: "$(abc123)"'),
            ("settings.yml", f'{PASSWORD.upper()}: "$(abc123)"'),
            ("app.properties", f'{PASSWORD}="$(abc123)"'),
            ("Main.kt", f'val {PASSWORD} = "$(abc123)"'),
            ("Main.kt", f'const val {PASSWORD.upper()} = "$(abc123)"'),
        ):
            with self.subTest(path=path, line=line):
                self.track(path, f"{line}\n")
                self.assert_problem(f"{path}:1", "hard-coded")

    def test_a_whole_command_passes_quoted_or_not(self):
        upper = PASSWORD.upper()
        for path, line in (
            ("start.sh", f'{upper}="$(cat file)"'),
            ("start.sh", f'{upper}="$(cat "file")"'),
            ("start.sh", f'{upper}="$(cat file)" ./run'),
            ("start.sh", f'{upper}="$(cat file)"; next'),
            ("start.sh", f"{upper}=$(cat file); next"),
            ("start.sh", f"{upper}=$(cat file) && next"),
            ("start.sh", f'{PASSWORD}="$(cat file)"'),
            ("ci.yml", f'      - run: SIGNING_{upper}="$(cat file)" ./gradlew'),
        ):
            with self.subTest(path=path, line=line):
                self.track(path, f"{line}\n")
                self.assert_clean()

    def test_a_literal_after_a_quoted_placeholder_is_reported(self):
        for value in (
            '"$VALUE"literal123',
            "'$VALUE'literal123",
            '"$VALUE"\'literal123\'',
            '"$VALUE""literal123"',
            '"$VALUE"-literal123',
            '"$VALUE".literal123',
            '"$VALUE"\\literal123',
            '"$VALUE"\\nliteral123',
            '"<name>"literal123',
            '""literal123',
        ):
            for name in (PASSWORD.upper(), PASSWORD):
                with self.subTest(name=name, value=value):
                    self.track("start.sh", f"{name}={value}\n")
                    self.assert_problem("start.sh:1", "hard-coded")

    def test_a_quoted_placeholder_ending_its_word_passes(self):
        upper = PASSWORD.upper()
        for path, line in (
            ("start.sh", f'{upper}="$VALUE"'),
            ("start.sh", f"{upper}='$VALUE'"),
            ("start.sh", f'{upper}="$VALUE" ./run'),
            ("start.sh", f'{upper}="$VALUE";'),
            ("start.sh", f'{upper}="$VALUE" | next'),
            ("Main.kt", f'connect({PASSWORD} = "$value",'),
            ("Main.kt", f'connect({PASSWORD} = "$value")'),
            ("Main.kt", f'val {PASSWORD} = "test-{PASSWORD}".toCharArray()'),
            ("a.json", f'{{"{PASSWORD}": "<value>"}}'),
            ("a.json", f'["{PASSWORD}": "<value>"]'),
            ("a.xml", f'<item {PASSWORD}="<value>"/>'),
            ("a.xml", f'<item {PASSWORD}="<value>">'),
            ("a.md", f'Set `{PASSWORD} = "<value>"` first.'),
            ("a.md", f'Set {PASSWORD} = "<value>". Then go.'),
            ("writer.py", f"""out.write('{upper} = "$value"\\n')"""),
        ):
            with self.subTest(path=path, line=line):
                self.track(path, f"{line}\n")
                self.assert_clean()

    def test_a_config_file_without_shell_holds_no_commands(self):
        upper = PASSWORD.upper()
        for path in (
            "app.properties", "app.ini", "app.toml", "app.conf", "app.cfg",
            "prod.env", ".env.example",
        ):
            for value in ("$(abc123)", '"$(abc123)"'):
                with self.subTest(path=path, value=value):
                    self.track(path, f"{upper}={value}\n")
                    self.assert_problem(f"{path}:1", "hard-coded")

    def test_a_shell_step_in_either_yaml_suffix_may_run_a_command(self):
        upper = PASSWORD.upper()
        for path in ("ci.yml", "ci.yaml"):
            for value in ("$(cat file)", '"$(cat file)"'):
                with self.subTest(path=path, value=value):
                    self.track(path, f"      - run: SIGNING_{upper}={value} ./gradlew\n")
                    self.assert_clean()

    def test_a_shell_step_in_a_workflow_may_run_a_command(self):
        self.track("ci.yml", f"      - run: SIGNING_{PASSWORD.upper()}=$(cat file) ./gradlew\n")
        self.assert_clean()

    def test_code_that_is_not_an_assignment_passes(self):
        self.track(
            SOURCE,
            f"val {PASSWORD}: String\n"
            f"val {PASSWORD} = readLine()\n"
            f"val {PASSWORD}Hint = \"Your {PASSWORD}\"\n"
            f'val tokenCount = "3"\n'
            f'if ({PASSWORD} == "generated") return\n'
            f'authPassword("user", "generated")\n'
            f'env.get("ANTHROPIC_AUTH_TOKEN")\n'
            f"keyPassword = System.getenv(\"KEY\")\n",
        )
        self.track(
            "start.sh",
            f"{PASSWORD.upper()}=$(cat)\n"
            f"OPENCODE_SERVER_{PASSWORD.upper()}=\"$VALUE\"\n",
        )
        self.track(
            "ci.yml",
            f"{PASSWORD}: ${{{{ secrets.VALUE }}}}\n"
            f"storePassword: ${{{{ secrets.STORE }}}}\n",
        )
        self.track("guide.md", f"The {PASSWORD}: never put it on the command line.\n")
        self.assert_clean()


class ServerAddresses(GitRepoCase):
    script = "check_boundaries.py"

    def test_literal_addresses_are_reported_without_echoing_them(self):
        address = join("192.168", ".1.", "44")
        for line in (
            f"ssh ace@{address}",
            f"http://{address}:4096",
            f'val host = "{address}"',
            f"server: {address}",
        ):
            with self.subTest(line):
                self.track("notes.txt", f"{line}\n")
                result = self.check()
                self.assert_problem("notes.txt:1", "server address", result)
                self.assertNotIn(address, result.stdout)

    def test_non_server_addresses_pass(self):
        for line in (
            "http://127.0.0.1:4096",
            "bind 0.0.0.0",
            "host = 0.0.0.0",
            'val host = "127.0.0.1"',
            "http://10.0.2.2:4096",
            "http://192.0.2.10",
            "ssh user@198.51.100.7",
            "http://203.0.113.9:4096",
            "version = 1.2.3.4",
            "gradle 9.8.2.1 ok",
            "http://999.1.1.1",
        ):
            with self.subTest(line):
                self.track("notes.txt", f"{line}\n")
                self.assert_clean()


class FileSelection(GitRepoCase):
    script = "check_boundaries.py"

    def secret_line(self):
        return f"{TOKENS['GitHub personal']}\n"

    def test_ignored_files_are_not_scanned(self):
        self.write(".gitignore", "local.properties\n")
        self.git("add", ".gitignore")
        self.write("local.properties", self.secret_line())
        self.assert_clean()

    def test_untracked_files_that_are_not_ignored_are_scanned(self):
        self.write("new.txt", self.secret_line())
        self.assert_problem("new.txt:1", "token")

    def test_no_path_is_exempt(self):
        for path in (
            "scripts/tests/samples.py",
            "scripts/check_boundaries.py",
            "docs/example.md",
            "core/testing/src/main/resources/fixtures/a.json",
            "core/model/src/test/kotlin/ATest.kt",
        ):
            with self.subTest(path):
                self.track(path, self.secret_line())
                self.assert_problem(f"{path}:1", "token")
                self.git("rm", "--force", "--quiet", "--", path)

    def test_a_tracked_file_deleted_from_disk_is_skipped(self):
        target = self.track("gone.txt", "text\n")
        target.unlink()
        self.assert_clean()

    def test_tracked_symlinks_are_skipped(self):
        outside = self.base / "outside.txt"
        outside.write_text(self.secret_line(), encoding="utf-8")
        (self.root / "link.txt").symlink_to(outside)
        self.git("add", "link.txt")
        self.assert_clean()

    def test_a_ci_workflow_shape_passes(self):
        self.track(
            ".github/workflows/ci.yml",
            "permissions:\n"
            "  contents: read\n"
            "jobs:\n"
            "  checks:\n"
            "    steps:\n"
            f"      - uses: actions/checkout@{'0123456789abcdef' * 2}01234567 # v7\n"
            "        with:\n"
            "          token: ${{ secrets.GITHUB_TOKEN }}\n"
            "      - run: python3 scripts/check_strings.py\n"
            "        env:\n"
            "          SIGNING_KEY_PASSWORD: ${{ secrets.SIGNING_KEY_PASSWORD }}\n"
            "          secrets: inherit\n",
        )
        self.assert_clean()

    def test_a_name_with_spaces_and_unicode_is_reported_verbatim(self):
        self.track("docs/şifre notları.txt", self.secret_line())
        self.assert_problem("docs/şifre notları.txt:1", "token")

    def test_reports_are_sorted_and_cover_every_problem(self):
        self.track("b.txt", self.secret_line())
        self.track("a.txt", "ok\n" + self.secret_line())
        self.track(SOURCE, "import android.os.Bundle\n")
        lines = self.check().stdout.splitlines()
        locations = [line.split(": ")[0] for line in lines if ": " in line]
        self.assertEqual(locations, ["a.txt:2", "b.txt:1", f"{SOURCE}:1"])


if __name__ == "__main__":
    unittest.main()
