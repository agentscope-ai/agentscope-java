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

"""Check that API examples use the real registration lifecycle."""

import importlib.util
import json
import asyncio
from pathlib import Path
from types import SimpleNamespace

import pytest


@pytest.mark.parametrize("managed", [False, True])
def test_bootstrap_registers_external_workers_before_policy_without_precreating_agents(
    monkeypatch, tmp_path, managed
):
    source = Path(__file__).resolve().parents[3] / "examples/service-api/bootstrap.py"
    spec = importlib.util.spec_from_file_location("service_api_bootstrap", source)
    bootstrap = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(bootstrap)
    calls = []

    class ReadyForTeam(Exception):
        pass

    class API:
        def create_application(self, body):
            return {"application": {"id": "application", "version": 1}}

        def create_agent(self, body):
            calls.append(("create_agent", body))
            return {"agent": {"id": "managed"}}

        def put_runtime_policy(self, agent_id, body):
            for candidate in body["candidates"]:
                assert isinstance(candidate.get("requiredCapabilities", {}), dict)
                assert candidate["binding"]["agentId"] == agent_id
                assert candidate["binding"]["bindingId"]
            calls.append(("policy", agent_id))

        def create_team(self, body):
            calls.append(("team", body))
            raise ReadyForTeam()

    class Worker:
        def __init__(self, command, env):
            agent_id = env["AGENTSCOPE_AGENT_KEY"]
            assert Path(env["AGENTSCOPE_IDENTITY_FILE"]).parent.name == agent_id.rsplit("-", 1)[0]
            calls.append(("register_external", agent_id))
            Path(env["AGENTSCOPE_IDENTITY_FILE"]).write_text(json.dumps({
                "agentId": agent_id, "bindingId": agent_id + "-binding",
                "kind": "external-application",
            }))

        def terminate(self):
            pass

        def wait(self, timeout):
            return 0

    monkeypatch.setattr(bootstrap, "ManagementClient", lambda *args: API())
    monkeypatch.setattr(bootstrap.subprocess, "Popen", Worker)
    monkeypatch.setattr(bootstrap.sys, "argv", ["bootstrap", "--output", str(tmp_path / "demo.json")])
    monkeypatch.setenv("AGENTSCOPE_BASE_URL", "http://test.invalid")
    monkeypatch.setenv("AGENTSCOPE_PLATFORM_TOKEN", "test-token")
    monkeypatch.delenv("AGENTSCOPE_MANAGED_AGENT_ID", raising=False)
    monkeypatch.delenv("AGENTSCOPE_MANAGED_AGENT_JSON", raising=False)
    if managed:
        definition = {"agentKey": "managed", "binding": {"kind": "managed"}}
        config = tmp_path / "managed.json"
        config.write_text(json.dumps(definition))
        monkeypatch.setenv("AGENTSCOPE_MANAGED_AGENT_JSON", str(config))

    with pytest.raises(ReadyForTeam):
        bootstrap.main()

    assert [operation for operation, _ in calls[:4]] == [
        "register_external", "policy", "register_external", "policy",
    ]
    created = [body for operation, body in calls if operation == "create_agent"]
    assert created == ([definition] if managed else [])
    members = calls[-1][1]["members"]
    assert any(member["agentId"] == "managed" for member in members) is managed


def test_worker_and_async_runner_accept_nullable_task_context(monkeypatch):
    source = Path(__file__).resolve().parents[3] / "examples/service-api/worker.py"
    spec = importlib.util.spec_from_file_location("service_api_worker", source)
    worker = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(worker)

    async def no_delay(*args):
        pass

    monkeypatch.setattr(worker.asyncio, "sleep", no_delay)
    events = []
    context = SimpleNamespace(context={"task": None, "team": None, "inputs": None},
                              event=lambda *args, **kwargs: events.append((args, kwargs)),
                              refresh=no_delay)
    result = asyncio.run(worker.run(context))
    assert result.result["sources"] == 3
    assert result.processed_input_ids == []
    assert len([entry for entry in events if entry[0] == ("tool.completed",)]) == 3

    from agentscope_service.adapters.runners import _result

    result = _result(context, {"answer": "done"}, lambda value: value)
    assert result.processed_input_ids == []
