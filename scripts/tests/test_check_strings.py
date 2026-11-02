import unittest

from repo_fixture import REPO_ROOT, TempRepoCase

VALUES = "feature/chat/ui/src/main/res/values"
VALUES_TR = "feature/chat/ui/src/main/res/values-tr"
SCREEN = "feature/chat/ui/src/main/kotlin/io/example/Screen.kt"


def resources(*entries):
    """Each entry lands on its own line, so the first one is at line 3."""
    body = "\n".join(entries)
    return f'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n{body}\n</resources>\n'


def string(name, text="x", extra=""):
    return f'    <string name="{name}"{extra}>{text}</string>'


def composable(body):
    """`body` starts at line 7 of the file."""
    return (
        "package io.example\n"
        "\n"
        "import androidx.compose.runtime.Composable\n"
        "\n"
        "@Composable\n"
        "fun Screen(name: String) {\n"
        f"{body}\n"
        "}\n"
    )


class CliContract(TempRepoCase):
    script = "check_strings.py"

    def test_a_repository_without_android_modules_passes(self):
        self.write("core/model/src/main/kotlin/Model.kt", "package x\n")
        self.assert_clean()

    def test_the_real_repository_passes_with_no_arguments(self):
        self.assert_clean(self.run_script(cwd=REPO_ROOT))

    def test_the_default_root_does_not_depend_on_the_working_directory(self):
        self.assert_clean(self.run_script(cwd=self.base))

    def test_a_missing_root_is_a_clear_error(self):
        result = self.run_script("--root", str(self.base / "nowhere"))
        self.assertEqual(result.returncode, 2)
        self.assertIn("nowhere", result.stderr)
        self.assertNotIn("Traceback", result.stderr)


