# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#     http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import io
import json
from contextlib import contextmanager
from urllib.parse import parse_qs, urlparse

import pytest
from agentscope_service import ServiceClient


def test_native_webhooks_keep_keys_filters_identity_and_bounded_pages(monkeypatch):
    calls = []
    @contextmanager
    def respond(request, timeout):
        identity(request)
        calls.append(request)
        yield io.BytesIO(b'{}')
    monkeypatch.setattr(service, "urlopen", respond)
    client = transport().managed_sessions()
    client.webhook("session/one", "https://receiver.example/events", idempotency_key="hook-key", event_types=["tool.operation.completed"])
    assert urlparse(calls[-1].full_url).path == "/v1/sessions/session%2Fone/webhooks"
    assert calls[-1].get_header("Idempotency-key") == "hook-key"
    assert json.loads(calls[-1].data)["event_types"] == ["tool.operation.completed"]
    client.webhooks("session", after="a" * 64, limit=25)
    assert parse_qs(urlparse(calls[-1].full_url).query)["limit"] == ["25"]
    client.webhook_deliveries("session", "hook/one")
    assert urlparse(calls[-1].full_url).path == "/v1/sessions/session/webhooks/hook%2Fone/deliveries"
    client.retry_webhook("session", "hook")
    assert urlparse(calls[-1].full_url).path == "/v1/sessions/session/webhooks/hook/retry"
    assert calls[-1].get_method() == "POST"
    client.delete_webhook("session", "hook")
    assert calls[-1].get_method() == "DELETE"
    with pytest.raises(ValueError, match="page limit"):
        client.webhooks("session", limit=101)
    with pytest.raises(ValueError, match="Idempotency-Key"):
        client.webhook("session", "url", idempotency_key="")
from agentscope_service import service


def transport():
    return ServiceClient("http://fixture", api_key="original-key", tenant="tenant", namespace="namespace")


def identity(request):
    assert request.get_header("X-api-key") == "original-key"
    assert request.get_header("X-agentscope-tenant") == "tenant"
    assert request.get_header("X-agentscope-namespace") == "namespace"
    assert request.get_header("X-builder-internal-token") is None


def test_checkpoint_writes_keep_original_identity_retry_keys_and_source_scope(monkeypatch):
    calls = []
    @contextmanager
    def respond(request, timeout):
        identity(request)
        calls.append(request)
        yield io.BytesIO(b'{"source_session_id":"session","status":"validating"}')
    monkeypatch.setattr(service, "urlopen", respond)
    client = transport().managed_sessions()
    client.restore_checkpoint("session/one", "checkpoint", idempotency_key="restore-key")
    assert urlparse(calls[-1].full_url).path == "/v1/sessions/session%2Fone/checkpoints/restore"
    assert json.loads(calls[-1].data) == {"checkpoint_id": "checkpoint"}
    assert calls[-1].get_header("Idempotency-key") == "restore-key"
    client.fork_checkpoint("session", "target", "checkpoint", idempotency_key="fork-key")
    assert json.loads(calls[-1].data) == {"checkpoint_id": "checkpoint", "target_session_id": "target"}
    assert calls[-1].get_header("Idempotency-key") == "fork-key"
    assert client.checkpoint_operation("session", "operation")["status"] == "validating"
    with pytest.raises(ValueError, match="another Session"):
        client.checkpoint_operation("other", "operation")
    with pytest.raises(ValueError, match="must differ"):
        client.fork_checkpoint("same", "same", "checkpoint", idempotency_key="key")
    with pytest.raises(ValueError, match="Idempotency-Key"):
        client.restore_checkpoint("session", "checkpoint", idempotency_key="")
    client.cancel_checkpoint_operation("session", "operation")
    assert urlparse(calls[-1].full_url).path == "/v1/sessions/session/checkpoint-operations/operation/cancel"


def test_public_checkpoint_operations_keep_routes_fields_and_source_identity(monkeypatch):
    calls = []
    @contextmanager
    def respond(request, timeout):
        identity(request)
        calls.append(request)
        yield io.BytesIO(b'{"source_session_id":"session","operation_id":"operation"}')
    monkeypatch.setattr(service, "urlopen", respond)
    client = transport()
    client.restore_checkpoint("session", "checkpoint_saved", idempotency_key="restore-key")
    assert urlparse(calls[-1].full_url).path == "/api/v1/agent-sessions/session/checkpoints/restore"
    assert json.loads(calls[-1].data) == {"checkpoint_id": "checkpoint_saved"}
    client.fork("session", "target", "checkpoint_saved", idempotency_key="fork-key")
    assert json.loads(calls[-1].data) == {"target_session_id": "target", "checkpoint_id": "checkpoint_saved"}
    client.checkpoint_operation("session", "operation")
    with pytest.raises(ValueError, match="identity differs"):
        client.checkpoint_operation("other", "operation")
    with pytest.raises(ValueError, match="identity differs"):
        client.checkpoint_operation("session", "other")
    client.cancel_checkpoint_operation("session", "operation")
    assert urlparse(calls[-1].full_url).path == "/api/v1/agent-sessions/session/checkpoint-operations/operation/cancel"


