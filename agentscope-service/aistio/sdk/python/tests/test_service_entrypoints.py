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

from aistio import AgentScopeRunnerAdapter, AsyncInvokeAdapter, ManagementClient, ServiceClient
from aistio.adapters.base import AgentTaskAssignment
from aistio.proto import asdp_pb2
from aistio.transport.http_pull import HttpPullTransport


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
    from aistio.transport import http_pull
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
    client._request = lambda *args: calls.append(args) or {}
    client.create_session({"type": "agent", "id": "agent"}, idempotency_key="session-key")
    client.snapshot("session", "turn")
    client.command("session", "turn", "actions", {"request_id": "r", "expected_version": 2}, idempotency_key="retry-key")
    assert calls[2][1] == "/session/turns/turn/snapshot"
    assert calls[3][-1] == "retry-key"
    assert client._headers() == {"Authorization": "Bearer platform"}


def test_openapi_covers_public_routes_and_references_resolve():
    import re
    from pathlib import Path
    service_root = Path(__file__).resolve().parents[4]
    document = json.loads((service_root / "docs/service-api/openapi-v1.json").read_text())
    source = (service_root / "aistio/internal/httpapi/service_session_handler.go").read_text()
    normalize = lambda path: re.sub(r":([A-Za-z0-9_]+)", r"{\1}", path)
    registered = set()
    for group, method, path in re.findall(r'\b(g|turns|hooks)\.(GET|POST|PATCH|PUT|DELETE)\("([^"]*)"', source):
        prefix = {"g": "", "turns": "/:publicSessionId/turns/:turnId", "hooks": "/:publicSessionId/webhooks"}[group]
        if path.endswith("/"):
            continue
        registered.add((method.lower(), "/api/v1/agent-sessions" + normalize(prefix + path)))
    documented = {(method, path) for path, methods in document["paths"].items()
                  for method in methods if method in {"get", "post", "patch", "put", "delete"}}
    assert registered <= documented
    assert not any("/invoke/" in path or "/endpoints" in path for _, path in documented)
    def validate_refs(value):
        if isinstance(value, dict):
            if "$ref" in value:
                ref = value["$ref"]
                assert ref.startswith("#/")
                target = document
                for name in ref[2:].split("/"):
                    target = target[name]
            for child in value.values(): validate_refs(child)
        elif isinstance(value, list):
            for child in value: validate_refs(child)
    validate_refs(document)
    assert "partial_succeeded" in document["components"]["schemas"]["Turn"]["properties"]["status"]["enum"]
    assert "targets" in document["components"]["schemas"]["CredentialInput"]["required"]
    assert "410" in document["paths"]["/api/v1/agent-sessions/{publicSessionId}/events/stream"]["get"]["responses"]


def test_team_example_releases_leader_and_accepts_only_current_child():
    import importlib.util
    from pathlib import Path
    from aistio import TaskContext
    path = Path(__file__).resolve().parents[4] / "aistio/examples/service-api/worker.py"
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
    from aistio.event_journal import EventJournal
    journal = EventJournal(str(tmp_path), tenant="t", namespace="n", agent_key="a", instance_key="i")
    event = asdp_pb2.SessionEventMsg(session_id="s", seq=1, content="x" * (16 * 1024 * 1024))
    try:
        journal.append(event)
    except ValueError as error:
        assert "artifact" in str(error)
    else:
        raise AssertionError("oversized record was accepted")
    assert len(journal) == 0
