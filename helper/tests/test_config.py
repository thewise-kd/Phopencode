"""Tests for notifyd.config: the service's 0600 JSON configuration file."""
import ast
import io
import json
import logging
import os
import tempfile
import traceback
import unittest
import uuid
from pathlib import Path
from unittest import mock

from notifyd import config
from notifyd.config import Config, ConfigError, Secret

# Secrets are built at runtime: the repository is public and scanned for literals.
SENTINEL_PASSWORD = "pw-" + uuid.uuid4().hex
SENTINEL_TOPIC = "tp-" + uuid.uuid4().hex

SERVER_URL = "http://127.0.0.1:4096"


def valid_fields():
    return {
        "server_url": SERVER_URL,
        "username": "opencode",
        "password": SENTINEL_PASSWORD,
        "ntfy_server": "https://ntfy.example.org",
        "ntfy_topic": SENTINEL_TOPIC,
    }


def all_text(error):
    """Everything a log line or a traceback could show about an exception."""
    parts = [str(error), repr(error), repr(error.args)]
    parts.extend(traceback.format_exception(type(error), error, error.__traceback__))
    cause = error.__cause__ or error.__context__
    while cause is not None:
        parts.append(repr(cause))
        parts.append(repr(getattr(cause, "doc", "")))
        cause = cause.__cause__ or cause.__context__
    return "\n".join(parts)