class KeyParity(TempRepoCase):
    script = "check_strings.py"

    def write_pair(self, english, turkish, name="strings_chat_form.xml"):
        self.write(f"{VALUES}/{name}", english)
        self.write(f"{VALUES_TR}/{name}", turkish)

    def test_matching_keys_pass(self):
        self.write_pair(
            resources(string("chat_form_title"), string("chat_form_send")),
            resources(string("chat_form_send", "gönder"), string("chat_form_title", "başlık")),
        )
        self.assert_clean()

    def test_a_key_missing_from_turkish_is_reported_where_it_exists(self):
        self.write_pair(
            resources(string("chat_form_title"), string("chat_form_send")),
            resources(string("chat_form_title")),
        )
        self.assert_problem(
            f"{VALUES}/strings_chat_form.xml:4", "chat_form_send"
        )

    def test_a_key_missing_from_english_is_reported_where_it_exists(self):
        self.write_pair(
            resources(string("chat_form_title")),
            resources(string("chat_form_title"), string("chat_form_send")),
        )
        self.assert_problem(
            f"{VALUES_TR}/strings_chat_form.xml:4", "chat_form_send"
        )

    def test_plurals_and_arrays_are_keys_too(self):
        self.write_pair(
            resources(
                '    <plurals name="chat_form_items"><item quantity="one">x</item></plurals>',
                '    <string-array name="chat_form_kinds"><item>x</item></string-array>',
            ),
            resources(),
        )
        path = f"{VALUES}/strings_chat_form.xml"
        result = self.check()
        self.assert_problem(f"{path}:3", "chat_form_items", result)
        self.assert_problem(f"{path}:4", "chat_form_kinds", result)

    def test_plural_quantities_may_differ_between_languages(self):
        self.write_pair(
            resources(
                '    <plurals name="chat_form_items">\n'
                '        <item quantity="one">x</item>\n'
                '        <item quantity="other">y</item>\n'
                "    </plurals>"
            ),
            resources(
                '    <plurals name="chat_form_items"><item quantity="other">y</item></plurals>'
            ),
        )
        self.assert_clean()

    def test_an_untranslatable_key_needs_no_turkish_copy(self):
        self.write_pair(
            resources(string("chat_form_brand", "opencode", ' translatable="false"')),
            resources(),
        )
        self.assert_clean()

    def test_an_untranslatable_key_must_not_be_translated(self):
        self.write_pair(
            resources(string("chat_form_brand", "opencode", ' translatable="false"')),
            resources(string("chat_form_brand", "opencode")),
        )
        self.assert_problem(
            f"{VALUES_TR}/strings_chat_form.xml:3", "translatable"
        )

    def test_every_android_module_is_covered(self):
        for module in ("app", "core/ui", "core/markdown", "feature/git/ui"):
            with self.subTest(module=module):
                self.write(
                    f"{module}/src/main/res/values/strings_x_y.xml",
                    resources(string("x_y_title")),
                )
                self.write(
                    f"{module}/src/main/res/values-tr/strings_x_y.xml", resources()
                )
                self.assert_problem(
                    f"{module}/src/main/res/values/strings_x_y.xml:3", "x_y_title"
                )
                self.write(
                    f"{module}/src/main/res/values-tr/strings_x_y.xml",
                    resources(string("x_y_title")),
                )

    def test_resources_outside_the_shipped_tree_are_ignored(self):
        for module in (
            "app/src/androidTest/res",
            "app/src/test/res",
            "app/build/generated/res/src/main/res",
            ".claude/worktrees/other/app/src/main/res",
        ):
            self.write(f"{module}/values/strings_x_y.xml", resources(string("x_y_title")))
        self.assert_clean()

    def test_other_locales_are_not_part_of_the_pair(self):
        self.write_pair(resources(string("chat_form_title")), resources(string("chat_form_title")))
        self.write(
            "feature/chat/ui/src/main/res/values-night/strings_chat_form.xml", resources()
        )
        self.assert_clean()

    def test_a_key_of_another_type_is_not_a_translation(self):
        self.write_pair(
            resources(string("chat_form_kinds")),
            resources('    <string-array name="chat_form_kinds"><item>x</item></string-array>'),
        )
        result = self.check()
        path = "strings_chat_form.xml"
        self.assert_problem(f"{VALUES}/{path}:3", "string-array", result)
        self.assert_problem(f"{VALUES_TR}/{path}:3", "string-array", result)

    def test_a_key_of_another_type_does_not_hide_a_missing_key(self):
        self.write_pair(
            resources(
                string("chat_form_items"),
                '    <plurals name="chat_form_items"><item quantity="other">x</item></plurals>',
            ),
            resources(string("chat_form_items")),
        )
        self.assert_problem(f"{VALUES}/strings_chat_form.xml:4", "plurals")

    def test_an_untranslatable_key_of_another_type_is_reported(self):
        self.write_pair(
            resources(string("chat_form_kinds", "x", ' translatable="false"')),
            resources('    <string-array name="chat_form_kinds"><item>x</item></string-array>'),
        )
        self.assert_problem(f"{VALUES_TR}/strings_chat_form.xml:3", "string-array")

    def test_each_resource_type_has_its_own_namespace(self):
        both = resources(
            string("chat_form_items"),
            '    <plurals name="chat_form_items"><item quantity="other">x</item></plurals>',
        )
        self.write_pair(both, both)
        self.assert_clean()

    def test_a_key_declared_twice_with_one_type_across_files_is_reported(self):
        for folder in (VALUES, VALUES_TR):
            self.write(f"{folder}/strings_chat.xml", resources(string("chat_form_title")))
            self.write(f"{folder}/strings_chat_form.xml", resources(string("chat_form_title")))
        self.assert_problem(f"{VALUES}/strings_chat_form.xml:3", "duplicate")

    def test_a_key_declared_twice_is_reported(self):
        self.write_pair(
            resources(string("chat_form_title"), string("chat_form_title")),
            resources(string("chat_form_title")),
        )
        self.assert_problem(f"{VALUES}/strings_chat_form.xml:4", "duplicate")


