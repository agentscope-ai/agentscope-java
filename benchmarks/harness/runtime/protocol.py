# Copyright 2024-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Validate and reconcile a single Managed Agents event stream.

This is a passive protocol reader, not an agent loop or a durable execution engine.
It never runs tools, infers scores, or retries agent work.
"""
from copy import deepcopy
import json
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker


SCHEMA_PATH = Path(__file__).resolve().parents[1] / "schemas/managed-agents.schema.json"
SCHEMA = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
_FORMATS = FormatChecker()
_EVENT_VALIDATOR = Draft202012Validator(SCHEMA, format_checker=_FORMATS)
_REQUEST_VALIDATOR = Draft202012Validator(
    {"$ref": "#/$defs/sendRequest", "$defs": SCHEMA["$defs"]}, format_checker=_FORMATS)
_RESULT_LINKS = {
    "agent.tool_result": ("tool_use_id", "agent.tool_use"),
    "agent.mcp_tool_result": ("mcp_tool_use_id", "agent.mcp_tool_use"),
    "user.custom_tool_result": ("custom_tool_use_id", "agent.custom_tool_use"),
    "user.tool_confirmation": ("tool_use_id", None),
}


class ProtocolError(ValueError):
    """Unsupported or inconsistent protocol data; no model execution is attempted."""


def _validate(validator, value):
    error = next(validator.iter_errors(value), None)
    if error is not None:
        # Do not include the instance: it can contain private tool/model content.
        path = "/".join(str(part) for part in error.absolute_path) or "$"
        raise ProtocolError("Schema violation at %s (%s)" % (path, error.validator))


def _check_confirmation(event):
    if (event["type"] == "user.tool_confirmation"
            and event["result"] == "allow" and event.get("deny_message") is not None):
        raise ProtocolError("deny_message is only allowed for a denial")


def validate_event(event):
    """Accept only the documented text profile, without adding an envelope."""
    _validate(_EVENT_VALIDATOR, event)
    _check_confirmation(event)


def validate_request(request):
    """Validate send-event parameters, which have no server ID or timestamp."""
    _validate(_REQUEST_VALIDATOR, request)
    for event in request["events"]:
        _check_confirmation(event)


def iter_jsonl(lines):
    """Read complete records; callers must split input only at LF boundaries."""
    for number, line in enumerate(lines, 1):
        if not line.strip():
            continue
        try:
            yield json.loads(line)
        except (ValueError, UnicodeDecodeError) as exc:
            raise ProtocolError("Invalid JSONL at line %d" % number) from exc


def iter_sse(lines):
    """Decode SSE data records, including comments and multi-line data.

    Connection IDs/retry hints are transport metadata. Event IDs come from the JSON
    payload. A disconnected partial record is discarded, as required by SSE framing.
    """
    data, event_type = [], None
    for raw in lines:
        line = raw.decode("utf-8") if isinstance(raw, bytes) else raw
        line = line.rstrip("\r\n")
        if not line:
            if data:
                try:
                    value = json.loads("\n".join(data))
                except ValueError as exc:
                    raise ProtocolError("Invalid SSE JSON data") from exc
                if not isinstance(value, dict):
                    raise ProtocolError("SSE data must be an event object")
                if event_type not in (None, "", "message", value.get("type")):
                    raise ProtocolError("SSE event name disagrees with JSON type")
                yield value
            data, event_type = [], None
        elif not line.startswith(":"):
            field, _, value = line.partition(":")
            if value.startswith(" "):
                value = value[1:]
            if field == "data":
                data.append(value)
            elif field == "event":
                event_type = value


class EventProjection:
    """Canonical history and live preview for one session/thread stream.

    Seed with ordered history before consuming an already-open live stream. This
    object is not a multi-thread event merger or an execution state machine.
    """

    def __init__(self):
        self._events = {}
        self._preview_types = {}
        self._previews = {}
        self._resolved = set()
        self._pending = set()
        self.status = None
        self.stop_reason = None

    @property
    def events(self):
        return deepcopy(list(self._events.values()))

    @property
    def previews(self):
        return deepcopy(self._previews)

    @property
    def pending_calls(self):
        return sorted(self._pending - self._resolved)

    def reset_previews(self):
        """Discard connection-local previews on disconnect; preserve formal history."""
        self._preview_types.clear()
        self._previews.clear()

    def accept(self, event):
        """Return True for a new/updated persisted event, False for preview/replay."""
        validate_event(event)
        event = deepcopy(event)
        kind = event["type"]
        if kind in ("event_start", "event_delta"):
            self._preview(event)
            return False

        event_id = event["id"]
        previous = self._events.get(event_id)
        if previous is not None:
            old = {k: v for k, v in previous.items() if k != "processed_at"}
            new = {k: v for k, v in event.items() if k != "processed_at"}
            if old != new:
                raise ProtocolError("Conflicting payload for an existing event ID")
            if previous.get("processed_at") == event.get("processed_at"):
                return False
            if kind.startswith("user."):
                if event.get("processed_at") is None:
                    return False  # Stale queued echo after its processed echo.
                if previous.get("processed_at") is None:
                    self._events[event_id] = event
                    self._apply(event)
                    return True
            raise ProtocolError("Conflicting processed_at for an existing event ID")

        link = _RESULT_LINKS.get(kind)
        if link:
            field, expected = link
            origin = self._events.get(event[field])
            allowed = {expected} if expected else {"agent.tool_use", "agent.mcp_tool_use"}
            if origin is None or origin["type"] not in allowed:
                raise ProtocolError("Tool result does not reference a matching tool event")
            if kind == "user.tool_confirmation" and origin.get("evaluated_permission") != "ask":
                raise ProtocolError("Confirmation refers to a tool that did not ask")

        if kind == "span.model_request_end":
            origin = self._events.get(event["model_request_start_id"])
            if origin is None or origin["type"] != "span.model_request_start":
                raise ProtocolError("Model span end has no matching start")

        if kind == "session.status_idle" and event["stop_reason"]["type"] == "requires_action":
            for identity in event["stop_reason"]["event_ids"]:
                origin = self._events.get(identity)
                if origin is None or not (origin["type"] == "agent.custom_tool_use" or (
                        origin["type"] in ("agent.tool_use", "agent.mcp_tool_use")
                        and origin.get("evaluated_permission") == "ask")):
                    raise ProtocolError("requires_action references an unknown blocking call")

        if kind == "agent.message":
            self._reconcile_message(event)
        if kind == "agent.thinking":
            if self._preview_types.get(event_id) not in (None, "agent.thinking"):
                raise ProtocolError("Final thinking event does not match its preview type")
            self._preview_types.pop(event_id, None)
        self._events[event_id] = event
        self._apply(event)
        return True

    def _preview(self, event):
        if event["type"] == "event_start":
            preview = event["event"]
            if preview["id"] in self._events:
                return  # Already obtained through history after opening the stream.
            if preview["id"] in self._preview_types:
                raise ProtocolError("Duplicate event_start on the same connection")
            self._preview_types[preview["id"]] = preview["type"]
            return
        event_id = event["event_id"]
        if event_id in self._events:
            return
        if self._preview_types.get(event_id) != "agent.message":
            raise ProtocolError("Text delta requires an agent.message preview start")
        key = (event_id, event["delta"]["index"])
        self._previews[key] = self._previews.get(key, "") + event["delta"]["content"]["text"]

    def _reconcile_message(self, event):
        event_id = event["id"]
        if self._preview_types.get(event_id) not in (None, "agent.message"):
            raise ProtocolError("Final message does not match its preview type")
        keys = [key for key in self._previews if key[0] == event_id]
        for key in keys:
            index = key[1]
            if index >= len(event["content"]):
                raise ProtocolError("Preview references a missing final content block")
            block = event["content"][index]
            if block["type"] == "text" and not block["text"].startswith(self._previews[key]):
                raise ProtocolError("Preview is not a prefix of the authoritative message")
        for key in keys:
            del self._previews[key]
        self._preview_types.pop(event_id, None)

    def _apply(self, event):
        kind = event["type"]
        if kind == "agent.custom_tool_use" or (
                kind in ("agent.tool_use", "agent.mcp_tool_use")
                and event.get("evaluated_permission") == "ask"):
            self._pending.add(event["id"])
        elif kind in ("user.custom_tool_result", "user.tool_confirmation") and event.get("processed_at"):
            field = _RESULT_LINKS[kind][0]
            self._resolved.add(event[field])
        elif kind in ("agent.tool_result", "agent.mcp_tool_result"):
            self._resolved.add(event[_RESULT_LINKS[kind][0]])
        elif kind == "session.status_running":
            self.status, self.stop_reason = "running", None
        elif kind == "session.status_idle":
            reason = event["stop_reason"]
            if reason["type"] == "requires_action":
                remaining = set(reason["event_ids"]) - self._resolved
                self._pending.update(remaining)
                # Results may have been supplied before the required idle echo.
                if not remaining:
                    return
            self.status, self.stop_reason = "idle", deepcopy(reason)
        elif kind == "session.status_terminated":
            self.status, self.stop_reason = "terminated", None
            self.reset_previews()
        elif kind == "session.status_rescheduled":
            self.status, self.stop_reason = "rescheduled", None
        if kind in ("span.model_request_end", "session.status_idle"):
            # Profile v1 consumes one ordered thread with sequential model requests.
            self.reset_previews()

    def summary(self):
        """Describe observations only. There is deliberately no pass/score field."""
        counts = {}
        for event in self._events.values():
            counts[event["type"]] = counts.get(event["type"], 0) + 1
        return {"profile": "managed-agents-text-v1", "persisted_events": len(self._events),
                "event_counts": counts, "session_status": self.status,
                "stop_reason": deepcopy(self.stop_reason), "pending_calls": self.pending_calls,
                "unreconciled_preview_blocks": len(self._previews)}