def test_native_admission_inputs_and_lifecycle_receipts(monkeypatch):
    calls = []
    @contextmanager
    def respond(request, timeout):
        identity(request)
        calls.append(request)
        yield io.BytesIO(json.dumps({"turn_id": "turn", "session_id": "session", "status": "accepted", "data": [{"id": "event", "agentscope_service": {"turn_id": "turn", "intent": "new_turn", "receipt": {"id": "turn", "status": "accepted"}}}]}).encode())
    monkeypatch.setattr(service, "urlopen", respond)
    original = transport()
    client = original.managed_sessions()
    client.create_session({"agent": "agent", "environment_id": "environment"}, idempotency_key="create")
    assert calls[0].full_url == "http://fixture/v1/sessions"
    assert json.loads(calls[0].data) == {"agent": "agent", "environment_id": "environment"}
    client.submit("session/one", message="hello", idempotency_key="turn-key")
    assert calls[-1].full_url.endswith("/session%2Fone/events")
    assert json.loads(calls[-1].data) == {"events": [{"type": "user.message", "content": [{"type": "text", "text": "hello"}], "agentscope_service": {"intent": "new_turn"}}]}
    with pytest.raises(ValueError, match="unsupported_capability"):
        client.input("session", "turn", "reply", {"allow": True}, action_id="action", idempotency_key="reply")
    client.steer("session", "turn", "redirect", idempotency_key="steer")
    assert json.loads(calls[-1].data)["events"][0]["agentscope_service"] == {"intent": "steer", "turn_id": "turn"}
    assert client.input_receipt("session", "turn", "input")["status"] == "accepted"
    with pytest.raises(ValueError, match="another Turn"):
        client.input_receipt("session", "other", "input")
    client.lifecycle("session", "archive", idempotency_key="archive-key")
    assert calls[-1].get_header("Idempotency-key") == "archive-key"
    assert client.lifecycle_receipt("session", "command")["session_id"] == "session"
    with pytest.raises(ValueError, match="another Session"):
        client.lifecycle_receipt("other", "command")
    with pytest.raises(ValueError, match="Idempotency-Key"):
        client.lifecycle("session", "delete", idempotency_key="")
    with pytest.raises(ValueError, match="path identifier"):
        client.session("..")
    client.update_session("session", metadata={"purpose": "test"})
    assert json.loads(calls[-1].data) == {"metadata": {"purpose": "test"}}
    original.session("external")
    assert calls[-1].full_url == "http://fixture/api/v1/agent-sessions/external"


def test_binary_upload_and_scoped_resource_pages(monkeypatch):
    calls = []
    content = bytes([0, 1, 255, 13, 10])
    @contextmanager
    def respond(request, timeout):
        identity(request)
        calls.append(request)
        yield io.BytesIO(content if request.full_url.endswith("/content") else b'{"file_id":"file"}')
    monkeypatch.setattr(service, "urlopen", respond)
    client = transport().managed_sessions()
    assert client.upload_file("session", "文件 name.bin", content, idempotency_key="file-key")["file_id"] == "file"
    assert calls[-1].data == content
    assert calls[-1].get_header("X-file-name") == "%E6%96%87%E4%BB%B6%20name.bin"
    assert calls[-1].full_url.endswith("/v1/sessions/session/files")
    assert client.file_content("session", "file") == content
    client.resources("session", "snapshot", "artifacts", after="opaque+/=", limit=256)
    query = parse_qs(urlparse(calls[-1].full_url).query)
    assert query["snapshot_id"] == ["snapshot"]
    assert query["after"] == ["opaque+/="]
    with pytest.raises(ValueError, match="16 MiB"):
        client.upload_file("session", "large", bytes((16 << 20) + 1), idempotency_key="key")


