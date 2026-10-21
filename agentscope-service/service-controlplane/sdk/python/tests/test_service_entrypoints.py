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

import asyncio
import json
from contextlib import contextmanager

from agentscope_service import AgentScopeRunnerAdapter, AsyncInvokeAdapter, ManagementClient, ServiceClient
from agentscope_service.adapters.base import AgentTaskAssignment
from agentscope_service.proto import asdp_pb2
from agentscope_service.transport.http_pull import HttpPullTransport


def assignment():
    return AgentTaskAssignment("attempt", "task", "run", "node", 1, "dispatch", "", "", "",
                               json.dumps({"runtimeBinding": {"sessionId": "session"}}).encode(), 0)


def test_framework_runners_invoke_fresh_instances_and_remove_hooks():
    instances = []
    class Runnable:
        async def ainvoke(self, value):
            return {"answer": value}
    def factory(ctx):
        target = Runnable()
        instances.append(target)
        return target
    adapter = AsyncInvokeAdapter("http://unused", factory, input_builder=lambda ctx: ctx.context["currentRequest"])
    adapter._client.call_tool = lambda *args: {}
    adapter._client.task_context = lambda *args: {"currentRequest": "hello", "inputs": []}
    assert asyncio.run(adapter.handle_agent_task(assignment())).result == {"answer": "hello"}
    asyncio.run(adapter.handle_agent_task(assignment()))
    assert instances[0] is not instances[1]

    class Agent:
        supported_hook_types = ["pre_reply"]
        def __init__(self): self.hooks = []
        def register_instance_hook(self, **kwargs): self.hooks.append(kwargs["hook_type"])
        def remove_instance_hook(self, hook_type, name): self.hooks.remove(hook_type)
        async def __call__(self, value):
            assert self.hooks
            raise asyncio.CancelledError()
    agent = Agent()
    adapter = AgentScopeRunnerAdapter("http://unused", lambda ctx: agent, input_builder=lambda ctx: "hello")
    adapter._client.call_tool = lambda *args: {}
    adapter._client.task_context = lambda *args: {}
    try:
        asyncio.run(adapter.handle_agent_task(assignment()))
    except asyncio.CancelledError:
        pass
    else:
        raise AssertionError("cancellation swallowed")
    assert not agent.hooks


def test_http_exchange_retries_reports_and_only_acks_accepted_commands(monkeypatch):
    from agentscope_service.transport import http_pull
    transport = HttpPullTransport("http://service", tenant="t", credential="secret", agent_id="a", agent_key="key",
                                  binding_id="b", namespace="n", instance_key="i", generation=1, capabilities=[])
    transport.report_sessions([])
    requests, handled = [], []
    def handler(*args):
        handled.append(args)
        if len(handled) == 1:
            raise RuntimeError("temporarily busy")
    transport.set_session_command_handler(handler)
    command = {"id": "command-1", "message": {"sessionCmd": {"sessionId": "s", "command": "terminate"}}}
    @contextmanager
    def fake_urlopen(request, timeout):
        body = json.loads(request.data)
        requests.append(body)
        if len(requests) == 1:
            raise TimeoutError("response lost")
        if len(requests) == 4:
            transport._stop.set()
        class Response:
            def read(self):
                return json.dumps({"messages": [{"connectAck": {"accepted": True}}],
                                   "commands": [command] if len(requests) < 4 else []}).encode()
        yield Response()
    monkeypatch.setattr(http_pull, "urlopen", fake_urlopen)
    monkeypatch.setattr(http_pull, "BACKOFF_INITIAL", 0.001)
    transport._run()
    assert requests[0]["messages"] == requests[1]["messages"]
    assert requests[2]["ack"] == []
    assert requests[3]["ack"] == ["command-1"]