class PartnerFiles(TempRepoCase):
    script = "check_strings.py"

    def test_an_english_file_without_a_partner_is_reported(self):
        self.write(f"{VALUES}/strings_chat_form.xml", resources(string("chat_form_title")))
        self.assert_problem(f"{VALUES}/strings_chat_form.xml:1", "values-tr")

    def test_a_turkish_file_without_a_partner_is_reported(self):
        self.write(f"{VALUES_TR}/strings_chat_form.xml", resources(string("chat_form_title")))
        self.assert_problem(f"{VALUES_TR}/strings_chat_form.xml:1", "values/")

    def test_an_empty_contract_stub_still_needs_its_partner(self):
        self.write(f"{VALUES}/strings_chat_form.xml", resources())
        self.assert_problem(f"{VALUES}/strings_chat_form.xml:1", "values-tr")

    def test_an_empty_contract_stub_pair_passes(self):
        self.write(f"{VALUES}/strings_chat_form.xml", "<resources/>\n")
        self.write(f"{VALUES_TR}/strings_chat_form.xml", "<resources/>\n")
        self.assert_clean()

    def test_files_without_strings_need_no_partner(self):
        self.write(f"{VALUES}/colors.xml", resources('    <color name="red">#f00</color>'))
        self.write(f"{VALUES}/themes.xml", resources('    <style name="Theme.App"/>'))
        self.assert_clean()

    def test_translatable_strings_cannot_hide_in_another_file_name(self):
        self.write(f"{VALUES}/misc.xml", resources(string("misc_title")))
        self.assert_problem(f"{VALUES}/misc.xml:1", "values-tr")

    def test_a_skeleton_strings_xml_may_hold_only_untranslatable_keys(self):
        self.write(
            "app/src/main/res/values/strings.xml",
            resources(string("app_name", "phopencode", ' translatable="false"')),
        )
        self.assert_clean()

    def test_a_skeleton_strings_xml_may_be_a_translated_pair(self):
        for folder in ("values", "values-tr"):
            self.write(f"app/src/main/res/{folder}/strings.xml", resources(string("app_name")))
        self.assert_clean()

    def test_a_translatable_strings_xml_still_needs_its_partner(self):
        self.write("app/src/main/res/values/strings.xml", resources(string("app_name")))
        self.assert_problem("app/src/main/res/values/strings.xml:1", "values-tr")

    def test_a_turkish_only_file_needs_a_partner_whatever_it_holds(self):
        path = "app/src/main/res/values-tr/strings.xml"
        self.write(path, resources(string("app_name", "x", ' translatable="false"')))
        self.assert_problem(f"{path}:1", "values/")

    def test_a_turkish_only_file_of_another_name_needs_a_partner(self):
        self.write(
            f"{VALUES_TR}/misc.xml", resources(string("misc_name", "x", ' translatable="false"'))
        )
        self.assert_problem(f"{VALUES_TR}/misc.xml:1", "values/")

    def test_a_turkish_file_without_strings_needs_no_partner(self):
        self.write(f"{VALUES_TR}/colors.xml", resources('    <color name="red">#f00</color>'))
        self.assert_clean()

    def test_broken_xml_is_a_problem_and_not_a_crash(self):
        self.write(f"{VALUES}/strings_chat_form.xml", "<resources>\n<string name='a'>\n")
        self.write(f"{VALUES_TR}/strings_chat_form.xml", resources())
        self.assert_problem(f"{VALUES}/strings_chat_form.xml:3", "XML")

    def test_non_utf8_xml_is_a_problem_and_not_a_crash(self):
        self.write(f"{VALUES}/strings_chat_form.xml", b"<resources>\xff\xfe\x00</resources>")
        self.write(f"{VALUES_TR}/strings_chat_form.xml", resources())
        result = self.check()
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("XML", result.stdout)
        self.assertNotIn("Traceback", result.stderr)