def test_scoped_sse_replay_closes_on_foreign_event(monkeypatch):
    owner = "session"
    closed = []
    @contextmanager
    def respond(request, timeout):
        identity(request)
        assert request.full_url == "http://fixture/v1/sessions/session/events/stream"
        assert request.get_header("Last-event-id") is None
        event = {"id": "event", "type": "agentscope_service.turn.completed", "processed_at": "2026-10-19T00:00:00Z", "agentscope_service": {"session_id": owner}}
        try:
            yield io.BytesIO((": keepalive\n\ndata: " + json.dumps(event) + "\n\n").encode())
        finally:
            closed.append(True)
    monkeypatch.setattr(service, "urlopen", respond)
    client = transport().managed_sessions()
    assert list(client.stream("session"))[0]["id"] == "event"
    owner = "another-session"
    with pytest.raises(ValueError, match="another Session"):
        list(client.stream("session"))
    assert len(closed) == 2


def test_checkpoint_handles_and_pinned_pages_use_native_queries(monkeypatch):
    calls = []

    @contextmanager
    def respond(request, timeout):
        identity(request)
        calls.append(request.full_url)
        yield io.BytesIO(b'{"data":[],"as_of":"head","has_more":false,"next_cursor":null}')

    monkeypatch.setattr(service, "urlopen", respond)
    client = transport().managed_sessions()
    client.checkpoints("session", after="page", through="head", limit=1)
    client.checkpoint("session", "checkpoint/one")
    assert calls == ["http://fixture/v1/sessions/session/checkpoints?after=page&through=head&limit=1", "http://fixture/v1/sessions/session/checkpoints/checkpoint%2Fone"]
    with pytest.raises(ValueError):
        client.checkpoints("session", limit=101)

def test_filters_final_output_retry_and_strict_inputs(monkeypatch):
    from urllib.error import HTTPError
    calls = []
    unavailable, cycle = True, False
    @contextmanager
    def respond(request, timeout):
        nonlocal unavailable
        assert request.get_method() == "GET"
        calls.append(request.full_url)
        assert 0 < timeout <= 2
        url = urlparse(request.full_url)
        if url.path.endswith("/output"):
            if unavailable:
                unavailable = False
                raise HTTPError(request.full_url, 503, "projection unavailable", {}, None)
            more = cycle or "after" not in parse_qs(url.query)
            result = {"data": [{"id": "answer"}], "output_available": True, "next_cursor": "cursor" if more else None, "has_more": more}
        else: result = {"status": "completed"}
        yield io.BytesIO(json.dumps(result).encode())
    monkeypatch.setattr(service, "urlopen", respond)
    client = transport().managed_sessions()
    # Ordinary requests keep the configured transport timeout.
    client._transport.timeout = 2
    client.turns("s", input_event_id="event")
    assert parse_qs(urlparse(calls[-1]).query)["input_event_id"] == ["event"]
    client.inputs("s", "t", event_id="input", status="applied")
    assert parse_qs(urlparse(calls[-1]).query)["event_id"] == ["input"]
    assert len(client.final_output("s", "t", timeout=2, poll_interval=.001)) == 2
    output_calls = [url for url in calls if "/output" in url]
    assert output_calls[0] == output_calls[1]
    cycle = True
    with pytest.raises(ValueError, match="invalid final output page"):
        client.final_output("s", "t", timeout=2)
    for value in ({"message": "text", "input": []}, {"message": "text", "durable": False}):
        with pytest.raises(ValueError, match="exactly one"):
            client.submit("s", value)
    with pytest.raises(ValueError, match="Idempotency-Key"):
        client.send_events("s", [], idempotency_key="")
    with pytest.raises(ValueError, match="positive"):
        client.final_output("s", "t", timeout=0)


def test_final_output_repeated_unavailability_obeys_deadline(monkeypatch):
    from urllib.error import HTTPError
    def unavailable(request, timeout):
        raise HTTPError(request.full_url, 503, "temporary", {}, None)
    monkeypatch.setattr(service, "urlopen", unavailable)
    with pytest.raises(TimeoutError, match="execution continues"):
        transport().managed_sessions().final_output("s", "t", timeout=.02, poll_interval=.005)


def test_terminal_turn_without_output_finishes_with_empty_result(monkeypatch):
    @contextmanager
    def respond(request, timeout):
        data = {"data": [], "output_available": False, "has_more": False, "next_cursor": None} if "/output" in request.full_url else {"status": "failed"}
        yield io.BytesIO(json.dumps(data).encode())
    monkeypatch.setattr(service, "urlopen", respond)
    assert transport().managed_sessions().final_output("s", "t") == []