class ConfigFileTest(unittest.TestCase):
    def setUp(self):
        self._dir = tempfile.TemporaryDirectory()
        self.addCleanup(self._dir.cleanup)
        self.dir = Path(self._dir.name)

    def write(self, content, mode=0o600, name="notifyd.json"):
        path = self.dir / name
        if isinstance(content, dict):
            content = json.dumps(content)
        if isinstance(content, str):
            content = content.encode("utf-8")
        path.write_bytes(content)
        os.chmod(path, mode)
        return path

    def assert_rejected(self, path, *fragments):
        with self.assertRaises(ConfigError) as caught:
            config.load(path)
        text = all_text(caught.exception)
        self.assertNotIn(SENTINEL_PASSWORD, text)
        self.assertNotIn(SENTINEL_TOPIC, text)
        for fragment in fragments:
            self.assertIn(fragment, str(caught.exception))
        return caught.exception

    # Loading a good file

    def test_loads_all_fields(self):
        loaded = config.load(self.write(valid_fields()))
        self.assertEqual(loaded.server_url, SERVER_URL)
        self.assertEqual(loaded.username, "opencode")
        self.assertEqual(loaded.password.reveal(), SENTINEL_PASSWORD)
        self.assertEqual(loaded.ntfy_server, "https://ntfy.example.org")
        self.assertEqual(loaded.ntfy_topic.reveal(), SENTINEL_TOPIC)

    def test_accepts_a_path_string(self):
        loaded = config.load(str(self.write(valid_fields())))
        self.assertEqual(loaded.server_url, SERVER_URL)

    def test_optional_fields_take_defaults(self):
        fields = valid_fields()
        del fields["username"]
        del fields["ntfy_server"]
        loaded = config.load(self.write(fields))
        self.assertEqual(loaded.username, "opencode")
        self.assertEqual(loaded.ntfy_server, "https://ntfy.sh")
        self.assertEqual(config.DEFAULT_NTFY_SERVER, "https://ntfy.sh")
        self.assertEqual(config.DEFAULT_USERNAME, "opencode")

    def test_trailing_slashes_are_removed(self):
        fields = valid_fields()
        fields["server_url"] = SERVER_URL + "/"
        fields["ntfy_server"] = "https://ntfy.example.org/base/"
        loaded = config.load(self.write(fields))
        self.assertEqual(loaded.server_url, SERVER_URL)
        self.assertEqual(loaded.ntfy_server, "https://ntfy.example.org/base")

    # File mode and ownership

    def test_rejects_modes_other_than_0600(self):
        for mode in (0o644, 0o640, 0o604, 0o700, 0o660, 0o400, 0o602, 0o6600):
            with self.subTest(mode=oct(mode)):
                path = self.write(valid_fields(), mode=mode, name="m%o.json" % mode)
                self.assert_rejected(path, "0600")

    def test_rejects_a_file_owned_by_someone_else(self):
        path = self.write(valid_fields())
        with mock.patch.object(config.os, "getuid", return_value=os.getuid() + 1):
            self.assert_rejected(path, "owned")

    def test_rejects_a_symlink(self):
        target = self.write(valid_fields())
        link = self.dir / "link.json"
        link.symlink_to(target)
        self.assert_rejected(link)

    def test_rejects_a_directory(self):
        path = self.dir / "dir.json"
        path.mkdir(mode=0o700)
        os.chmod(path, 0o600)
        self.assert_rejected(path)

    def test_missing_file_is_a_config_error(self):
        error = self.assert_rejected(self.dir / "absent.json")
        self.assertIn("absent.json", str(error))

    def test_missing_file_says_not_found(self):
        self.assert_rejected(self.dir / "absent.json", "not found")

    def test_read_failures_are_config_errors(self):
        path = self.write(valid_fields())
        for name in ("read", "fstat"):
            with self.subTest(call=name):
                failing = mock.patch.object(
                    config.os, name, side_effect=OSError(5, "Input/output error")
                )
                with failing:
                    error = self.assert_rejected(path, str(path))
                self.assertIsNone(error.__cause__)
                self.assertTrue(error.__context__ is None or error.__suppress_context__)

    def test_open_failures_other_than_missing_are_config_errors(self):
        path = self.write(valid_fields())
        with mock.patch.object(config.os, "open", side_effect=OSError(13, "Permission denied")):
            self.assert_rejected(path, str(path))

    def test_strings_that_are_not_utf8_are_rejected(self):
        for field in ("server_url", "username", "password", "ntfy_server", "ntfy_topic"):
            with self.subTest(field=field):
                fields = valid_fields()
                fields[field] = fields[field] + "\ud800"
                # json.dumps writes the lone surrogate as a \ud800 escape.
                error = self.assert_rejected(self.write(fields), field)
                self.assertNotIn("\ud800", str(error))

    def test_rejects_an_oversized_file(self):
        fields = valid_fields()
        fields["password"] = "x" * (config.MAX_FILE_BYTES + 1)
        self.assert_rejected(self.write(fields), "large")

    # Content

    def test_missing_required_fields_are_named(self):
        for field in ("server_url", "password", "ntfy_topic"):
            with self.subTest(field=field):
                fields = valid_fields()
                del fields[field]
                self.assert_rejected(self.write(fields), field)

    def test_empty_required_fields_are_rejected(self):
        for field in ("server_url", "username", "password", "ntfy_server", "ntfy_topic"):
            with self.subTest(field=field):
                fields = valid_fields()
                fields[field] = ""
                self.assert_rejected(self.write(fields), field)

    def test_non_string_fields_are_rejected(self):
        for field in ("server_url", "username", "password", "ntfy_server", "ntfy_topic"):
            for value in (None, 7, ["a"], {"a": "b"}, True):
                with self.subTest(field=field, value=value):
                    fields = valid_fields()
                    fields[field] = value
                    self.assert_rejected(self.write(fields), field)

    def test_unknown_fields_are_rejected(self):
        fields = valid_fields()
        fields["passwrod"] = "x"
        self.assert_rejected(self.write(fields), "passwrod")

    def test_duplicate_fields_are_rejected(self):
        text = '{"password": "%s", "password": "y", "server_url": "%s", "ntfy_topic": "%s"}' % (
            SENTINEL_PASSWORD,
            SERVER_URL,
            SENTINEL_TOPIC,
        )
        self.assert_rejected(self.write(text), "password")

    def test_rejects_invalid_json_without_echoing_it(self):
        text = '{"password": "%s", "ntfy_topic": "%s", ' % (SENTINEL_PASSWORD, SENTINEL_TOPIC)
        error = self.assert_rejected(self.write(text), "JSON")
        self.assertIsNone(error.__cause__)
        self.assertTrue(error.__context__ is None or error.__suppress_context__)

    def test_rejects_invalid_utf8(self):
        self.assert_rejected(self.write(b'{"server_url": "\xff\xfe"}'))

    def test_rejects_a_non_object(self):
        for text in ("[]", '"x"', "7", "null"):
            with self.subTest(text=text):
                self.assert_rejected(self.write(text), "object")

    def test_server_url_must_be_http_or_https_with_a_host(self):
        for url in (
            "ftp://127.0.0.1:4096",
            "127.0.0.1:4096",
            "http://",
            "http:///path",
            "http://127.0.0.1:4096?x=1",
            "http://127.0.0.1:4096#x",
            "http://127.0.0.1:99999",
            "http://127.0.0.1:4096/a b",
        ):
            with self.subTest(url=url):
                fields = valid_fields()
                fields["server_url"] = url
                self.assert_rejected(self.write(fields), "server_url")

    def test_urls_with_credentials_are_rejected_without_echoing_them(self):
        for field, url in (
            ("server_url", "http://user:%s@127.0.0.1:4096" % SENTINEL_PASSWORD),
            ("ntfy_server", "https://user:%s@ntfy.example.org" % SENTINEL_PASSWORD),
        ):
            with self.subTest(field=field):
                fields = valid_fields()
                fields[field] = url
                self.assert_rejected(self.write(fields), field)

    def test_ntfy_server_must_be_https(self):
        fields = valid_fields()
        fields["ntfy_server"] = "http://ntfy.example.org"
        self.assert_rejected(self.write(fields), "ntfy_server", "https")

    def test_ntfy_topic_must_be_a_valid_topic_and_is_not_echoed(self):
        for topic in (SENTINEL_TOPIC + "/x", SENTINEL_TOPIC + " ", "a" * 65, SENTINEL_TOPIC + "?"):
            with self.subTest(length=len(topic)):
                fields = valid_fields()
                fields["ntfy_topic"] = topic
                self.assert_rejected(self.write(fields), "ntfy_topic")

    def test_username_must_not_hold_a_colon_or_control_characters(self):
        for username in ("a:b", "a\nb", "a\x00b"):
            with self.subTest(username=repr(username)):
                fields = valid_fields()
                fields["username"] = username
                self.assert_rejected(self.write(fields), "username")

    def test_password_must_not_hold_control_characters(self):
        for suffix in ("\n", "\r", "\x00"):
            with self.subTest(suffix=repr(suffix)):
                fields = valid_fields()
                fields["password"] = SENTINEL_PASSWORD + suffix
                self.assert_rejected(self.write(fields), "password")

    # Secrets stay out of logs

    def test_repr_and_str_hide_the_secrets(self):
        loaded = config.load(self.write(valid_fields()))
        for text in (
            repr(loaded),
            str(loaded),
            repr(loaded.password),
            str(loaded.password),
            "%s %r" % (loaded.ntfy_topic, loaded.ntfy_topic),
            "{} {!r} {}".format(loaded.password, loaded.password, loaded),
            f"{loaded.password}{loaded.ntfy_topic!r}",
            repr(vars(loaded)) if hasattr(loaded, "__dict__") else "",
        ):
            self.assertNotIn(SENTINEL_PASSWORD, text)
            self.assertNotIn(SENTINEL_TOPIC, text)
        self.assertIn(SERVER_URL, repr(loaded))

    def test_logging_a_config_hides_the_secrets(self):
        stream = io.StringIO()
        handler = logging.StreamHandler(stream)
        logger = logging.getLogger("notifyd.test")
        logger.addHandler(handler)
        logger.setLevel(logging.DEBUG)
        self.addCleanup(logger.removeHandler, handler)
        loaded = config.load(self.write(valid_fields()))
        logger.debug("config %s %r", loaded, loaded)
        logger.info("password %s topic %s", loaded.password, loaded.ntfy_topic)
        logger.info("fields %s", [loaded.password, loaded.ntfy_topic])
        output = stream.getvalue()
        self.assertIn("config", output)
        self.assertNotIn(SENTINEL_PASSWORD, output)
        self.assertNotIn(SENTINEL_TOPIC, output)

    def test_loading_logs_no_secrets(self):
        stream = io.StringIO()
        handler = logging.StreamHandler(stream)
        root = logging.getLogger()
        old_level = root.level
        root.addHandler(handler)
        root.setLevel(logging.DEBUG)
        self.addCleanup(root.removeHandler, handler)
        self.addCleanup(root.setLevel, old_level)
        config.load(self.write(valid_fields()))
        bad = valid_fields()
        bad["ntfy_topic"] = SENTINEL_TOPIC + "/"
        with self.assertRaises(ConfigError):
            config.load(self.write(bad, name="bad.json"))
        output = stream.getvalue()
        self.assertNotIn(SENTINEL_PASSWORD, output)
        self.assertNotIn(SENTINEL_TOPIC, output)


