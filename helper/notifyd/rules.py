"""Event rules: which opencode server events become a phone notification.

An event is the parsed JSON of one SSE `data:` line, an envelope of the form
`{id, created?, type, data, ...}`. The rules map it to a Notification with fixed
English text, or to None.

Privacy: the text, priority and link come only from the constants below. Nothing
from an event (titles, IDs, paths, messages, AI output) is copied into a
Notification, and this module logs nothing. Session IDs are kept in memory only to
tell a subagent's child session from a root session.

Notified:
- `form.created`: a form or question waits for an answer, in any session.
- `permission.asked`: a permission request waits, in any session.
- `session.execution.succeeded` of a root session: the session finished.
- `session.execution.failed` of a root session: the session stopped with an error.

A child session is one whose `session.created` carried a non-empty `parentID`. A
session never seen being created (for example created before the service started)
counts as a root session. A fork carries `fork`, not `parentID`, so it is a root
session. Interrupts are not notified: the user caused them.

Memory bound: at most `max_children` child sessions are remembered, least recently
seen first out. A child counts as seen when it is created, starts an execution, asks
a form or permission, or ends an execution. So a child is forgotten, and its next
finish or error notified as a root session's, only after `max_children` other
children were seen since its own last event.
"""

import time
from collections import OrderedDict, deque
from typing import Optional

CLICK_URL = "phopencode://pending"

KIND_FORM = "form"
KIND_PERMISSION = "permission"
KIND_FINISHED = "finished"
KIND_FAILED = "failed"

# The event types the service has to receive; pass them to the SSE `types` filter.
EVENT_TYPES = (
    "form.created",
    "permission.asked",
    "session.created",
    "session.deleted",
    "session.execution.succeeded",
    "session.execution.failed",
    "session.execution.started",
)

DEFAULT_MIN_INTERVAL = 60.0
DEFAULT_MAX_PER_HOUR = 20
DEFAULT_MAX_PER_DAY = 100
DEFAULT_MAX_CHILDREN = 10000

_HOUR = 3600.0
_DAY = 86400.0


class Notification(object):
    """A notification with fixed text. Immutable."""

    __slots__ = ("kind", "title", "body", "priority", "click")

    def __init__(self, kind, title, body, priority, click):
        object.__setattr__(self, "kind", kind)
        object.__setattr__(self, "title", title)
        object.__setattr__(self, "body", body)
        object.__setattr__(self, "priority", priority)
        object.__setattr__(self, "click", click)

    def __setattr__(self, name, value):
        raise AttributeError("Notification is immutable")

    def __delattr__(self, name):
        raise AttributeError("Notification is immutable")

    def __eq__(self, other):
        if not isinstance(other, Notification):
            return NotImplemented
        return self._key() == other._key()

    def __ne__(self, other):
        result = self.__eq__(other)
        if result is NotImplemented:
            return result
        return not result

    def __hash__(self):
        return hash(self._key())

    def __repr__(self):
        return "Notification(kind=%r, title=%r, body=%r, priority=%r, click=%r)" % self._key()

    def _key(self):
        return (self.kind, self.title, self.body, self.priority, self.click)


FORM = Notification(
    KIND_FORM,
    "Answer needed",
    "A question is waiting for your answer.",
    "high",
    CLICK_URL,
)
PERMISSION = Notification(
    KIND_PERMISSION,
    "Permission needed",
    "A permission request is waiting for you.",
    "high",
    CLICK_URL,
)
FINISHED = Notification(
    KIND_FINISHED,
    "Session finished",
    "A session has finished its work.",
    "default",
    CLICK_URL,
)
FAILED = Notification(
    KIND_FAILED,
    "Session failed",
    "A session stopped with an error.",
    "high",
    CLICK_URL,
)


