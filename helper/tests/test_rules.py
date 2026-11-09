"""Tests for notifyd.rules: which server events become a notification, and throttling."""

import ast
import copy
import io
import json
import logging
import unittest
from pathlib import Path

from notifyd import rules
from notifyd.rules import (
    CLICK_URL,
    EVENT_TYPES,
    KIND_FAILED,
    KIND_FINISHED,
    KIND_FORM,
    KIND_PERMISSION,
    Notification,
    Rules,
    Throttle,
)

REPO = Path(__file__).resolve().parents[2]
FIXTURES = REPO / "core" / "testing" / "src" / "main" / "resources" / "fixtures"
MODULE = Path(rules.__file__)

SENTINEL = "SENTINEL-7f3a"


def load_flow(name):
    path = FIXTURES / name / "events.jsonl"
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def all_flows():
    return sorted(p.parent.name for p in FIXTURES.glob("*/events.jsonl"))


class FakeClock:
    def __init__(self, now=1000.0):
        self.now = now

    def __call__(self):
        return self.now

    def advance(self, seconds):
        self.now += seconds


def unthrottled():
    return Rules(throttle=None)


def kinds(rule_set, events):
    out = []
    for event in events:
        note = rule_set.handle(event)
        if note is not None:
            out.append(note.kind)
    return out


def sentinelize(value, salt=""):
    """Replace every string in value with a sentinel string, keeping equal strings equal.

    The event type stays, so the rules still recognize the event. Every other string,
    including keys' values such as IDs, titles, paths and messages, becomes a sentinel.
    """
    if isinstance(value, dict):
        out = {}
        for key, item in value.items():
            if key == "type" and salt == "":
                out[key] = item
            else:
                out[key] = sentinelize(item, salt + "/" + key)
        out["extra" + SENTINEL] = SENTINEL + "-extra"
        return out
    if isinstance(value, list):
        return [sentinelize(item, salt + "/i") for item in value] + [SENTINEL + "-item"]
    if isinstance(value, str):
        # Same input, same output, so parentID still points at the right sessionID.
        return "%s-%08x" % (SENTINEL, abs(hash(value)) & 0xFFFFFFFF)
    return value


def text_of(note):
    return " ".join(
        [note.kind, note.title, note.body, note.priority, note.click, repr(note), str(note)]
    )


class FixtureShapes(unittest.TestCase):
    """The recorded samples have the shapes the rules rely on."""

    def test_fixtures_are_present(self):
        self.assertTrue(FIXTURES.is_dir(), FIXTURES)
        self.assertIn("subagent", all_flows())

    def test_event_types_are_in_the_server_spec(self):
        spec = (FIXTURES / "spec" / "openapi.json").read_text(encoding="utf-8")
        for event_type in EVENT_TYPES:
            self.assertIn('"%s"' % event_type, spec)