class SecretTest(unittest.TestCase):
    def test_reveal_returns_the_value(self):
        self.assertEqual(Secret(SENTINEL_PASSWORD).reveal(), SENTINEL_PASSWORD)

    def test_equality_compares_values(self):
        self.assertEqual(Secret(SENTINEL_PASSWORD), Secret(SENTINEL_PASSWORD))
        self.assertNotEqual(Secret(SENTINEL_PASSWORD), Secret(SENTINEL_TOPIC))
        self.assertNotEqual(Secret(SENTINEL_PASSWORD), SENTINEL_PASSWORD)

    def test_comparing_lone_surrogates_does_not_raise(self):
        self.assertEqual(Secret("a\ud800"), Secret("a\ud800"))
        self.assertNotEqual(Secret("a\ud800"), Secret("a\ud801"))

    def test_has_no_instance_dict(self):
        self.assertFalse(hasattr(Secret(SENTINEL_PASSWORD), "__dict__"))

    def test_rejects_non_strings(self):
        with self.assertRaises(TypeError):
            Secret(7)


class FromMappingTest(unittest.TestCase):
    def test_builds_a_config_from_a_mapping(self):
        loaded = config.from_mapping(valid_fields())
        self.assertIsInstance(loaded, Config)
        self.assertEqual(loaded.password.reveal(), SENTINEL_PASSWORD)

    def test_reports_every_missing_field_at_once(self):
        with self.assertRaises(ConfigError) as caught:
            config.from_mapping({})
        message = str(caught.exception)
        for field in ("server_url", "password", "ntfy_topic"):
            self.assertIn(field, message)