def test_management_and_public_clients_follow_current_routes():
    calls = []
    api = ManagementClient("http://unused", "platform")
    api._send = lambda *args: calls.append(args) or {}
    api.create_credential("application", name="client", scopes=["invoke", "read"], targets=[{"type": "agent", "id": "agent"}])
    assert calls[0][1] == "/api/v1/applications/application/credentials"
    assert calls[0][2]["targets"] == [{"type": "agent", "id": "agent"}]
    client = ServiceClient("http://unused", api_token="platform")
    def reply(*args):
        calls.append(args)
        return {"snapshot_id": "snapshot", "as_of": "cursor", "resources": {}} if "/snapshot?" in args[1] else {}
    client._request = reply
    client.create_session({"type": "agent", "id": "agent"}, idempotency_key="session-key")
    client.snapshot("session", "turn")
    client.command("session", "turn", "actions", {"answers": [{"request_id": "r", "allow": True}]}, idempotency_key="retry-key")
    assert calls[2][1] == "/session/turns/turn/snapshot?limit=100"
    assert calls[3][-1] == "retry-key"
    assert client._headers() == {"Authorization": "Bearer platform"}


def test_team_example_releases_leader_and_accepts_only_current_child():
    import importlib.util
    from pathlib import Path
    from agentscope_service import TaskContext
    path = Path(__file__).resolve().parents[4] / "service-controlplane/examples/service-api/worker.py"
    spec = importlib.util.spec_from_file_location("service_demo_worker", path)
    demo = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(demo)
    delegated, accepted = [], []
    class Client:
        def create_child_from_task(self, *args): delegated.append(args[-1]); return {}
        def accept_current_issue(self, token, reason): accepted.append(reason)
        def task_context(self, *args):
            return {"coordinatorChildren": [{"issue": {"id": "child", "status": "done"},
                        "outcomes": [{"status": "completed", "result": {"answer": "checked"}}]}]}
    ctx = TaskContext(assignment(), {"task": {"leaderTask": True},
                      "team": {"members": [{"agentId": "member"}]}}, Client(), lambda event: None, "session")
    result = asyncio.run(demo.run(ctx))
    assert result.outcome == "waiting" and not result.complete_run
    assert delegated == [{"title": "Check one source", "description": "Return a concise finding.",
                          "assigneeType": "agent", "assigneeRef": "member"}]
    ctx.context = {"task": {"leaderTask": True, "parentTaskId": "initial"},
                   "issue": {"id": "child", "status": "in_review"},
                   "coordinatorChildren": [{"issue": {"id": "child"},
                       "outcomes": [{"status": "completed", "result": {"answer": "checked"}}]}]}
    result = asyncio.run(demo.run(ctx))
    assert result.complete_run and result.result["findings"] == [{"answer": "checked"}]
    assert len(delegated) == 1 and len(accepted) == 1


def test_http_batches_by_encoded_size_without_truncating():
    small = {"contextReport": {"data": "a" * (9 * 1024 * 1024)}}
    assert HttpPullTransport._batch_count([small, small]) == 1
    assert HttpPullTransport._batch_count([{ "heartbeat": {} }, small]) == 2


def test_event_journal_rejects_oversized_record_before_write(tmp_path):
    from agentscope_service.event_journal import EventJournal
    journal = EventJournal(str(tmp_path), tenant="t", namespace="n", agent_key="a", instance_key="i")
    event = asdp_pb2.SessionEventMsg(session_id="s", seq=1, content="x" * (16 * 1024 * 1024))
    try:
        journal.append(event)
    except ValueError as error:
        assert "artifact" in str(error)
    else:
        raise AssertionError("oversized record was accepted")
    assert len(journal) == 0


def test_service_client_local_mode_omits_credentials(monkeypatch):
    from agentscope_service import service
    requests = []
    @contextmanager
    def respond(request, timeout):
        requests.append(request)
        class Response:
            def read(self, size=-1): return b'{"items":[]}'
        yield Response()
    monkeypatch.setattr(service, "urlopen", respond)
    client = ServiceClient("http://local-service")
    assert client.sessions() == {"items": []}
    assert not requests[0].has_header("Authorization")
    assert not requests[0].has_header("X-api-key")
    import pytest
    with pytest.raises(ValueError):
        ServiceClient("http://local-service", "application-key", api_token="platform-token")


