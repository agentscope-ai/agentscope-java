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

"""Session API client for Agents, Teams and Workflows."""
from __future__ import annotations

import json
import math
from typing import Any, Iterator
from urllib.parse import quote, urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener


class _NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def urlopen(request, timeout):
    # Public credentials must not be forwarded to a redirect destination.
    return build_opener(_NoRedirects()).open(request, timeout=timeout)


class ServiceClient:
    def __init__(self, base_url: str, api_key: str = "", *, api_token: str = "", timeout: float = 30, tenant: str = "", namespace: str = "") -> None:
        if api_key and api_token:
            raise ValueError("provide an application api_key or platform api_token, not both")
        if not math.isfinite(timeout) or timeout <= 0:
            raise ValueError("timeout must be positive and finite")
        self.base_url, self.api_key, self.timeout = base_url.rstrip("/"), api_key, timeout
        self.api_token = api_token
        self.tenant, self.namespace = tenant, namespace

    def _headers(self) -> dict[str, str]:
        headers = {"X-API-Key": self.api_key} if self.api_key else {}
        if self.api_token:
            headers["Authorization"] = "Bearer " + self.api_token
        if self.tenant:
            headers["X-Managed-Agents-Tenant"] = self.tenant
        if self.namespace:
            headers["X-Managed-Agents-Namespace"] = self.namespace
        return headers

    def _request(self, method: str, path: str, body: Any = None, key: str = "", *, revision: int | None = None) -> Any:
        return self._request_at("/api/v1/agent-sessions", method, path, body, key, revision=revision)

    def _request_at(self, root: str, method: str, path: str, body: Any = None,
                    key: str = "", *, revision: int | None = None, timeout: float | None = None) -> Any:
        headers = {**self._headers(), "Content-Type": "application/json"}
        if root == "/v1/sessions": headers.update({"Managed-Agents-Extensions": "turn-inputs-v1", "anthropic-version": "2023-06-01", "anthropic-beta": "managed-agents-2026-04-01"})
        if key:
            headers["Idempotency-Key"] = key
        if revision is not None:
            headers["If-Match"] = str(revision)
        request = Request(self.base_url + root + path,
                          data=None if body is None else json.dumps(body).encode(),
                          headers=headers, method=method)
        with urlopen(request, timeout=self.timeout if timeout is None else min(self.timeout, timeout)) as response:
            data = response.read((16 << 20) + 1)
            if len(data) > 16 << 20:
                raise ValueError("Service JSON response exceeds the 16 MiB client limit")
            return json.loads(data) if data else None

    def managed_sessions(self):
        from .managed import ManagedSessionClient
        return ManagedSessionClient(self)

    @staticmethod
    def _session(session_id: str) -> str:
        return "/" + quote(session_id, safe="")

    @classmethod
    def _turn(cls, session_id: str, turn_id: str) -> str:
        return cls._session(session_id) + "/turns/" + quote(turn_id, safe="")

    def create_session(self, target: dict, *, idempotency_key: str = "", **options: Any) -> dict:
        return self._request("POST", "", {"target": target, **options}, idempotency_key)

    def session(self, session_id: str) -> dict:
        return self._request("GET", self._session(session_id))

    def sessions(self, *, after: str = "", limit: int = 50, status: str = "active",
                 application_id: str = "") -> dict:
        query = {"after": after, "limit": limit, "status": status}
        if application_id:
            query["applicationId"] = application_id
        return self._request("GET", "?" + urlencode(query))

    def capabilities(self, session_id: str) -> dict:
        value = self._request("GET", self._session(session_id) + "/capabilities")
        if not isinstance(value, dict) or not isinstance(value.get("capabilities"), dict) or any(
                not isinstance(value["capabilities"].get(name), bool)
                for name in ("files", "file_input", "artifacts")):
            raise ValueError("Unsupported Session capabilities schema; use matching Service and client versions")
        return value

    def submit(self, session_id: str, input: Any = None, *, message: str | None = None,
               idempotency_key: str) -> dict:
        if message is not None and input is not None:
            raise ValueError("provide message or input, not both")
        if message is None and input is None:
            raise ValueError("message or input is required")
        return self._request("POST", self._session(session_id) + "/turns",
                             {"message": message} if message is not None else {"input": input}, idempotency_key)

    def turn(self, session_id: str, turn_id: str) -> dict:
        return self._request("GET", self._turn(session_id, turn_id))

    def turns(self, session_id: str, *, after: str = "", limit: int = 50,
              order: str = "asc", status: str = "") -> dict:
        query = {"after": after, "limit": limit, "order": order}
        if status:
            query["status"] = status
        return self._request("GET", self._session(session_id) + "/turns?" + urlencode(query))

    def snapshot(self, session_id: str, turn_id: str = "", *, limit: int = 100) -> dict:
        path = self._turn(session_id, turn_id) if turn_id else self._session(session_id)
        value = self._request("GET", path + "/snapshot?" + urlencode({"limit": limit}))
        if not isinstance(value, dict) or not value.get("snapshot_id") or not isinstance(value.get("resources"), dict) or not isinstance(value.get("as_of"), str):
            raise ValueError("Unsupported Session snapshot schema; use matching Service and client versions")
        return value

    def resources(self, session_id: str, kind: str, *, turn_id: str = "", after: str = "",
                  snapshot_id: str = "", limit: int = 100) -> dict:
        path = self._turn(session_id, turn_id) if turn_id else self._session(session_id)
        query = {"after": after, "limit": limit}
        if snapshot_id:
            query["snapshot_id"] = snapshot_id
        return self._request("GET", path + "/resources/" + quote(kind, safe="") + "?" + urlencode(query))

    def events(self, session_id: str, *, after: str = "", limit: int = 100, turn_id: str = "") -> dict:
        path = self._turn(session_id, turn_id) if turn_id else self._session(session_id)
        return self._request("GET", path + "/events?" + urlencode({"after": after, "limit": limit}))

    def stream(self, session_id: str, *, after: str = "", turn_id: str = "") -> Iterator[dict]:
        """Yield committed events. Persist each event's cursor after applying it.

        Network timeouts are raised to the caller; reconnect using the last applied
        cursor. To rebuild a lost UI, load snapshot first and pass its as_of cursor.
        """
        yield from self._stream_at("/api/v1/agent-sessions", (self._turn(session_id, turn_id) if turn_id else self._session(session_id)) + "/events/stream", after)

    def _stream_at(self, root: str, path: str, after: str = "") -> Iterator[dict]:
        headers = {**self._headers(), "Accept": "text/event-stream"}
        if root == "/v1/sessions": headers["Managed-Agents-Extensions"] = "turn-inputs-v1"
        if after:
            headers["Last-Event-ID"] = after
        request = Request(self.base_url + root + path, headers=headers)
        with urlopen(request, timeout=self.timeout) as response:
            data: list[str] = []
            event_size = 0
            while True:
                raw = response.readline((1 << 20) + 1)
                if not raw:
                    break
                if len(raw) > 1 << 20:
                    raise ValueError("Service event exceeds the 1 MiB client limit")
                line = raw.decode("utf-8").rstrip("\r\n")
                if not line:
                    if data:
                        event = json.loads("\n".join(data))
                        if not isinstance(event.get("id"), str) or (root == "/v1/sessions" and not isinstance(event.get("type"), str)) or (root != "/v1/sessions" and (event.get("schema_version") != 1 or not isinstance(event.get("cursor"), str))):
                            raise ValueError("Unsupported Service event schema; use matching Service and client versions")
                        yield event
                    data = []
                    event_size = 0
                elif line.startswith("data:"):
                    event_size += len(raw)
                    if event_size > 1 << 20:
                        raise ValueError("Service event exceeds the 1 MiB client limit")
                    data.append(line[5:].lstrip(" "))

    def export_events(self, session_id: str, *, after: str = "", through: str = "",
                      turn_id: str = "", limit: int = 1000) -> Iterator[dict]:
        """Export a fixed public history in bounded NDJSON windows.

        For durable restart, pass snapshot['as_of'] as through and persist each
        applied event cursor. Close the iterator when consumption stops.
        """
        if not isinstance(limit, int) or isinstance(limit, bool) or not 1 <= limit <= 1000:
            raise ValueError("limit must be 1..1000")
        path = self._turn(session_id, turn_id) if turn_id else self._session(session_id)
        while True:
            query = urlencode({"after": after, "through": through, "limit": limit})
            request = Request(self.base_url + "/api/v1/agent-sessions" + path + "/export?" + query,
                              headers={**self._headers(), "Accept": "application/x-ndjson"})
            with urlopen(request, timeout=self.timeout) as response:
                content_type = response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
                end = response.headers.get("X-Export-Through", "")
                next_cursor = response.headers.get("X-Next-Cursor", "")
                more = response.headers.get("X-Has-More", "")
                raw_count = response.headers.get("X-Export-Count", "")
                if (content_type != "application/x-ndjson" or not end or not next_cursor
                        or more not in ("true", "false") or not raw_count.isdecimal()
                        or int(raw_count) > limit or (through and end != through)):
                    raise ValueError("Unsupported Service export schema")
                through = end
                count = 0
                last = after
                while True:
                    raw = response.readline((1 << 20) + 1)
                    if not raw:
                        break
                    if len(raw) > 1 << 20:
                        raise ValueError("Service export event exceeds the 1 MiB client limit")
                    if not raw.endswith(b"\n"):
                        raise ValueError("Incomplete Service export event")
                    event = json.loads(raw)
                    if (not isinstance(event, dict) or event.get("schema_version") != 1
                            or event.get("session_id") != session_id
                            or not isinstance(event.get("id"), str)
                            or not isinstance(event.get("cursor"), str)
                            or not isinstance(event.get("type"), str)
                            or not isinstance(event.get("data"), dict)):
                        raise ValueError("Unsupported Service export event schema")
                    count += 1
                    if count > int(raw_count):
                        raise ValueError("Service export count mismatch")
                    last = event["cursor"]
                    yield event
                if count != int(raw_count) or (more == "true" and (not count or next_cursor != last or next_cursor == after)):
                    raise ValueError("Incomplete Service export window")
            if more == "false":
                if next_cursor != through:
                    raise ValueError("Incomplete Service export watermark")
                return
            after = next_cursor

    def command(self, session_id: str, turn_id: str, kind: str, payload: dict | None = None,
                *, idempotency_key: str) -> dict:
        if kind not in {"actions", "steer", "cancel", "resume", "tool-recovery", "workspace-recovery"}:
            raise ValueError("unsupported command kind")
        if kind == "actions" and (not isinstance(payload, dict) or not isinstance(payload.get("answers"), list)):
            raise ValueError("actions requires an answers list")
        return self._request("POST", self._turn(session_id, turn_id) + "/" + kind, payload or {}, idempotency_key)

    def command_status(self, session_id: str, turn_id: str, command_id: str) -> dict:
        value = self._request("GET", self._session(session_id) + "/commands/" + quote(command_id, safe=""))
        if value.get("turn_id") != turn_id:
            raise ValueError("Command does not belong to this Turn")
        return value

    def turn_capabilities(self, session_id: str, turn_id: str) -> dict:
        return self._request("GET", self._turn(session_id, turn_id) + "/capabilities")

    def actions(self, session_id: str, turn_id: str, *, after: str = "", snapshot_id: str = "",
                limit: int = 100) -> dict:
        return self.resources(session_id, "required_actions", turn_id=turn_id,
                              after=after, snapshot_id=snapshot_id, limit=limit)

    def usage(self, session_id: str, turn_id: str = "") -> dict:
        path = self._turn(session_id, turn_id) if turn_id else self._session(session_id)
        return self._request("GET", path + "/usage")["data"]

    def artifacts(self, session_id: str, turn_id: str = "", *, after: str = "",
                  snapshot_id: str = "", limit: int = 100) -> dict:
        path = self._turn(session_id, turn_id) if turn_id else self._session(session_id)
        query: dict[str, str | int] = {"limit": limit}
        if after:
            query["after"] = after
        if snapshot_id:
            query["snapshot_id"] = snapshot_id
        return self._request("GET", path + "/artifacts?" + urlencode(query))

    def artifact(self, session_id: str, turn_id: str, artifact_id: str) -> bytes:
        return self.artifact_content(session_id, artifact_id, turn_id=turn_id)

    def artifact_content(self, session_id: str, artifact_id: str, *, turn_id: str = "") -> bytes:
        path = self._turn(session_id, turn_id) if turn_id else self._session(session_id)
        return self._content(path + "/artifacts/" + quote(artifact_id, safe="") + "/content")

    def _content(self, path: str) -> bytes:
        return self._content_at("/api/v1/agent-sessions", path)

    def _content_at(self, root: str, path: str) -> bytes:
        request = Request(self.base_url + root + path, headers=self._headers())
        with urlopen(request, timeout=self.timeout) as response:
            data = response.read((100 << 20) + 1)
            if len(data) > 100 << 20:
                raise ValueError("Service content exceeds the 100 MiB download limit")
            return data

    def artifact_metadata(self, session_id: str, artifact_id: str, *, turn_id: str = "",
                          snapshot_id: str = "") -> dict:
        path = self._turn(session_id, turn_id) if turn_id else self._session(session_id)
        query = "?" + urlencode({"snapshot_id": snapshot_id}) if snapshot_id else ""
        return self._request("GET", path + "/artifacts/" + quote(artifact_id, safe="") + query)

    def delete_artifact(self, session_id: str, artifact_id: str) -> Any:
        return self._request("DELETE", self._session(session_id) + "/artifacts/" + quote(artifact_id, safe=""))

    def upload_file(self, session_id: str, name: str, data: bytes, *, idempotency_key: str,
                    content_type: str = "application/octet-stream") -> dict:
        return self._upload_at("/api/v1/agent-sessions", self._session(session_id) + "/files", name, data, idempotency_key, content_type)

    def _upload_at(self, root: str, path: str, name: str, data: bytes, key: str, content_type: str) -> dict:
        if len(data) > 16 << 20:
            raise ValueError("Upload exceeds the 16 MiB client limit")
        request = Request(self.base_url + root + path,
                          data=data, method="POST", headers={**self._headers(), "Content-Type": content_type,
                          "X-File-Name": quote(name, safe=""), "Idempotency-Key": key})
        with urlopen(request, timeout=self.timeout) as response:
            payload = response.read((16 << 20) + 1)
            if len(payload) > 16 << 20:
                raise ValueError("Service JSON response exceeds the 16 MiB client limit")
            return json.loads(payload)

    def files(self, session_id: str, *, offset: int = 0, limit: int = 100) -> dict:
        return self._request("GET", self._session(session_id) + "/files?" + urlencode({"offset": offset, "limit": limit}))

    def file_content(self, session_id: str, file_id: str) -> bytes:
        return self._content(self._session(session_id) + "/files/" + quote(file_id, safe="") + "/content")

    def file_metadata(self, session_id: str, file_id: str) -> dict:
        return self._request("GET", self._session(session_id) + "/files/" + quote(file_id, safe=""))

    def webhook(self, session_id: str, url: str, *, idempotency_key: str,
                event_types: list[str] | None = None) -> dict:
        return self._request("POST", self._session(session_id) + "/webhooks",
                             {"url": url, **({"event_types": event_types} if event_types is not None else {})}, idempotency_key)

    def webhooks(self, session_id: str, *, after: str | None = None, limit: int = 100) -> dict:
        query = {"limit": limit}
        if after is not None:
            query["after"] = after
        return self._request("GET", self._session(session_id) + "/webhooks?" + urlencode(query))

    def delete_webhook(self, session_id: str, webhook_id: str) -> Any:
        return self._request("DELETE", self._session(session_id) + "/webhooks/" + quote(webhook_id, safe=""))

    def retry_webhook(self, session_id: str, webhook_id: str) -> Any:
        return self._request("POST", self._session(session_id) + "/webhooks/" + quote(webhook_id, safe="") + "/retry", {})

    def webhook_deliveries(self, session_id: str, webhook_id: str, *, after: str | None = None, limit: int = 100) -> dict:
        query = {"limit": limit}
        if after is not None:
            query["after"] = after
        return self._request("GET", self._session(session_id) + "/webhooks/" + quote(webhook_id, safe="") + "/deliveries?" + urlencode(query))

    def archive(self, session_id: str) -> dict:
        return self._request("POST", self._session(session_id) + "/archive", {})

    def restore(self, session_id: str) -> dict:
        return self._request("POST", self._session(session_id) + "/restore", {})

    def delete(self, session_id: str) -> Any:
        return self._request("DELETE", self._session(session_id))

    def update_session(self, session_id: str, **settings: Any) -> dict:
        return self._request("PATCH", self._session(session_id), settings)

    def trace(self, session_id: str, *, after: int = 0, through: int | None = None, limit: int = 100) -> dict:
        """Read a bounded native prefix with an enabled human trace credential."""
        if after < 0 or not 1 <= limit <= 100 or through is not None and through < after:
            raise ValueError("Invalid trace window")
        query = {"after": after, "limit": limit}
        if through is not None:
            query["through"] = through
        return self._request("GET", self._session(session_id) + "/trace?" + urlencode(query))

    def budget(self, session_id: str) -> dict:
        return self._request("GET", self._session(session_id) + "/budget")

    def set_budget(self, session_id: str, limits: dict, *, revision: int | None = None) -> dict:
        """Apply before the next model call; a revision prevents overwriting newer limits."""
        if revision is not None and (type(revision) is not int or revision < 1):
            raise ValueError("Budget revision must be a positive integer")
        return self._request("PUT", self._session(session_id) + "/budget", limits, revision=revision)

    def checkpoints(self, session_id: str, *, after: str = "", through: str = "", limit: int = 20) -> dict:
        if not 1 <= limit <= 100:
            raise ValueError("Invalid checkpoint page limit")
        query = {"limit": limit}
        if after:
            query["after"] = after
        if through:
            query["through"] = through
        return self._request("GET", self._session(session_id) + "/checkpoints?" + urlencode(query))

    def restore_checkpoint(self, session_id: str, checkpoint_id: str, *,
                           idempotency_key: str) -> dict:
        return self._request("POST", self._session(session_id) + "/checkpoints/restore",
                             {"checkpoint_id": checkpoint_id}, idempotency_key)

    def fork(self, session_id: str, target_session_id: str, checkpoint_id: str, *,
             idempotency_key: str) -> dict:
        return self._request("POST", self._session(session_id) + "/fork",
                             {"target_session_id": target_session_id, "checkpoint_id": checkpoint_id}, idempotency_key)

    def checkpoint_operation(self, session_id: str, operation_id: str) -> dict:
        receipt = self._request("GET", self._session(session_id) + "/checkpoint-operations/" + quote(operation_id, safe=""))
        if receipt.get("source_session_id") != session_id or receipt.get("operation_id") != operation_id:
            raise ValueError("Checkpoint operation identity differs")
        return receipt

    def cancel_checkpoint_operation(self, session_id: str, operation_id: str) -> dict:
        return self._request("POST", self._session(session_id) + "/checkpoint-operations/" + quote(operation_id, safe="") + "/cancel")

    def inject(self, session_id: str, input: Any = None, *, message: str | None = None,
               idempotency_key: str) -> dict:
        if (message is None) == (input is None):
            raise ValueError("provide exactly one of message or input")
        return self._request("POST", self._session(session_id) + "/inputs/inject",
                             {"message": message} if message is not None else {"input": input}, idempotency_key)

    def subagents(self, session_id: str) -> dict:
        return self._request("GET", self._session(session_id) + "/subagents")

    def publish_artifact(self, session_id: str, artifact: dict, *, idempotency_key: str) -> dict:
        return self._request("POST", self._session(session_id) + "/artifacts", artifact, idempotency_key)
