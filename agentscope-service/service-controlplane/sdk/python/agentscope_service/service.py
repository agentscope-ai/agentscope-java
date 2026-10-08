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
from typing import Any, Iterator
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen


class ServiceClient:
    def __init__(self, base_url: str, api_key: str = "", *, api_token: str = "", timeout: float = 30, tenant: str = "", namespace: str = "") -> None:
        if bool(api_key) == bool(api_token):
            raise ValueError("provide exactly one application api_key or platform api_token")
        self.base_url, self.api_key, self.timeout = base_url.rstrip("/"), api_key, timeout
        self.api_token = api_token
        self.tenant, self.namespace = tenant, namespace

    def _headers(self) -> dict[str, str]:
        headers = {"X-API-Key": self.api_key} if self.api_key else {"Authorization": "Bearer " + self.api_token}
        if self.tenant:
            headers["X-AgentScope-Tenant"] = self.tenant
        if self.namespace:
            headers["X-AgentScope-Namespace"] = self.namespace
        return headers

    def _request(self, method: str, path: str, body: Any = None, key: str = "") -> Any:
        headers = {**self._headers(), "Content-Type": "application/json"}
        if key:
            headers["Idempotency-Key"] = key
        request = Request(self.base_url + "/api/v1/agent-sessions" + path,
                          data=None if body is None else json.dumps(body).encode(),
                          headers=headers, method=method)
        with urlopen(request, timeout=self.timeout) as response:
            data = response.read()
            return json.loads(data) if data else None

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

    def sessions(self, *, offset: int = 0, limit: int = 50, status: str = "active") -> dict:
        return self._request("GET", "?" + urlencode({"offset": offset, "limit": limit, "status": status}))

    def capabilities(self, session_id: str) -> dict:
        return self._request("GET", self._session(session_id) + "/capabilities")

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

    def turns(self, session_id: str, *, offset: int = 0, limit: int = 50) -> dict:
        return self._request("GET", self._session(session_id) + "/turns?" + urlencode({"offset": offset, "limit": limit}))

    def snapshot(self, session_id: str, turn_id: str = "") -> dict:
        path = self._turn(session_id, turn_id) if turn_id else self._session(session_id)
        return self._request("GET", path + "/snapshot")

    def events(self, session_id: str, *, after: str = "", limit: int = 100, turn_id: str = "") -> dict:
        path = self._turn(session_id, turn_id) if turn_id else self._session(session_id)
        return self._request("GET", path + "/events?" + urlencode({"after": after, "limit": limit}))

    def stream(self, session_id: str, *, after: str = "", turn_id: str = "") -> Iterator[dict]:
        """Yield committed events. Persist each event's cursor after applying it.

        Network timeouts are raised to the caller; reconnect using the last applied
        cursor. To rebuild a lost UI, load snapshot first and pass its as_of cursor.
        """
        headers = {**self._headers(), "Accept": "text/event-stream"}
        if after:
            headers["Last-Event-ID"] = after
        request = Request(self.base_url + "/api/v1/agent-sessions" + (self._turn(session_id, turn_id) if turn_id else self._session(session_id)) + "/events/stream", headers=headers)
        with urlopen(request, timeout=self.timeout) as response:
            data: list[str] = []
            for raw in response:
                line = raw.decode("utf-8").rstrip("\r\n")
                if not line:
                    if data:
                        yield json.loads("\n".join(data))
                    data = []
                elif line.startswith("data:"):
                    data.append(line[5:].lstrip(" "))

    def command(self, session_id: str, turn_id: str, kind: str, payload: dict | None = None,
                *, idempotency_key: str) -> dict:
        if kind not in {"actions", "inputs", "steer", "cancel", "resume"}:
            raise ValueError("unsupported command kind")
        return self._request("POST", self._turn(session_id, turn_id) + "/" + kind, payload or {}, idempotency_key)

    def command_status(self, session_id: str, turn_id: str, command_id: str) -> dict:
        return self._request("GET", self._turn(session_id, turn_id) + "/commands/" + quote(command_id, safe=""))

    def turn_capabilities(self, session_id: str, turn_id: str) -> dict:
        return self._request("GET", self._turn(session_id, turn_id) + "/capabilities")

    def actions(self, session_id: str, turn_id: str) -> dict:
        return self._request("GET", self._turn(session_id, turn_id) + "/actions")

    def usage(self, session_id: str, turn_id: str = "") -> dict:
        path = self._turn(session_id, turn_id) if turn_id else self._session(session_id)
        return self._request("GET", path + "/usage")

    def artifacts(self, session_id: str, turn_id: str) -> dict:
        return self._request("GET", self._turn(session_id, turn_id) + "/artifacts")

    def artifact(self, session_id: str, turn_id: str, artifact_id: str) -> bytes:
        request = Request(self.base_url + "/api/v1/agent-sessions" + self._turn(session_id, turn_id)
                          + "/artifacts/" + quote(artifact_id, safe=""), headers=self._headers())
        with urlopen(request, timeout=self.timeout) as response:
            return response.read()

    def upload_file(self, session_id: str, name: str, data: bytes, *, idempotency_key: str,
                    content_type: str = "application/octet-stream") -> dict:
        request = Request(self.base_url + "/api/v1/agent-sessions" + self._session(session_id) + "/files",
                          data=data, method="POST", headers={**self._headers(), "Content-Type": content_type,
                          "X-File-Name": quote(name, safe=""), "Idempotency-Key": idempotency_key})
        with urlopen(request, timeout=self.timeout) as response:
            return json.loads(response.read())

    def files(self, session_id: str, *, offset: int = 0, limit: int = 100) -> dict:
        return self._request("GET", self._session(session_id) + "/files?" + urlencode({"offset": offset, "limit": limit}))

    def file_content(self, session_id: str, file_id: str) -> bytes:
        request = Request(self.base_url + "/api/v1/agent-sessions" + self._session(session_id)
                          + "/files/" + quote(file_id, safe="") + "/content", headers=self._headers())
        with urlopen(request, timeout=self.timeout) as response:
            return response.read()

    def webhook(self, session_id: str, url: str, *, idempotency_key: str,
                event_types: list[str] | None = None) -> dict:
        return self._request("POST", self._session(session_id) + "/webhooks",
                             {"url": url, "event_types": event_types or []}, idempotency_key)

    def webhooks(self, session_id: str) -> dict:
        return self._request("GET", self._session(session_id) + "/webhooks")

    def delete_webhook(self, session_id: str, webhook_id: str) -> Any:
        return self._request("DELETE", self._session(session_id) + "/webhooks/" + quote(webhook_id, safe=""))

    def retry_webhook(self, session_id: str, webhook_id: str) -> Any:
        return self._request("POST", self._session(session_id) + "/webhooks/" + quote(webhook_id, safe="") + "/retry", {})

    def archive(self, session_id: str) -> dict:
        return self._request("POST", self._session(session_id) + "/archive", {})

    def restore(self, session_id: str) -> dict:
        return self._request("POST", self._session(session_id) + "/restore", {})

    def delete(self, session_id: str) -> Any:
        return self._request("DELETE", self._session(session_id))

    def update_session(self, session_id: str, **settings: Any) -> dict:
        return self._request("PATCH", self._session(session_id), settings)

    def budget(self, session_id: str) -> dict:
        return self._request("GET", self._session(session_id) + "/budget")

    def set_budget(self, session_id: str, limits: dict) -> dict:
        return self._request("PUT", self._session(session_id) + "/budget", limits)

    def checkpoints(self, session_id: str) -> dict:
        return self._request("GET", self._session(session_id) + "/checkpoints")

    def restore_checkpoint(self, session_id: str, checkpoint_id: str, *, reason: str,
                           idempotency_key: str) -> dict:
        return self._request("POST", self._session(session_id) + "/checkpoints/restore",
                             {"checkpoint_id": checkpoint_id, "reason": reason}, idempotency_key)

    def fork(self, session_id: str, target_session_id: str, checkpoint_id: str, *, reason: str,
             idempotency_key: str) -> dict:
        return self._request("POST", self._session(session_id) + "/fork",
                             {"target_session_id": target_session_id, "checkpoint_id": checkpoint_id,
                              "reason": reason}, idempotency_key)

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