class Notified(unittest.TestCase):
    def test_form_created_is_notified(self):
        self.assertEqual([KIND_FORM, KIND_FORM, KIND_FINISHED], kinds(unthrottled(), load_flow("form-question")))

    def test_all_form_shapes_are_notified(self):
        self.assertEqual([KIND_FORM] * 3, kinds(unthrottled(), load_flow("form-fields")))

    def test_permission_asked_is_notified(self):
        self.assertEqual(
            [KIND_PERMISSION] * 3 + [KIND_FINISHED], kinds(unthrottled(), load_flow("permission"))
        )

    def test_root_session_finish_is_notified(self):
        self.assertEqual([KIND_FINISHED], kinds(unthrottled(), load_flow("text")))

    def test_root_session_error_is_notified(self):
        self.assertEqual([KIND_FAILED, KIND_FAILED], kinds(unthrottled(), load_flow("error")))

    def test_subagent_finish_is_not_notified(self):
        events = load_flow("subagent")
        succeeded = [e for e in events if e["type"] == "session.execution.succeeded"]
        self.assertEqual(2, len(succeeded))
        self.assertEqual([KIND_FINISHED], kinds(unthrottled(), events))

    def test_the_root_one_is_notified_not_the_child(self):
        events = load_flow("subagent")
        child = next(
            e["data"]["sessionID"] for e in events if e["type"] == "session.created" and e["data"].get("parentID")
        )
        rule_set = unthrottled()
        notified_for = []
        for event in events:
            if rule_set.handle(event) is not None:
                notified_for.append(event["data"]["sessionID"])
        self.assertNotIn(child, notified_for)
        self.assertEqual(1, len(notified_for))

    def test_subagent_permission_is_notified(self):
        self.assertEqual(
            [KIND_PERMISSION, KIND_FINISHED], kinds(unthrottled(), load_flow("subagent-permission"))
        )

    def test_subagent_error_is_not_notified(self):
        rule_set = unthrottled()
        rule_set.handle(event("session.created", sessionID="ses_child", parentID="ses_root"))
        self.assertIsNone(
            rule_set.handle(event("session.execution.failed", sessionID="ses_child", error={"type": "x"}))
        )
        self.assertIsNotNone(
            rule_set.handle(event("session.execution.failed", sessionID="ses_root", error={"type": "x"}))
        )

    def test_unknown_session_counts_as_root(self):
        # A session created before the service started has no session.created in the stream.
        note = unthrottled().handle(event("session.execution.succeeded", sessionID="ses_old"))
        self.assertEqual(KIND_FINISHED, note.kind)

    def test_forked_session_stays_root(self):
        # The server's fork carries `fork`, not `parentID`: it is a top-level session.
        self.assertEqual([KIND_FINISHED], kinds(unthrottled(), load_flow("lifecycle")))
        rule_set = unthrottled()
        for e in load_flow("lifecycle"):
            rule_set.handle(e)
        forked = [e["data"]["sessionID"] for e in load_flow("lifecycle") if e["type"] == "session.forked"]
        live = forked[1]  # forked[0] is deleted in the flow
        note = rule_set.handle(event("session.execution.succeeded", sessionID=live))
        self.assertIsNotNone(note)

    def test_mark_child_suppresses_finish(self):
        rule_set = unthrottled()
        rule_set.mark_child("ses_x")
        self.assertIsNone(rule_set.handle(event("session.execution.succeeded", sessionID="ses_x")))

    def test_deleted_child_is_forgotten(self):
        rule_set = unthrottled()
        rule_set.handle(event("session.created", sessionID="ses_c", parentID="ses_p"))
        rule_set.handle(event("session.deleted", sessionID="ses_c"))
        self.assertEqual(0, rule_set.tracked_children())

    def test_child_set_is_bounded(self):
        rule_set = Rules(throttle=None, max_children=3)
        for n in range(10):
            rule_set.handle(event("session.created", sessionID="ses_%d" % n, parentID="ses_p"))
        self.assertEqual(3, rule_set.tracked_children())
        self.assertIsNone(rule_set.handle(event("session.execution.succeeded", sessionID="ses_9")))

    def created_children(self, rule_set, *names):
        for name in names:
            rule_set.handle(event("session.created", sessionID=name, parentID="ses_p"))

    def test_active_child_is_not_evicted(self):
        for refresh in (
            event("session.execution.started", sessionID="ses_a"),
            event("form.created", form={"id": "f", "sessionID": "ses_a"}),
            event("permission.asked", id="p", sessionID="ses_a"),
        ):
            rule_set = Rules(throttle=None, max_children=2)
            self.created_children(rule_set, "ses_a", "ses_b")
            rule_set.handle(refresh)
            self.created_children(rule_set, "ses_c")
            self.assertEqual(2, rule_set.tracked_children())
            self.assertIsNone(
                rule_set.handle(event("session.execution.succeeded", sessionID="ses_a")), refresh["type"]
            )
            # ses_b was the least recently seen, so it went.
            self.assertIsNotNone(rule_set.handle(event("session.execution.succeeded", sessionID="ses_b")))

    def test_started_is_in_event_types(self):
        self.assertIn("session.execution.started", EVENT_TYPES)

    def test_started_never_notifies_or_marks_root(self):
        rule_set = unthrottled()
        self.assertIsNone(rule_set.handle(event("session.execution.started", sessionID="ses_r")))
        self.assertEqual(0, rule_set.tracked_children())

    def test_null_or_empty_parent_is_root(self):
        rule_set = unthrottled()
        rule_set.handle(event("session.created", sessionID="ses_a", parentID=None))
        rule_set.handle(event("session.created", sessionID="ses_b", parentID=""))
        self.assertEqual(0, rule_set.tracked_children())