class DefaultPathTest(unittest.TestCase):
    def test_uses_xdg_config_home_when_absolute(self):
        path = config.default_path({"XDG_CONFIG_HOME": "/cfg", "HOME": "/home/u"})
        self.assertEqual(path, Path("/cfg/phopencode/notifyd.json"))

    def test_falls_back_to_home_config(self):
        for environ in ({"HOME": "/home/u"}, {"HOME": "/home/u", "XDG_CONFIG_HOME": "rel"}):
            with self.subTest(environ=environ):
                path = config.default_path(environ)
                self.assertEqual(path, Path("/home/u/.config/phopencode/notifyd.json"))

    def test_needs_a_home(self):
        with self.assertRaises(ConfigError):
            config.default_path({})


class SourceTest(unittest.TestCase):
    def test_parses_as_python_3_9(self):
        source = Path(config.__file__).read_text(encoding="utf-8")
        ast.parse(source, feature_version=(3, 9))

    def test_imports_only_the_standard_library(self):
        source = Path(config.__file__).read_text(encoding="utf-8")
        allowed = {
            "__future__", "errno", "hmac", "json", "os", "pathlib", "re", "stat", "typing",
            "urllib",
        }
        for node in ast.walk(ast.parse(source)):
            if isinstance(node, ast.Import):
                names = [alias.name for alias in node.names]
            elif isinstance(node, ast.ImportFrom):
                names = [node.module or ""]
            else:
                continue
            for name in names:
                self.assertIn(name.split(".")[0], allowed)


if __name__ == "__main__":
    unittest.main()