def test_public_service_client_rejects_redirect_before_forwarding_identity():
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    from threading import Thread
    from urllib.error import HTTPError
    import pytest
    forwarded = []

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def do_GET(self):
            if self.path == "/forwarded":
                forwarded.append(self.headers.get("X-API-Key"))
                self.send_response(200)
            else:
                self.send_response(302)
                self.send_header("Location", "/forwarded")
            self.end_headers()

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        client = ServiceClient("http://127.0.0.1:" + str(server.server_port), "original-key")
        with pytest.raises(HTTPError) as error:
            client.session("session")
        assert error.value.code == 302
        assert forwarded == []
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_public_upload_enforces_request_and_response_limits(monkeypatch):
    from agentscope_service import service
    import pytest
    client = ServiceClient("http://local-service")
    with pytest.raises(ValueError, match="16 MiB"):
        client.upload_file("session", "file", bytes((16 << 20) + 1), idempotency_key="key")
    limits = []
    @contextmanager
    def respond(request, timeout):
        assert request.get_header("X-file-name") == "%E6%96%87%E4%BB%B6%20name.bin"
        assert request.data == b"\x00\xff"
        class Response:
            def read(self, size):
                limits.append(size)
                return b"x" * size
        yield Response()
    monkeypatch.setattr(service, "urlopen", respond)
    with pytest.raises(ValueError, match="JSON response"):
        client.upload_file("session", "文件 name.bin", b"\x00\xff", idempotency_key="key")
    assert limits == [(16 << 20) + 1]


def test_public_export_pins_watermark_and_rejects_incomplete_windows(monkeypatch):
    from agentscope_service import service
    from io import BytesIO
    from urllib.parse import parse_qs, urlsplit
    import pytest
    queries, closed = [], []
    incomplete = False

    @contextmanager
    def respond(request, timeout):
        assert request.get_header("X-api-key") == "original-key"
        assert not request.has_header("X-builder-internal-token")
        query = parse_qs(urlsplit(request.full_url).query)
        queries.append(query)
        first = not query.get("after")
        event = {"schema_version": 1, "session_id": "session", "id": "a" if first else "b",
                 "cursor": "cursor-a" if first else "watermark", "type": "turn.completed", "data": {}}
        payload = json.dumps(event).encode() + (b"" if incomplete else b"\n")
        response = BytesIO(payload)
        response.headers = {"Content-Type": "application/x-ndjson", "X-Export-Through": "watermark",
                            "X-Next-Cursor": event["cursor"], "X-Has-More": "true" if first else "false",
                            "X-Export-Count": "1"}
        try:
            yield response
        finally:
            response.close()
            closed.append(True)

    monkeypatch.setattr(service, "urlopen", respond)
    client = ServiceClient("http://local-service", "original-key")
    assert [event["id"] for event in client.export_events("session", limit=1)] == ["a", "b"]
    assert queries[1]["through"] == ["watermark"]
    assert queries[1]["after"] == ["cursor-a"]
    assert len(closed) == 2
    incomplete = True
    with pytest.raises(ValueError, match="Incomplete"):
        list(client.export_events("session", limit=1))
    assert len(closed) == 3


def test_public_budget_preserves_identity_and_conditional_revision(monkeypatch):
    from agentscope_service import service
    import io
    import pytest
    @contextmanager
    def respond(request, timeout):
        assert request.get_header("X-api-key") == "original-key"
        assert request.get_header("X-builder-internal-token") is None
        assert request.full_url.endswith("/session/budget")
        if request.method == "PUT":
            assert request.get_header("If-match") == "2"
            assert json.loads(request.data) == {"max_model_calls": 20}
        yield io.BytesIO(b'{"revision":3}')
    monkeypatch.setattr(service, "urlopen", respond)
    client = ServiceClient("http://local-service", api_key="original-key")
    assert client.budget("session")["revision"] == 3
    assert client.set_budget("session", {"max_model_calls": 20}, revision=2)["revision"] == 3
    with pytest.raises(ValueError, match="positive"):
        client.set_budget("session", {}, revision=0)