class KeyPrefix(TempRepoCase):
    script = "check_strings.py"

    def write_both(self, name, *keys):
        for folder in (VALUES, VALUES_TR):
            self.write(f"{folder}/{name}", resources(*(string(key) for key in keys)))

    def test_a_key_with_the_file_prefix_passes(self):
        self.write_both("strings_chat_form.xml", "chat_form_title")
        self.assert_clean()

    def test_the_prefix_comes_from_the_file_name(self):
        self.write_both("strings_ui_common.xml", "ui_common_retry")
        self.assert_clean()

    def test_a_key_with_another_screen_prefix_is_reported(self):
        self.write_both("strings_chat_form.xml", "chat_composer_title")
        self.assert_problem(f"{VALUES}/strings_chat_form.xml:3", "chat_form_")

    def test_a_prefix_needs_the_separator(self):
        self.write_both("strings_chat_form.xml", "chat_formula")
        self.assert_problem(f"{VALUES}/strings_chat_form.xml:3", "chat_form_")

    def test_the_prefix_alone_is_not_a_key(self):
        self.write_both("strings_chat_form.xml", "chat_form_")
        self.assert_problem(f"{VALUES}/strings_chat_form.xml:3", "chat_form_")

    def test_an_untranslatable_key_follows_the_prefix_rule_too(self):
        self.write(
            f"{VALUES}/strings_chat_form.xml",
            resources(string("brand", "x", ' translatable="false"')),
        )
        self.write(f"{VALUES_TR}/strings_chat_form.xml", resources())
        self.assert_problem(f"{VALUES}/strings_chat_form.xml:3", "chat_form_")