class NotNotified(unittest.TestCase):
    def test_interrupt_is_not_notified(self):
        self.assertEqual([], kinds(unthrottled(), load_flow("interrupt")))

    def test_other_events_are_not_notified(self):
        quiet = {
            "session.execution.started",
            "session.execution.interrupted",
            "session.retry.scheduled",
            "session.step.failed",
            "form.replied",
            "form.cancelled",
            "permission.replied",
            "server.connected",
            "session.text.delta",
        }
        rule_set = unthrottled()
        for flow in all_flows():
            for e in load_flow(flow):
                if e["type"] in quiet:
                    self.assertIsNone(rule_set.handle(e), e["type"])

    def test_every_notified_event_is_in_event_types(self):
        rule_set = unthrottled()
        for flow in all_flows():
            for e in load_flow(flow):
                if rule_set.handle(e) is not None:
                    self.assertIn(e["type"], EVENT_TYPES)

    def test_malformed_events_are_ignored(self):
        rule_set = unthrottled()
        bad = [
            None,
            42,
            "permission.asked",
            [],
            {},
            {"type": None},
            {"type": 5, "data": {}},
            {"type": "permission.asked"},
            {"type": "permission.asked", "data": None},
            {"type": "permission.asked", "data": "x"},
            {"type": "form.created", "data": {}},
            {"type": "form.created", "data": {"form": "x"}},
            {"type": "session.execution.succeeded", "data": {}},
            {"type": "session.execution.succeeded", "data": {"sessionID": 7}},
            {"type": "session.execution.failed", "data": {"sessionID": ""}},
            {"type": "session.created", "data": {"sessionID": ["x"], "parentID": "p"}},
            {"type": "session.created", "data": {"sessionID": "s", "parentID": {"x": 1}}},
            {"type": "permission.asked", "data": {"sessionID": None}},
        ]
        for item in bad:
            self.assertIsNone(rule_set.handle(item), repr(item))
        self.assertEqual(0, rule_set.tracked_children())


class FixedText(unittest.TestCase):
    def test_text_is_fixed_per_kind(self):
        seen = {}
        rule_set = unthrottled()
        for flow in all_flows():
            for e in load_flow(flow):
                note = rule_set.handle(e)
                if note is None:
                    continue
                key = (note.title, note.body, note.priority, note.click)
                seen.setdefault(note.kind, set()).add(key)
        self.assertEqual({KIND_FORM, KIND_PERMISSION, KIND_FINISHED, KIND_FAILED}, set(seen))
        for kind, texts in seen.items():
            self.assertEqual(1, len(texts), kind)

    def test_kinds_have_distinct_nonempty_text(self):
        notes = [
            unthrottled().handle(e)
            for e in [
                event("form.created", form={"id": "f", "sessionID": "s"}),
                event("permission.asked", id="p", sessionID="s"),
                event("session.execution.succeeded", sessionID="s"),
                event("session.execution.failed", sessionID="s"),
            ]
        ]
        self.assertEqual(4, len({n.body for n in notes}))
        for n in notes:
            self.assertTrue(n.title.strip())
            self.assertTrue(n.body.strip())
            self.assertEqual(CLICK_URL, n.click)
            self.assertIn(n.priority, ("min", "low", "default", "high", "urgent"))
            n.title.encode("ascii")
            n.body.encode("ascii")

    def test_click_opens_pending(self):
        self.assertEqual("phopencode://pending", CLICK_URL)

    def test_waiting_items_rank_above_finish(self):
        rule_set = unthrottled()
        form = rule_set.handle(event("form.created", form={"id": "f", "sessionID": "s"}))
        finish = rule_set.handle(event("session.execution.succeeded", sessionID="s"))
        self.assertEqual("high", form.priority)
        self.assertEqual("default", finish.priority)

    def test_notification_is_immutable(self):
        note = unthrottled().handle(event("session.execution.succeeded", sessionID="s"))
        self.assertIsInstance(note, Notification)
        with self.assertRaises(AttributeError):
            note.body = "changed"


