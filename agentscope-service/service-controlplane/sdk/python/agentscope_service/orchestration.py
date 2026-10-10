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

"""Operator clients for orchestration definitions, runs, attempts and policies."""
from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any
from urllib.error import HTTPError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen


class OrchestrationError(RuntimeError):
    pass


@dataclass
class OrchestrationClient:
    base_url: str
    api_token: str = ""
    tenant: str = "default"
    namespace: str = "default"
    timeout: float = 10.0

    def create_definition(self, body: dict[str, Any]) -> dict[str, Any]:
        return self._send("POST", "/api/v1/orchestration-definitions", body)

    def definitions(self, **query: Any) -> dict[str, Any]:
        return self._send("GET", self._scope("/api/v1/orchestration-definitions", query))

    def definition(self, definition_id: str) -> dict[str, Any]:
        return self._send("GET", f"/api/v1/orchestration-definitions/{quote(definition_id)}")

    def update_definition(self, definition_id: str, body: dict[str, Any]) -> dict[str, Any]:
        return self._send("PATCH", f"/api/v1/orchestration-definitions/{quote(definition_id)}", body)

    def validate_definition(self, definition_id: str, spec: Any = None) -> dict[str, Any]:
        return self._send("POST", f"/api/v1/orchestration-definitions/{quote(definition_id)}/validate", {"spec": spec} if spec is not None else {})

    def publish_definition(self, definition_id: str) -> dict[str, Any]:
        return self._send("POST", f"/api/v1/orchestration-definitions/{quote(definition_id)}/publish", {})

    def revisions(self, definition_id: str) -> dict[str, Any]:
        return self._send("GET", f"/api/v1/orchestration-definitions/{quote(definition_id)}/revisions")

    def start(self, definition_id: str, body: dict[str, Any]) -> dict[str, Any]:
        return self._send("POST", f"/api/v1/orchestration-definitions/{quote(definition_id)}/runs", body)

    def runs(self, **query: Any) -> dict[str, Any]:
        return self._send("GET", self._scope("/api/v1/orchestration-runs", query))

    def run(self, run_id: str) -> dict[str, Any]:
        return self._send("GET", f"/api/v1/orchestration-runs/{quote(run_id)}")

    def graph(self, run_id: str) -> dict[str, Any]:
        return self._send("GET", f"/api/v1/orchestration-runs/{quote(run_id)}/graph")

    def events(self, run_id: str, after: int = 0) -> dict[str, Any]:
        return self._send("GET", f"/api/v1/orchestration-runs/{quote(run_id)}/events?after={after}")

    def control(self, run_id: str, action: str) -> dict[str, Any]:
        if action not in {"pause", "resume", "cancel"}:
            raise ValueError("action must be pause, resume, or cancel")
        return self._send("POST", f"/api/v1/orchestration-runs/{quote(run_id)}/{action}", {})

    def rerun(self, run_id: str, idempotency_key: str, input: Any = None) -> dict[str, Any]:
        body = {"idempotencyKey": idempotency_key}
        if input is not None:
            body["input"] = input
        return self._send("POST", f"/api/v1/orchestration-runs/{quote(run_id)}/rerun", body)

    def signal(self, run_id: str, name: str, idempotency_key: str, payload: Any = None) -> dict[str, Any]:
        return self._send("POST", f"/api/v1/orchestration-runs/{quote(run_id)}/signals/{quote(name)}", {"idempotencyKey": idempotency_key, "payload": payload})

    def attempts(self, **query: Any) -> dict[str, Any]:
        return self._send("GET", self._scope("/api/v1/execution-attempts", query))

    def attempt(self, attempt_id: str) -> dict[str, Any]:
        return self._send("GET", f"/api/v1/execution-attempts/{quote(attempt_id)}")

    def runtime_policy(self, agent_ref: str) -> dict[str, Any]:
        return self._send("GET", self._scope(f"/api/v1/agent-runtime-policies/{quote(agent_ref)}", {}))

    def put_runtime_policy(self, agent_ref: str, body: dict[str, Any]) -> dict[str, Any]:
        return self._send("PUT", f"/api/v1/agent-runtime-policies/{quote(agent_ref)}", body)

    def _scope(self, path: str, query: dict[str, Any]) -> str:
        values = {"tenant": self.tenant, "namespace": self.namespace, **{k: v for k, v in query.items() if v is not None}}
        return path + "?" + urlencode(values)

    def _send(self, method: str, path: str, body: Any = None) -> dict[str, Any]:
        data = None if body is None else json.dumps(body).encode()
        headers = {"Accept": "application/json"}
        if data is not None:
            headers["Content-Type"] = "application/json"
        if self.api_token:
            headers["Authorization"] = "Bearer " + self.api_token
        request = Request(self.base_url.rstrip("/") + path, data=data, headers=headers, method=method)
        try:
            with urlopen(request, timeout=self.timeout) as response:
                raw = response.read()
                return json.loads(raw) if raw else {}
        except HTTPError as exc:
            raise OrchestrationError(f"HTTP {exc.code}: {exc.read().decode(errors='replace')}") from exc
