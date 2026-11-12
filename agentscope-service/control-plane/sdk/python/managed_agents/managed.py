# Copyright 2024-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#     http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from __future__ import annotations

from typing import TYPE_CHECKING, Any, Iterator
import time
import math
from urllib.error import HTTPError
import uuid
from urllib.parse import quote, urlencode

if TYPE_CHECKING:
    from .service import ServiceClient

ROOT = "/v1/sessions"


def _segment(value: str) -> str:
    if not isinstance(value, str) or not value.strip() or value in {".", ".."}:
        raise ValueError("invalid path identifier")
    return quote(value, safe="")


def _key(value: str) -> str:
    if not isinstance(value, str) or not value.strip() or len(value) > 256:
        raise ValueError("Idempotency-Key needs 1..256 characters")
    return value


def _limit(value: int, maximum: int = 256) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or not 1 <= value <= maximum:
        raise ValueError("invalid page limit")
    return value


def _query(**values: Any) -> str:
    encoded = urlencode({key: value for key, value in values.items() if value is not None and value != ""}, doseq=True)
    return "?" + encoded if encoded else ""


class ManagedSessionClient:
    """Native Managed protocol, obtained from ServiceClient.managed_sessions()."""

    def __init__(self, transport: "ServiceClient"):
        self._transport = transport

    def _request(self, method: str, path: str, body: Any = None, key: str = "", *, revision: int | None = None, timeout: float | None = None):
        options = {} if timeout is None else {"timeout": timeout}
        return self._transport._request_at(ROOT, method, path, body, key, revision=revision, **options)

    @staticmethod
    def _session(session: str) -> str:
        return "/" + _segment(session)

    @classmethod
    def _turn(cls, session: str, turn: str) -> str:
        return cls._session(session) + "/turns/" + _segment(turn)

    def create_session(self, definition: dict, *, idempotency_key: str) -> dict:
        return self._request("POST", "", definition, _key(idempotency_key))

    def session(self, session: str) -> dict:
        return self._request("GET", self._session(session))

    def update_session(self, session: str, **values: Any) -> dict:
        if not {"title", "metadata"}.issuperset(values):
            raise ValueError("only title and metadata can be updated")
        return self._request("POST", self._session(session), values)

    def sessions(self, *, after: str = "", limit: int = 100, lifecycle: str = "active", agent_id: str = "") -> dict:
        return self._request("GET", _query(after=after, limit=_limit(limit), lifecycle=lifecycle, agent_id=agent_id))

    def send_events(self, session: str, events: list[dict], *, idempotency_key: str | None = None) -> dict:
        key = _key(str(uuid.uuid4()) if idempotency_key is None else idempotency_key)
        return self._request("POST", self._session(session) + "/events", {"events": events}, key)

    @staticmethod
    def _content(input: dict) -> list[dict]:
        if not isinstance(input, dict) or set(input) not in ({"message"}, {"input"}):
            raise ValueError("provide exactly one of message or input; unsupported input fields")
        if isinstance(input.get("message"), str):
            return [{"type": "text", "text": input["message"]}]
        messages = input.get("input", [])
        if not isinstance(messages, list) or len(messages) != 1 or not isinstance(messages[0], dict) or messages[0].get("role") != "user" or set(messages[0]) != {"role", "content"}:
            raise ValueError("a single user message is required")
        blocks = messages[0]["content"]
        if not isinstance(blocks, list) or not blocks or any(not isinstance(block, dict) or set(block) != {"type", "text"} or block["type"] != "text" or not isinstance(block["text"], str) for block in blocks):
            raise ValueError("only text content is supported")
        return blocks

    @staticmethod
    def _receipt(response: dict) -> dict:
        event = response["data"][0]
        ext = event["managed_agents"]
        return {**ext["receipt"], "event_id": event["id"], "turn_id": ext["turn_id"],
                **({"input_type": ext["intent"]} if "input_id" in ext else {})}

    def submit(self, session: str, input: dict | None = None, *, message: str | None = None, idempotency_key: str | None = None) -> dict:
        if (input is None) == (message is None):
            raise ValueError("provide exactly one of input or message")
        return self._receipt(self.send_events(session, [{"type": "user.message", "content": self._content(input if input is not None else {"message": message}),
            "managed_agents": {"intent": "new_turn"}}], idempotency_key=idempotency_key))

    submit_turn = submit

    def turn(self, session: str, turn: str) -> dict:
        return self._request("GET", self._turn(session, turn))

    def turns(self, session: str, *, after: str = "", limit: int = 100, order: str = "desc", status: str = "", input_event_id: str = "") -> dict:
        return self._request("GET", self._session(session) + "/turns" + _query(after=after, limit=_limit(limit), order=order, status=status, input_event_id=input_event_id))

    def input(self, session: str, turn: str, kind: str, payload: dict | None = None, *, idempotency_key: str | None = None, action_id: str = "") -> dict:
        if kind not in {"steer", "inject", "cancel"} or action_id:
            raise ValueError("unsupported_capability: only steer, inject and cancel are supported")
        event = {"type": "user.interrupt" if kind == "cancel" else "user.message", "managed_agents": {"intent": kind, "turn_id": turn}}
        if kind != "cancel": event["content"] = self._content(payload or {})
        return self._receipt(self.send_events(session, [event], idempotency_key=idempotency_key))

    def steer(self, session: str, turn: str, message: str, *, idempotency_key: str | None = None) -> dict:
        return self.input(session, turn, "steer", {"message": message}, idempotency_key=idempotency_key)

    def inject(self, session: str, turn: str, message: str, *, idempotency_key: str | None = None) -> dict:
        return self.input(session, turn, "inject", {"message": message}, idempotency_key=idempotency_key)

    def cancel_turn(self, session: str, turn: str, *, idempotency_key: str | None = None) -> dict:
        return self.input(session, turn, "cancel", idempotency_key=idempotency_key)

    def output(self, session: str, turn: str, *, after: str = "", limit: int = 100) -> dict:
        return self._request("GET", self._turn(session, turn) + "/output" + _query(after=after, limit=_limit(limit)))

    def final_output(self, session: str, turn: str, *, timeout: float = 120, poll_interval: float = 0.25) -> list[dict]:
        if not math.isfinite(timeout) or timeout <= 0 or not math.isfinite(poll_interval) or poll_interval <= 0:
            raise ValueError("timeout and poll interval must be positive")
        deadline = time.monotonic() + timeout
        def remaining():
            budget = deadline - time.monotonic()
            if budget <= 0: raise TimeoutError("waiting for Turn output timed out; execution continues")
            return budget
        def pause():
            time.sleep(min(poll_interval, remaining()))
        def read(path):
            while True:
                try: return self._request("GET", path, timeout=remaining())
                except HTTPError as error:
                    if error.code != 503: raise
                    error.close()
                    pause()
        while read(self._turn(session, turn))["status"] not in {"completed", "failed", "cancelled"}: pause()
        result: list[dict] = []
        after, cursors = "", set()
        while True:
            page = read(self._turn(session, turn) + "/output" + _query(after=after, limit=100))
            cursor = page.get("next_cursor")
            if not isinstance(page.get("data"), list) or not isinstance(page.get("output_available"), bool) or (not page["output_available"] and (page["data"] or page.get("has_more"))) or not isinstance(page.get("has_more"), bool) or (page["has_more"] and (not isinstance(cursor, str) or not cursor or cursor in cursors)) or (not page["has_more"] and cursor is not None):
                raise ValueError("invalid final output page")
            result.extend(page["data"])
            if not page["has_more"]: return result
            after = cursor
            cursors.add(after)

    def inputs(self, session: str, turn: str, *, after: str = "", limit: int = 100, status: str = "", event_id: str = "") -> dict:
        return self._request("GET", self._turn(session, turn) + "/inputs" + _query(after=after, limit=_limit(limit), status=status, event_id=event_id))

    def input_receipt(self, session: str, turn: str, input_id: str) -> dict:
        receipt = self._request("GET", self._turn(session, turn) + "/inputs/" + _segment(input_id))
        if receipt.get("turn_id") != turn:
            raise ValueError("Input receipt belongs to another Turn")
        return receipt

    def checkpoints(self, session: str, *, after: str = "", through: str = "", limit: int = 20) -> dict:
        return self._request("GET", self._session(session) + "/checkpoints" + _query(after=after, through=through, limit=_limit(limit, 100)))

    def checkpoint(self, session: str, checkpoint: str) -> dict:
        return self._request("GET", self._session(session) + "/checkpoints/" + _segment(checkpoint))

    def restore_checkpoint(self, session: str, checkpoint: str, *, idempotency_key: str) -> dict:
        _segment(checkpoint)
        return self._request("POST", self._session(session) + "/checkpoints/restore",
                             {"checkpoint_id": checkpoint}, _key(idempotency_key))

    def fork_checkpoint(self, session: str, target: str, checkpoint: str, *, idempotency_key: str) -> dict:
        _segment(target)
        _segment(checkpoint)
        if session == target:
            raise ValueError("Fork target must differ")
        return self._request("POST", self._session(session) + "/fork",
                             {"checkpoint_id": checkpoint, "target_session_id": target}, _key(idempotency_key))

    def checkpoint_operation(self, session: str, operation: str) -> dict:
        receipt = self._request("GET", self._session(session) + "/checkpoint-operations/" + _segment(operation))
        if receipt.get("source_session_id") != session:
            raise ValueError("Checkpoint operation belongs to another Session")
        return receipt

    def cancel_checkpoint_operation(self, session: str, operation: str) -> dict:
        return self._request("POST", self._session(session) + "/checkpoint-operations/" + _segment(operation) + "/cancel")

    def capabilities(self, session: str) -> dict:
        return self._request("GET", self._session(session) + "/capabilities")

    def webhook(self, session: str, url: str, *, idempotency_key: str, event_types: list[str] | None = None) -> dict:
        body = {"url": url}
        if event_types is not None:
            body["event_types"] = event_types
        return self._request("POST", self._session(session) + "/webhooks", body, _key(idempotency_key))

    def webhooks(self, session: str, *, after: str = "", limit: int = 100) -> dict:
        return self._request("GET", self._session(session) + "/webhooks" + _query(after=after, limit=_limit(limit, 100)))

    def webhook_deliveries(self, session: str, webhook: str, *, after: str = "", limit: int = 100) -> dict:
        return self._request("GET", self._session(session) + "/webhooks/" + _segment(webhook) + "/deliveries" + _query(after=after, limit=_limit(limit, 100)))

    def delete_webhook(self, session: str, webhook: str):
        return self._request("DELETE", self._session(session) + "/webhooks/" + _segment(webhook))

    def retry_webhook(self, session: str, webhook: str) -> dict:
        return self._request("POST", self._session(session) + "/webhooks/" + _segment(webhook) + "/retry", {})

    def turn_capabilities(self, session: str, turn: str) -> dict:
        return self._request("GET", self._turn(session, turn) + "/capabilities")

    def snapshot(self, session: str, turn: str = "", *, limit: int = 100) -> dict:
        path = self._turn(session, turn) if turn else self._session(session)
        return self._request("GET", path + "/snapshot" + _query(limit=_limit(limit)))

    def resources(self, session: str, snapshot_id: str, kind: str, *, after: str = "", limit: int = 100) -> dict:
        return self._request("GET", self._session(session) + "/resources/" + _segment(kind) + _query(snapshot_id=snapshot_id, after=after, limit=_limit(limit)))

    def events(self, session: str, *, page: str = "", limit: int = 100, order: str = "asc", types: list[str] | None = None, created_at: dict[str, str] | None = None) -> dict:
        filters: dict[str, Any] = {"page": page, "limit": _limit(limit), "order": order}
        if types: filters["types[]"] = types
        for operator, value in (created_at or {}).items():
            if operator not in {"gt", "gte", "lt", "lte"}: raise ValueError("invalid time operator")
            filters[f"created_at[{operator}]"] = value
        return self._request("GET", self._session(session) + "/events" + _query(**filters))

    def stream(self, session: str, *, after: str = "") -> Iterator[dict]:
        if after: raise ValueError("unsupported_capability: SSE resume is not supported")
        source = self._transport._stream_at(ROOT, self._session(session) + "/events/stream")
        try:
            for event in source:
                if event.get("managed_agents", {}).get("session_id", session) != session:
                    raise ValueError("Event belongs to another Session")
                yield event
        finally:
            source.close()

    def usage(self, session: str, turn: str = "") -> dict:
        path = self._turn(session, turn) if turn else self._session(session)
        return self._request("GET", path + "/usage")

    def budget(self, session: str) -> dict:
        return self._request("GET", self._session(session) + "/budget")

    def set_budget(self, session: str, limits: dict, *, revision: int | None = None) -> dict:
        if revision is not None and (not isinstance(revision, int) or isinstance(revision, bool) or revision < 1):
            raise ValueError("budget revision must be positive")
        return self._request("PUT", self._session(session) + "/budget", limits, revision=revision)

    def tool_tasks(self, session: str, turn: str, *, after: str = "", limit: int = 100) -> dict:
        return self._request("GET", self._session(session) + "/tool-tasks" + _query(turn=turn, after=after, limit=_limit(limit)))

    def lifecycle(self, session: str, kind: str, *, idempotency_key: str) -> dict:
        if kind not in {"archive", "restore", "delete"}:
            raise ValueError("unsupported lifecycle kind")
        return self._request("DELETE" if kind == "delete" else "POST", self._session(session) + ("" if kind == "delete" else "/" + kind), key=_key(idempotency_key))

    def lifecycle_receipt(self, session: str, command: str) -> dict:
        receipt = self._request("GET", self._session(session) + "/lifecycle/" + _segment(command))
        if receipt.get("session_id") != session:
            raise ValueError("Lifecycle receipt belongs to another Session")
        return receipt

    def upload_file(self, session: str, name: str, data: bytes, *, idempotency_key: str, content_type: str = "application/octet-stream") -> dict:
        return self._transport._upload_at(ROOT, self._session(session) + "/files", name, data, _key(idempotency_key), content_type)

    def files(self, session: str, *, offset: int = 0, limit: int = 100) -> dict:
        return self._request("GET", self._session(session) + "/files" + _query(offset=offset, limit=_limit(limit, 100)))

    def file_metadata(self, session: str, file_id: str) -> dict:
        return self._request("GET", self._session(session) + "/files/" + _segment(file_id))

    def file_content(self, session: str, file_id: str) -> bytes:
        return self._transport._content_at(ROOT, self._session(session) + "/files/" + _segment(file_id) + "/content")

    def publish_artifact(self, session: str, artifact: dict, *, idempotency_key: str) -> dict:
        return self._request("POST", self._session(session) + "/artifacts", artifact, _key(idempotency_key))

    def artifacts(self, session: str, *, turn: str = "", after: str = "", snapshot_id: str = "", limit: int = 100) -> dict:
        return self._request("GET", self._session(session) + "/artifacts" + _query(turn=turn, after=after, snapshot_id=snapshot_id, limit=_limit(limit)))

    def artifact_metadata(self, session: str, artifact: str, *, snapshot_id: str = "") -> dict:
        return self._request("GET", self._session(session) + "/artifacts/" + _segment(artifact) + _query(snapshot_id=snapshot_id))

    def artifact_content(self, session: str, artifact: str) -> bytes:
        return self._transport._content_at(ROOT, self._session(session) + "/artifacts/" + _segment(artifact) + "/content")

    def delete_artifact(self, session: str, artifact: str) -> Any:
        return self._request("DELETE", self._session(session) + "/artifacts/" + _segment(artifact))