class HardCodedText(TempRepoCase):
    script = "check_strings.py"

    FAULTY = {
        "positional text": 'Text("Send")',
        "named text": 'Text(text = "Send")',
        "basic text": 'BasicText("Send")',
        "turkish text": 'Text("Gönder")',
        "escaped turkish text": 'Text("G\\u00f6nder")',
        "template with words": 'Text("Hello $name")',
        "raw string": 'Text("""Send""")',
        "concatenation": 'Text("Hello " + name)',
        "elvis fallback": 'Text(name ?: "Unnamed")',
        "if branch": 'Text(if (name.isEmpty()) "Empty" else name)',
        "title": 'TopAppBar(title = "Chat")',
        "label": 'Chip(label = "Running")',
        "placeholder": 'Field(placeholder = "Search")',
        "suffixed name": 'Dialog(confirmText = "Delete")',
        "content description": 'Icon(icon, contentDescription = "Back")',
        "positional content description": 'Icon(icon, "Back")',
        "positional image description": 'Image(painter, "Logo")',
        "semantics": 'Box(Modifier.semantics { contentDescription = "Back" })',
        "snackbar": 'host.showSnackbar("Saved")',
        "toast": 'Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()',
        "inside a lambda": 'Button(onClick = {}) { Text("Send") }',
        "slot lambda": 'OutlinedTextField(value, {}, label = { Text("Host") })',
        "after a comment": 'Text(/* greeting */ "Send")',
        "grouped text": 'Text(("Send"))',
        "grouped named text": 'Text(text = ("Send"))',
        "nested groups": 'Text((("Send")))',
        "grouped second argument": 'Icon(icon, ("Back"))',
        "grouped title": 'TopAppBar(title = (("Chat")))',
        "grouped fallback": 'Text(name ?: ("Unnamed"))',
    }

    CLEAN = {
        "string resource": "Text(stringResource(R.string.chat_form_send))",
        "named resource": "Text(text = stringResource(R.string.chat_form_send))",
        "variable": "Text(name)",
        "empty": 'Text("")',
        "punctuation": 'Text("•")',
        "numbers": 'Text("123")',
        "only a template": 'Text("$name")',
        "templates and punctuation": 'Text("${name.length} / $name")',
        "escapes": 'Text("\\n")',
        "format string": 'Text("%d".format(name.length))',
        "null description": "Icon(icon, contentDescription = null)",
        "test tag": 'Box(Modifier.testTag("chat_list"))',
        "test tag in text": 'Text(name, modifier = Modifier.testTag("chat_title"))',
        "route": 'navController.navigate("chat/detail")',
        "named route": 'composable(route = "chat") { }',
        "log tag": 'Log.d("ChatScreen", "opened")',
        "named log": 'Log.d(tag = "ChatScreen", message = "opened")',
        "exception": 'throw IllegalStateException(message = "never")',
        "check": 'check(name.isNotEmpty()) { "name" }',
        "remember key": 'val state = remember("key") { 1 }',
        "state value": 'var draft by remember { mutableStateOf("seed") }',
        "local variable": 'val label = "local"',
        "state assignment": 'draft.text = "seed"',
        "typed declaration": 'val title: String = "local"',
        "animation label": 'val a by animateFloatAsState(1f, label = "alpha")',
        "transition label": 'val t = updateTransition(name, label = "row")',
        "infinite label": 'val t = rememberInfiniteTransition(label = "pulse")',
        "transition child label": 'val c by t.animateColor(label = "tint") { Color.Red }',
        "crossfade label": 'Crossfade(name, label = "body") { }',
        "animated content label": 'AnimatedContent(name, label = "body") { }',
        "comparison": 'Chip(selected = name == "chat")',
        "nested non ui call": 'Text(name.removePrefix("chat/"))',
        "resource argument": 'Text(stringResource(R.string.a, "chat"))',
        "domain type": 'Row(AssistantBlock.Text("sample"))',
        "domain named argument": 'Row(ServerEvent.Created(title = "sample"))',
        "line comment": '// Text("Send")',
        "block comment": '/* Text("Send") /* nested */ Text("Send") */',
        "commented in a string": 'Log.d("x", "// Text(\\"Send\\")")',
        "allowed brand name": 'Text("opencode") // strings-ok: product name',
        "call in a text": 'Text(foo("Send"))',
        "call in a group": 'Text((foo("Send")))',
        "group in a call": 'Text(foo(("Send")))',
        "call on a call": 'Text(make()("Send"))',
        "call on an index": 'Text(list[0]("Send"))',
        "generic call": 'Text(make<String>("Send"))',
        "group in a condition": 'Text(if (("Send").isEmpty()) name else name)',
        "grouped comparison": 'Text(if ((name == "chat")) name else name)',
        "grouped animation label": 'val a by animateFloatAsState(1f, label = ("alpha"))',
        "grouped log": 'Log.d(tag = ("ChatScreen"), message = ("opened"))',
        "grouped key": 'val state = remember(("key")) { 1 }',
    }

    def test_faulty_samples_are_reported_at_their_line(self):
        for name, body in self.FAULTY.items():
            with self.subTest(name):
                self.write(SCREEN, composable(body))
                self.assert_problem(f"{SCREEN}:7", "hard-coded")

    def test_clean_samples_pass(self):
        for name, body in self.CLEAN.items():
            with self.subTest(name):
                self.write(SCREEN, composable(body))
                self.assert_clean()

    def test_the_allow_comment_needs_a_reason(self):
        self.write(SCREEN, composable('Text("opencode") // strings-ok:'))
        self.assert_problem(f"{SCREEN}:7", "hard-coded")

    def test_the_allow_comment_covers_only_its_own_line(self):
        self.write(SCREEN, composable('// strings-ok: product name\nText("opencode")'))
        self.assert_problem(f"{SCREEN}:8", "hard-coded")

    def test_multiline_calls_report_the_line_of_the_literal(self):
        self.write(SCREEN, composable('Text(\n    modifier = Modifier,\n    text = "Send",\n)'))
        self.assert_problem(f"{SCREEN}:9", "hard-coded")

    def test_screen_stubs_in_navigation_graphs_are_covered(self):
        self.write(
            SCREEN,
            "import androidx.navigation.compose.composable\n"
            "fun NavGraphBuilder.chatGraph() {\n"
            '    composable<Route> { Text("Stub") }\n'
            "}\n",
        )
        self.assert_problem(f"{SCREEN}:3", "hard-coded")

    def test_previews_are_skipped(self):
        self.write(
            SCREEN,
            "import androidx.compose.runtime.Composable\n"
            '@Preview(name = "Dark")\n'
            "@Composable\n"
            "private fun ScreenPreview() {\n"
            '    Text("Sample")\n'
            "}\n",
        )
        self.assert_clean()

    def test_expression_body_previews_and_preview_parameters_are_skipped(self):
        self.write(
            SCREEN,
            "import androidx.compose.runtime.Composable\n"
            "@PreviewLightDark @Composable\n"
            'private fun A() = AppTheme { Text("Sample") }\n'
            "@Preview @Composable\n"
            'private fun B(@PreviewParameter(P::class) x: Int) { Text("Sample") }\n',
        )
        self.assert_clean()

    def test_the_composable_after_a_preview_is_still_checked(self):
        self.write(
            SCREEN,
            "import androidx.compose.runtime.Composable\n"
            "@Preview @Composable\n"
            'private fun A() = AppTheme { Text("Sample") }\n'
            "@Composable\n"
            'fun B() { Text("Real") }\n',
        )
        self.assert_problem(f"{SCREEN}:5", "hard-coded")

    STRING_CONTENTS = ("{", "}", "(", ")", "[", "]", "=", "fun", "class", "object", "@", "val")
    PREVIEW_FORMS = {
        "body": "@Preview @Composable\nprivate fun A() {\n    Text(\"%s\")\n    Text(\"Sample\")\n}",
        "expression": (
            "@Preview @Composable\n"
            "private fun A() = AppTheme {\n    Text(\"%s\")\n    Text(\"Sample\")\n}"
        ),
        "bare expression": "@Preview @Composable\nprivate fun A() = Text(\"%s\" + \"Sample\")",
        "parameter default": (
            "@Preview @Composable\n"
            "private fun A(x: String = \"%s\") {\n    Text(\"Sample\")\n}"
        ),
        "annotation argument": (
            "@Preview(name = \"%s\") @Composable\n"
            "private fun A() {\n    Text(\"Sample\")\n}"
        ),
    }

    def test_string_contents_do_not_end_or_extend_a_preview(self):
        for form, template in self.PREVIEW_FORMS.items():
            for content in self.STRING_CONTENTS:
                with self.subTest(form=form, content=content):
                    preview = template.replace("%s", content)
                    source = f"import androidx.compose.runtime.Composable\n{preview}\n"
                    self.write(SCREEN, source)
                    self.assert_clean()

                    last = source.count("\n") + 2
                    self.write(SCREEN, source + '@Composable\nfun Real() { Text("Real") }\n')
                    self.assert_problem(f"{SCREEN}:{last}", "hard-coded")

    def test_string_contents_do_not_open_a_preview(self):
        self.write(
            SCREEN,
            "import androidx.compose.runtime.Composable\n"
            'val at = "@"\n'
            "PreviewData.load()\n"
            "@Composable\n"
            'fun Real() { Text("Real") }\n',
        )
        self.assert_problem(f"{SCREEN}:5", "hard-coded")

    def test_string_contents_do_not_mark_a_file_as_compose(self):
        self.write(SCREEN, 'val at = "@"\nComposable(title = "Sample")\n')
        self.assert_clean()

    def test_string_contents_do_not_qualify_the_next_call(self):
        for content in (".", "?.", "fun", "class"):
            with self.subTest(content):
                self.write(SCREEN, composable(f'val s = "{content}"\nText("Send")'))
                self.assert_problem(f"{SCREEN}:8", "hard-coded")

    def test_string_contents_do_not_make_a_declaration(self):
        for content in ("val", "var"):
            with self.subTest(content):
                self.write(
                    SCREEN,
                    composable(
                        f'Box(Modifier.semantics {{\n    val s = "{content}"\n'
                        '    contentDescription = "Back"\n})'
                    ),
                )
                self.assert_problem(f"{SCREEN}:9", "hard-coded")

    def test_a_function_named_with_backticks_is_not_a_keyword(self):
        self.write(
            SCREEN,
            "import androidx.compose.runtime.Composable\n"
            "@Preview @Composable\n"
            'private fun `fun`() { Text("Sample") }\n'
            "@Composable\n"
            'fun Real() { `fun`()\n Text("Real") }\n',
        )
        self.assert_problem(f"{SCREEN}:6", "hard-coded")

    def test_test_sources_are_skipped(self):
        for source_set in ("test", "androidTest", "testDebug", "testFixtures"):
            path = f"feature/chat/ui/src/{source_set}/kotlin/ScreenTest.kt"
            self.write(path, composable('Text("Send")'))
        self.assert_clean()

    def test_a_package_named_build_inside_sources_is_checked(self):
        path = "feature/chat/ui/src/main/kotlin/io/example/build/Screen.kt"
        self.write(path, composable('Text("Send")'))
        self.assert_problem(f"{path}:7", "hard-coded")

    def test_files_without_compose_are_skipped(self):
        self.write(SCREEN, 'class Notice(val title: String)\nval n = Notice(title = "Sample")\n')
        self.assert_clean()

    def test_compose_mentioned_outside_code_does_not_enable_the_check(self):
        sample = 'val n = Notice(title = "Sample")\n'
        for header in (
            "// import androidx.compose.runtime.Composable\n",
            "/*\nimport androidx.compose.runtime.Composable\n*/\n",
            '/**\n * @Composable\n */\n',
            'val doc = """\nimport androidx.compose.runtime.Composable\n"""\n',
            'val doc = """\nimport androidx.navigation.compose.composable\n"""\n',
        ):
            with self.subTest(header):
                self.write(SCREEN, header + sample)
                self.assert_clean()

    def test_every_form_of_a_compose_import_enables_the_check(self):
        for line in (
            "import androidx.compose.material3.Text",
            "import androidx.compose.material3.*",
            "    import androidx.compose.material3.Text",
            "import androidx.navigation.compose.composable",
            "import androidx/*c*/.compose.material3.Text",
            "import androidx.compose.material3.Text as M3Text",
        ):
            with self.subTest(line):
                self.write(SCREEN, f'{line}\nval n = Notice(title = "Sample")\n')
                self.assert_problem(f"{SCREEN}:2", "hard-coded")

    def test_lookalike_imports_do_not_enable_the_check(self):
        self.write(
            SCREEN,
            "import androidx.composefoo.Bar\n"
            "import io.example.androidx.compose.Bar\n"
            "import androidx.navigation.Foo\n"
            'val n = Notice(title = "Sample")\n',
        )
        self.assert_clean()

    def test_the_message_names_the_call_and_the_way_out(self):
        self.write(SCREEN, composable('Text("Send")'))
        output = self.check().stdout
        self.assertIn("Text(...)", output)
        self.assertIn("strings-ok", output)

    def test_unterminated_and_non_utf8_sources_do_not_crash(self):
        samples = (
            composable('Text("Send'),
            composable('val c = \'"\'\nval x = """open'),
            composable('Text("${'),
            composable("/* open"),
            b"@Composable\nfun A() { Text(\"\xff\xfeSend\") }\n",
            b"\xff\xfe\x00\x01",
        )
        for sample in samples:
            with self.subTest(sample=sample):
                self.write(SCREEN, sample)
                result = self.check()
                self.assertIn(result.returncode, (0, 1), result.stderr)
                self.assertNotIn("Traceback", result.stderr)

    def test_a_character_literal_quote_does_not_open_a_string(self):
        self.write(SCREEN, composable("val c = '\"'\nval d = '\\''\nText(\"Send\")"))
        self.assert_problem(f"{SCREEN}:9", "hard-coded")


if __name__ == "__main__":
    unittest.main()