class Throttle(object):
    """Limits how often notifications go out.

    A notification is dropped when one of the same kind went out less than
    `min_interval` seconds ago, or when `max_per_hour` or `max_per_day` were already
    sent in the sliding hour or day. Dropped notifications do not count. A clock that
    goes back is treated as no time having passed.
    """

    def __init__(
        self,
        clock=time.monotonic,
        min_interval=DEFAULT_MIN_INTERVAL,
        max_per_hour=DEFAULT_MAX_PER_HOUR,
        max_per_day=DEFAULT_MAX_PER_DAY,
    ):
        if min_interval < 0:
            raise ValueError("min_interval must not be negative")
        if max_per_hour < 1:
            raise ValueError("max_per_hour must be at least 1")
        if max_per_day < 1:
            raise ValueError("max_per_day must be at least 1")
        self._clock = clock
        self.min_interval = float(min_interval)
        self.max_per_hour = int(max_per_hour)
        self.max_per_day = int(max_per_day)
        self._last_by_kind = {}
        self._sent = deque()

    def allow(self, notification):
        # type: (Notification) -> bool
        """Returns True and records the send if the notification may go out now."""
        now = float(self._clock())
        if self._sent and now < self._sent[-1]:
            # The clock went back: no time has passed, and the send log stays sorted.
            now = self._sent[-1]
        while self._sent and now - self._sent[0] >= _DAY:
            self._sent.popleft()

        last = self._last_by_kind.get(notification.kind)
        if last is not None and now - last < self.min_interval:
            return False
        if len(self._sent) >= self.max_per_day:
            return False
        in_hour = 0
        for sent_at in reversed(self._sent):
            if now - sent_at >= _HOUR:
                break
            in_hour += 1
        if in_hour >= self.max_per_hour:
            return False

        self._last_by_kind[notification.kind] = now
        self._sent.append(now)
        return True


class Rules(object):
    """Maps server events to notifications and remembers which sessions are children.

    `handle(event)` classifies and applies the throttle; `classify(event)` only
    classifies. Pass `throttle=None` to turn throttling off. Without a `throttle`
    argument a default Throttle on `clock` is used.
    """

    _DEFAULT = object()

    def __init__(self, clock=time.monotonic, throttle=_DEFAULT, max_children=DEFAULT_MAX_CHILDREN):
        if throttle is Rules._DEFAULT:
            throttle = Throttle(clock=clock)
        if max_children < 1:
            raise ValueError("max_children must be at least 1")
        self._throttle = throttle  # type: Optional[Throttle]
        self._max_children = int(max_children)
        self._children = OrderedDict()

    def handle(self, event):
        # type: (object) -> Optional[Notification]
        """The notification to send for this event, or None. Never raises on bad input."""
        notification = self.classify(event)
        if notification is None:
            return None
        if self._throttle is not None and not self._throttle.allow(notification):
            return None
        return notification

    def classify(self, event):
        # type: (object) -> Optional[Notification]
        """Like handle, without the throttle. Still records child sessions."""
        if not isinstance(event, dict):
            return None
        event_type = event.get("type")
        data = event.get("data")
        if not isinstance(event_type, str) or not isinstance(data, dict):
            return None

        if event_type == "session.created":
            session_id = _session_id(data)
            parent_id = data.get("parentID")
            if session_id is not None and isinstance(parent_id, str) and parent_id:
                self.mark_child(session_id)
            return None
        if event_type == "session.deleted":
            session_id = _session_id(data)
            if session_id is not None:
                self._children.pop(session_id, None)
            return None
        if event_type == "session.execution.started":
            self._touch(_session_id(data))
            return None
        if event_type == "form.created":
            form = data.get("form")
            session_id = _session_id(form) if isinstance(form, dict) else None
            if session_id is not None:
                self._touch(session_id)
                return FORM
            return None
        if event_type == "permission.asked":
            session_id = _session_id(data)
            if session_id is not None:
                self._touch(session_id)
                return PERMISSION
            return None
        if event_type == "session.execution.succeeded":
            return self._root_only(data, FINISHED)
        if event_type == "session.execution.failed":
            return self._root_only(data, FAILED)
        return None

    def mark_child(self, session_id):
        # type: (str) -> None
        """Records a session as a child, for example from a session list at startup."""
        if not isinstance(session_id, str) or not session_id:
            return
        self._children[session_id] = True
        self._children.move_to_end(session_id)
        while len(self._children) > self._max_children:
            self._children.popitem(last=False)

    def tracked_children(self):
        # type: () -> int
        return len(self._children)

    def _touch(self, session_id):
        # A tracked child that shows activity becomes the most recently seen one.
        if session_id is not None and session_id in self._children:
            self._children.move_to_end(session_id)

    def _root_only(self, data, notification):
        session_id = _session_id(data)
        if session_id is None:
            return None
        if session_id in self._children:
            self._touch(session_id)
            return None
        return notification


def _session_id(data):
    # type: (dict) -> Optional[str]
    value = data.get("sessionID")
    if isinstance(value, str) and value:
        return value
    return None


__all__ = [
    "CLICK_URL",
    "EVENT_TYPES",
    "FAILED",
    "FINISHED",
    "FORM",
    "KIND_FAILED",
    "KIND_FINISHED",
    "KIND_FORM",
    "KIND_PERMISSION",
    "Notification",
    "PERMISSION",
    "Rules",
    "Throttle",
]