class Sentinels(unittest.TestCase):
    """Nothing from an event reaches the output or the logs."""

    def setUp(self):
        self.stream = io.StringIO()
        self.handler = logging.StreamHandler(self.stream)
        self.handler.setLevel(logging.DEBUG)
        root = logging.getLogger()
        self.old_level = root.level
        root.addHandler(self.handler)
        root.setLevel(logging.DEBUG)

    def tearDown(self):
        root = logging.getLogger()
        root.removeHandler(self.handler)
        root.setLevel(self.old_level)

    def test_sentinelize_reaches_every_string(self):
        sample = sentinelize({"type": "t", "data": {"a": "x", "b": ["y", {"c": "z"}], "n": 1}})
        flat = json.dumps(sample)
        for original in ('"x"', '"y"', '"z"'):
            self.assertNotIn(original, flat)
        self.assertEqual("t", sample["type"])

    def test_sentinel_never_appears_in_output(self):
        rule_set = unthrottled()
        produced = set()
        for flow in all_flows():
            for e in load_flow(flow):
                note = rule_set.handle(sentinelize(e))
                if note is None:
                    continue
                produced.add(note.kind)
                self.assertNotIn(SENTINEL, text_of(note))
        self.assertEqual({KIND_FORM, KIND_PERMISSION, KIND_FINISHED, KIND_FAILED}, produced)

    def test_sentinel_child_is_still_told_apart(self):
        self.assertEqual(
            [KIND_FINISHED],
            kinds(unthrottled(), [sentinelize(e) for e in load_flow("subagent")]),
        )

    def test_sentinel_in_hand_built_events(self):
        s = SENTINEL
        events = [
            event("form.created", form={"id": s, "sessionID": s, "title": s, "fields": [{"key": s, "title": s, "description": s}], "metadata": {"kind": s}}),
            event("permission.asked", id=s, sessionID=s, action=s, resources=[s, "/home/" + s], save=[s], source={"type": s, "messageID": s, "id": s}),
            event("session.execution.succeeded", sessionID=s + "-2", title=s, text=s),
            event("session.execution.failed", sessionID=s + "-3", error={"type": s, "message": s, "status": 500, "response": {"body": s}}),
        ]
        for e in events:
            e["id"] = s
            e["location"] = {"directory": "/workspace/" + s}
            e["metadata"] = {s: s}
        rule_set = Rules(clock=FakeClock())
        for e in events:
            note = rule_set.handle(e)
            self.assertIsNotNone(note, e["type"])
            self.assertNotIn(s, text_of(note))

    def test_nothing_from_events_is_logged(self):
        rule_set = Rules(clock=FakeClock(), throttle=Throttle(clock=FakeClock(), min_interval=1000))
        for flow in all_flows():
            for e in load_flow(flow):
                rule_set.handle(sentinelize(e))
        for item in [None, {"type": "x" + SENTINEL}, {"type": "permission.asked", "data": SENTINEL}]:
            rule_set.handle(item)
        self.assertNotIn(SENTINEL, self.stream.getvalue())


