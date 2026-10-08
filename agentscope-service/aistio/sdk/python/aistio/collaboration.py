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

"""Backend-independent Issue/Comment/AgentTask client for Agent runtimes."""
from __future__ import annotations

import json
import mimetypes
import uuid
from dataclasses import dataclass
from typing import Any
from urllib.error import HTTPError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen


class CollaborationError(RuntimeError):
    def __init__(self, operation: str, status: int, body: str) -> None:
        super().__init__(f"{operation} failed: HTTP {status}: {body}")
        self.operation, self.status, self.body = operation, status, body


@dataclass
class CollaborationClient:
    """Same collaboration contract for Managed, External, and Hosted Agents."""
    base_url: str
    internal_token: str = ""
    timeout: float = 10.0

    def issue(self, issue_id: str, task_token: str) -> dict[str, Any]:
        return self._send("GET", f"/api/v1/issues/{quote(issue_id)}", operation="issue.get", headers={"X-Agent-Task-Token": task_token})

    def comments(self, issue_id: str, task_token: str, **query: Any) -> dict[str, Any]:
        suffix = urlencode({k: v for k, v in query.items() if v is not None})
        return self._send("GET", f"/api/v1/issues/{quote(issue_id)}/comments" + (f"?{suffix}" if suffix else ""), operation="issue.comment.list", headers={"X-Agent-Task-Token": task_token})

    def add_comment(self, issue_id: str, task_token: str, content: str, *, parent_id: str | None = None, mentions: list[dict[str, str]] | None = None, comment_type: str = "comment") -> dict[str, Any]:
        return self._send("POST", f"/api/v1/issues/{quote(issue_id)}/comments", {"content": content, "parentId": parent_id, "mentions": mentions or [], "type": comment_type}, operation="issue.comment.add", headers={"X-Agent-Task-Token": task_token})

    def create_child_from_task(self, task_id: str, task_token: str, body: dict[str, Any]) -> dict[str, Any]:
        return self._task_send("POST", task_id, "children", task_token, body, "issue.child.create")

    def team(self, team_id: str, task_token: str) -> dict[str, Any]:
        return self._send("GET", f"/api/v1/teams/{quote(team_id)}", operation="team.get", headers={"X-Agent-Task-Token": task_token})

    def request_approval(self, task_token: str, body: dict[str, Any]) -> dict[str, Any]:
        return self._send("POST", "/api/v1/approvals", body, operation="approval.request", headers={"X-Agent-Task-Token": task_token})

    def task(self, task_id: str, task_token: str) -> dict[str, Any]:
        return self._send("GET", f"/api/v1/agent-tasks/{quote(task_id)}", operation="task.get", headers={"X-Agent-Task-Token": task_token})

    def task_context(self, task_id: str, task_token: str) -> dict[str, Any]:
        return self._task_send("GET", task_id, "context", task_token, None, "task.context")

    def ack(self, task_id: str, task_token: str, input_ids: list[str]) -> dict[str, Any]:
        return self._task_send("POST", task_id, "ack", task_token, {"inputIds": input_ids}, "task.ack")

    def start(self, task_id: str, task_token: str, *, expected_version: int = 0) -> dict[str, Any]:
        return self._task_send("POST", task_id, "start", task_token, {"expectedVersion": expected_version}, "task.start")

    def progress(self, task_id: str, task_token: str, content: str, *, mentions: list[dict[str, str]] | None = None) -> dict[str, Any]:
        return self._task_send("POST", task_id, "progress", task_token, {"content": content, "mentions": mentions or []}, "task.progress")

    def respond(self, task_id: str, task_token: str, content: str, *, parent_id: str | None = None, mentions: list[dict[str, str]] | None = None) -> dict[str, Any]:
        return self._task_send("POST", task_id, "respond", task_token, {"content": content, "parentId": parent_id, "mentions": mentions or [], "type": "result"}, "task.respond")

    def complete(self, task_id: str, task_token: str, *, summary: str = "", result: Any = None, processed_input_ids: list[str] | None = None, deferred_input_ids: list[str] | None = None, expected_version: int = 0) -> dict[str, Any]:
        body = {"summary": summary, "result": result or {}, "processedInputIds": processed_input_ids or [], "deferredInputIds": deferred_input_ids or [], "expectedVersion": expected_version}
        return self._task_send("POST", task_id, "complete", task_token, body, "task.complete")

    def fail(self, task_id: str, task_token: str, code: str, message: str, *, expected_version: int = 0) -> dict[str, Any]:
        return self._task_send("POST", task_id, "fail", task_token, {"code": code, "message": message, "expectedVersion": expected_version}, "task.fail")

    def run(self, task_id: str, task_token: str) -> dict[str, Any]:
        return self._task_send("GET", task_id, "run", task_token, None, "run.get")

    def run_graph(self, task_id: str, task_token: str) -> dict[str, Any]:
        return self._task_send("GET", task_id, "run/graph", task_token, None, "run.graph")

    def complete_run_node(self, task_id: str, task_token: str, output: Any) -> dict[str, Any]:
        return self._task_send("POST", task_id, "run/node/complete", task_token, {"output": output}, "run.node.complete")

    def fail_run_node(self, task_id: str, task_token: str, code: str, message: str) -> dict[str, Any]:
        return self._task_send("POST", task_id, "run/node/fail", task_token, {"code": code, "message": message}, "run.node.fail")

    def replan_run(self, task_id: str, task_token: str, node: dict[str, Any]) -> dict[str, Any]:
        return self._task_send("POST", task_id, "run/replan", task_token, node, "run.replan")

    def signal_run(self, task_id: str, task_token: str, name: str, idempotency_key: str, payload: Any = None) -> dict[str, Any]:
        return self._task_send("POST", task_id, f"run/signals/{quote(name)}", task_token, {"idempotencyKey": idempotency_key, "payload": payload}, "run.signal")

    def run_artifacts(self, task_id: str, task_token: str) -> dict[str, Any]:
        return self._task_send("GET", task_id, "run/artifacts", task_token, None, "run.artifacts")

    def upload_artifact(self, task_id: str, task_token: str, filename: str, content: bytes, *, content_type: str | None = None, target_type: str = "agent-task", target_ref: str | None = None) -> dict[str, Any]:
        boundary = "aistio-" + uuid.uuid4().hex
        media_type = content_type or mimetypes.guess_type(filename)[0] or "application/octet-stream"
        fields = {"sourceTaskId": task_id, "targetType": target_type, "targetRef": target_ref or task_id}
        parts: list[bytes] = []
        for key, value in fields.items():
            parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{key}\"\r\n\r\n{value}\r\n".encode())
        safe_name = filename.replace('"', "_").replace("\r", "_").replace("\n", "_")
        parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{safe_name}\"\r\nContent-Type: {media_type}\r\n\r\n".encode() + content + b"\r\n")
        parts.append(f"--{boundary}--\r\n".encode())
        return self._send_bytes("POST", "/api/v1/artifacts/uploads", b"".join(parts), operation="artifact.upload", headers={"Content-Type": f"multipart/form-data; boundary={boundary}", "X-Agent-Task-Token": task_token})

    def download_artifact(self, artifact_id: str, task_id: str, task_token: str) -> bytes:
        query = urlencode({"taskId": task_id})
        request = Request(self.base_url.rstrip("/") + f"/api/v1/artifacts/{quote(artifact_id)}/download?{query}", headers=self._headers({"X-Agent-Task-Token": task_token}), method="POST")
        try:
            with urlopen(request, timeout=self.timeout) as response:
                return response.read()
        except HTTPError as exc:
            raise CollaborationError("artifact.download", exc.code, exc.read().decode(errors="replace")) from exc

    def _task_send(self, method: str, task_id: str, action: str, token: str, body: Any, operation: str) -> dict[str, Any]:
        return self._send(method, f"/api/v1/agent-tasks/{quote(task_id)}/{action}", body, operation=operation, headers={"X-Agent-Task-Token": token})

    def _send(self, method: str, path: str, body: Any = None, *, operation: str, headers: dict[str, str] | None = None) -> dict[str, Any]:
        request_headers = self._headers(headers)
        data = None
        if body is not None:
            request_headers["Content-Type"] = "application/json"
            data = json.dumps(body).encode()
        request = Request(self.base_url.rstrip("/") + path, data=data, headers=request_headers, method=method)
        try:
            with urlopen(request, timeout=self.timeout) as response:
                raw = response.read()
                return json.loads(raw) if raw else {}
        except HTTPError as exc:
            raise CollaborationError(operation, exc.code, exc.read().decode(errors="replace")) from exc

    def _send_bytes(self, method: str, path: str, body: bytes, *, operation: str, headers: dict[str, str] | None = None) -> dict[str, Any]:
        request = Request(self.base_url.rstrip("/") + path, data=body, headers=self._headers(headers), method=method)
        try:
            with urlopen(request, timeout=self.timeout) as response:
                raw = response.read()
                return json.loads(raw) if raw else {}
        except HTTPError as exc:
            raise CollaborationError(operation, exc.code, exc.read().decode(errors="replace")) from exc

    def _headers(self, headers: dict[str, str] | None = None) -> dict[str, str]:
        result = {"Accept": "application/json", **(headers or {})}
        if self.internal_token:
            result["X-Builder-Internal-Token"] = self.internal_token
        return result