class Throttling(unittest.TestCase):
    def note(self, kind=KIND_FORM):
        return Notification(kind=kind, title="t", body="b", priority="default", click=CLICK_URL)

    def test_same_kind_within_interval_is_dropped(self):
        clock = FakeClock()
        throttle = Throttle(clock=clock, min_interval=30, max_per_hour=100, max_per_day=100)
        self.assertTrue(throttle.allow(self.note()))
        clock.advance(29)
        self.assertFalse(throttle.allow(self.note()))
        clock.advance(1)
        self.assertTrue(throttle.allow(self.note()))

    def test_dropped_notification_does_not_extend_the_interval(self):
        clock = FakeClock()
        throttle = Throttle(clock=clock, min_interval=30, max_per_hour=100, max_per_day=100)
        self.assertTrue(throttle.allow(self.note()))
        clock.advance(20)
        self.assertFalse(throttle.allow(self.note()))
        clock.advance(10)
        self.assertTrue(throttle.allow(self.note()))

    def test_kinds_have_separate_intervals(self):
        clock = FakeClock()
        throttle = Throttle(clock=clock, min_interval=30, max_per_hour=100, max_per_day=100)
        self.assertTrue(throttle.allow(self.note(KIND_FORM)))
        self.assertTrue(throttle.allow(self.note(KIND_PERMISSION)))
        self.assertTrue(throttle.allow(self.note(KIND_FINISHED)))
        self.assertTrue(throttle.allow(self.note(KIND_FAILED)))
        self.assertFalse(throttle.allow(self.note(KIND_FORM)))

    def test_hourly_cap(self):
        clock = FakeClock()
        throttle = Throttle(clock=clock, min_interval=0, max_per_hour=5, max_per_day=100)
        allowed = 0
        for _ in range(20):
            allowed += throttle.allow(self.note())
            clock.advance(60)
        # 20 attempts over 20 minutes: only 5 fit in the hour.
        self.assertEqual(5, allowed)
        clock.advance(3600)
        self.assertTrue(throttle.allow(self.note()))

    def test_hourly_window_slides(self):
        clock = FakeClock()
        throttle = Throttle(clock=clock, min_interval=0, max_per_hour=2, max_per_day=100)
        self.assertTrue(throttle.allow(self.note()))
        clock.advance(1800)
        self.assertTrue(throttle.allow(self.note()))
        clock.advance(1799)
        self.assertFalse(throttle.allow(self.note()))
        clock.advance(1)
        self.assertTrue(throttle.allow(self.note()))
        self.assertFalse(throttle.allow(self.note()))

    def test_daily_cap(self):
        clock = FakeClock()
        throttle = Throttle(clock=clock, min_interval=0, max_per_hour=1000, max_per_day=10)
        allowed = 0
        for _ in range(100):
            allowed += throttle.allow(self.note())
            clock.advance(600)
        # 100 attempts every 10 minutes cover 16.5 hours: the day allows 10.
        self.assertEqual(10, allowed)
        clock.advance(86400)
        self.assertTrue(throttle.allow(self.note()))

    def test_clock_going_back_does_not_unlock(self):
        clock = FakeClock(5000)
        throttle = Throttle(clock=clock, min_interval=30, max_per_hour=100, max_per_day=100)
        self.assertTrue(throttle.allow(self.note()))
        clock.now = 10
        self.assertFalse(throttle.allow(self.note()))

    def test_clock_going_back_keeps_the_hourly_cap(self):
        clock = FakeClock(5000)
        throttle = Throttle(clock=clock, min_interval=0, max_per_hour=2, max_per_day=100)
        self.assertTrue(throttle.allow(self.note()))
        clock.now = 10
        self.assertTrue(throttle.allow(self.note()))
        # Both sends happened within the last hour of real time: the cap is reached.
        clock.now = 3700
        self.assertFalse(throttle.allow(self.note()))
        clock.now = 5000 + 3600
        self.assertTrue(throttle.allow(self.note()))

    def test_clock_going_back_keeps_the_daily_cap(self):
        clock = FakeClock(100000)
        throttle = Throttle(clock=clock, min_interval=0, max_per_hour=100, max_per_day=2)
        self.assertTrue(throttle.allow(self.note()))
        clock.now = 10
        self.assertTrue(throttle.allow(self.note()))
        clock.now = 86400 + 20
        self.assertFalse(throttle.allow(self.note()))

    def test_defaults_are_sane(self):
        throttle = Throttle(clock=FakeClock())
        self.assertGreater(throttle.min_interval, 0)
        self.assertGreater(throttle.max_per_hour, 0)
        self.assertGreaterEqual(throttle.max_per_day, throttle.max_per_hour)
        self.assertLessEqual(throttle.max_per_day, 250)

    def test_rejects_bad_limits(self):
        for kwargs in ({"min_interval": -1}, {"max_per_hour": 0}, {"max_per_day": 0}):
            with self.assertRaises(ValueError):
                Throttle(clock=FakeClock(), **kwargs)

    def test_rules_apply_the_throttle(self):
        clock = FakeClock()
        rule_set = Rules(throttle=Throttle(clock=clock, min_interval=60, max_per_hour=100, max_per_day=100))
        events = load_flow("permission")
        self.assertEqual([KIND_PERMISSION, KIND_FINISHED], kinds(rule_set, events))
        clock.advance(60)
        self.assertEqual([KIND_PERMISSION, KIND_FINISHED], kinds(rule_set, events))

    def test_rules_default_throttle_uses_given_clock(self):
        clock = FakeClock()
        rule_set = Rules(clock=clock)
        e = event("permission.asked", id="p", sessionID="s")
        self.assertIsNotNone(rule_set.handle(e))
        self.assertIsNone(rule_set.handle(e))
        clock.advance(3600)
        self.assertIsNotNone(rule_set.handle(e))

    def test_classify_ignores_the_throttle(self):
        rule_set = Rules(clock=FakeClock())
        e = event("permission.asked", id="p", sessionID="s")
        self.assertIsNotNone(rule_set.classify(e))
        self.assertIsNotNone(rule_set.classify(e))
        self.assertIsNotNone(rule_set.handle(e))
        self.assertIsNone(rule_set.handle(e))


class Source(unittest.TestCase):
    def test_parses_as_python_3_9(self):
        source = MODULE.read_text(encoding="utf-8")
        ast.parse(source, filename=str(MODULE), feature_version=(3, 9))

    def test_test_file_parses_as_python_3_9(self):
        source = Path(__file__).read_text(encoding="utf-8")
        ast.parse(source, feature_version=(3, 9))

    def test_standard_library_only(self):
        tree = ast.parse(MODULE.read_text(encoding="utf-8"))
        allowed = {"collections", "time", "typing", "threading"}
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                names = [a.name.split(".")[0] for a in node.names]
            elif isinstance(node, ast.ImportFrom):
                self.assertEqual(0, node.level, "no relative imports")
                names = [node.module.split(".")[0]]
            else:
                continue
            for name in names:
                self.assertIn(name, allowed)

    def test_no_repository_paths_or_logging(self):
        source = MODULE.read_text(encoding="utf-8")
        for word in ("core/testing", "fixtures", "import logging", "print("):
            self.assertNotIn(word, source)


def event(event_type, **data):
    return copy.deepcopy({"id": "evt_1", "created": 1, "type": event_type, "data": data})


if __name__ == "__main__":
    unittest.main()
