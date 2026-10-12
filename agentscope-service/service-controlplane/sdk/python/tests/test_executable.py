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
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import pytest

from agentscope_service import ExecutableAdapter, TaskResult
from agentscope_service.adapters.base import AgentTaskAssignment
from agentscope_service.bridge import SessionBridge
from agentscope_service.proto import asdp_pb2


def test_start_acknowledgement_precedes_context_runner_and_waiting_completion(monkeypatch):
    from agentscope_service import bridge as bridge_module
    entered, release, started, completed = (threading.Event() for _ in range(4))
    calls = []

    async def run(ctx):
        assert started.is_set()
        calls.append("runner")
        return TaskResult(outcome="waiting", summary="Delegated work")

    adapter = ExecutableAdapter("http://unused", run)

    def start(token, name, arguments):
        assert name == "task.start" and arguments == {}
        calls.append("start")
        entered.set()
        assert release.wait(3)
        started.set()
        return {"task": {"status": "running"}}

    def context(*args):
        assert started.is_set()
        calls.append("context")
        return {}

    adapter._client.call_tool = start
    adapter._client.task_context = context

    class Client:
        def __init__(self, base): pass
        def complete(self, *args, **kwargs):
            assert started.is_set() and kwargs["outcome"] == "waiting"
            calls.append("complete")
            completed.set()

    class Capture:
        def __init__(self): self.reports = []
        def report_execution_attempt(self, report): self.reports.append(report)
        def stop(self): pass

    monkeypatch.setattr(bridge_module, "CollaborationClient", Client)
    bridge = SessionBridge(control_plane="unused", agent_key="leader", enable_events=False,
                           start_http=False, start_grpc=False)
    bridge.attach_target(run, adapter=adapter)
    bridge.start()
    transport = Capture()
    bridge._grpc = transport
    arguments = ("a", "t", "r", "n", 1)
    try:
        bridge._on_execution_attempt(*arguments, "dispatch", "", "token", "", b"{}", 0)
        wait_for(entered.is_set)
        bridge._on_execution_attempt(*arguments, "dispatch", "", "token", "", b"{}", 0)
        assert calls == ["start"] and not completed.is_set()
        assert not any(report.action == "start" for report in transport.reports)
        release.set()
        wait_for(completed.is_set)
        wait_for(lambda: "a" in bridge._attempt_reports)
        bridge._on_execution_attempt(*arguments, "dispatch", "", "token", "", b"{}", 0)
        assert calls == ["start", "context", "runner", "complete"]
    finally:
        release.set()
        bridge.stop()


def test_start_failure_never_enters_runner():
    executed = []

    async def run(ctx):
        executed.append(True)

    adapter = ExecutableAdapter("http://unused", run)
    def reject(*args):
        raise RuntimeError("stale task fence")
    adapter._client.call_tool = reject
    adapter._client.task_context = lambda *args: executed.append("context")
    assignment = AgentTaskAssignment("a", "t", "r", "n", 1, "dispatch", "", "token", "", b"{}", 0)
    with pytest.raises(RuntimeError, match="stale task fence"):
        asyncio.run(adapter.handle_agent_task(assignment))
    assert not executed


def test_cancel_joins_inflight_start_without_entering_runner():
    entered, release, finished = (threading.Event() for _ in range(3))
    executed = []

    async def run(ctx):
        executed.append(True)

    adapter = ExecutableAdapter("http://unused", run)
    def start(*args):
        entered.set()
        assert release.wait(3)
        finished.set()
        return {}
    adapter._client.call_tool = start
    adapter._client.task_context = lambda *args: executed.append("context")
    assignment = AgentTaskAssignment("a", "t", "r", "n", 1, "dispatch", "", "token", "", b"{}", 0)

    async def cancel():
        work = asyncio.create_task(adapter.handle_agent_task(assignment))
        assert await asyncio.to_thread(entered.wait, 2)
        work.cancel()
        await asyncio.sleep(0)
        assert not work.done()
        release.set()
        with pytest.raises(asyncio.CancelledError):
            await work
        assert finished.is_set()

    asyncio.run(cancel())
    assert not executed


def wait_for(predicate):
    deadline = time.monotonic() + 3
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.01)
    raise AssertionError("condition was not reached")


def test_task_adapter_emits_portable_events_and_result():
    observed = []
    async def run(ctx):
        ctx.event("item.delta", item_id="m", content=[{"type": "text", "text": "hello"}])
        return TaskResult(result={"answer": "hello"})
    adapter = ExecutableAdapter("http://unused", run)
    adapter._client.call_tool = lambda *args: {}
    adapter._client.task_context = lambda *args: {"inputs": []}
    adapter.attach(run, observed.append)
    assignment = AgentTaskAssignment("a", "t", "r", "n", 1, "dispatch", "", "", "",
                                     json.dumps({"runtimeBinding": {"sessionId": "s"}}).encode(), 0)
    result = asyncio.run(adapter.handle_agent_task(assignment))
    assert result.result == {"answer": "hello"}
    assert observed[0].session_id == "s"
    assert json.loads(observed[0].framework_meta)["public_event"]["type"] == "item.delta"


@pytest.mark.parametrize("worker_thread", [False, True])
def test_cancel_is_reported_after_runner_cleanup_and_dispatch_does_not_block(worker_thread):
    entered, cleaning, release = threading.Event(), threading.Event(), threading.Event()
    async def run(ctx):
        entered.set()
        try:
            await asyncio.sleep(100)
        finally:
            cleaning.set()
            while not release.is_set():
                await asyncio.sleep(0.01)
    adapter = ExecutableAdapter("http://unused", run)
    adapter._client.call_tool = lambda *args: {}
    adapter._client.task_context = lambda *args: {"inputs": []}
    bridge = SessionBridge(control_plane="unused", agent_key="test", enable_events=False,
                           start_http=False, start_grpc=False)
    bridge.attach_target(run, adapter=adapter)
    bridge.start()
    class Capture:
        def __init__(self): self.reports = []
        def report_execution_attempt(self, report): self.reports.append(report)
        def stop(self): pass
    transport = Capture()
    bridge._grpc = transport
    arguments = ("a", "t", "r", "n", 1)

    def command(action):
        args = (*arguments, action, "", "", "", b"{}", 0)
        if worker_thread:
            with ThreadPoolExecutor(max_workers=1) as executor:
                executor.submit(bridge._on_execution_attempt, *args).result(timeout=3)
        else:
            bridge._on_execution_attempt(*args)

    try:
        command("dispatch")
        wait_for(entered.is_set)
        command("cancel")
        wait_for(cleaning.is_set)
        assert not any(r.action == "cancelled" for r in transport.reports)
        release.set()
        wait_for(lambda: any(r.action == "cancelled" for r in transport.reports))
        command("dispatch")
        assert transport.reports[-1].action == "cancelled"
    finally:
        release.set()
        bridge.stop()


def test_completion_waits_for_every_event_batch_ack(tmp_path):
    emitted = threading.Event()

    async def run(ctx):
        for i in range(41):
            ctx.event("item.delta", item_id="message", content=[{"type": "text", "text": str(i)}])
        emitted.set()
        return TaskResult(result="done")

    adapter = ExecutableAdapter("http://unused", run)
    adapter._client.call_tool = lambda *args: {}
    adapter._client.task_context = lambda *args: {"inputs": []}
    bridge = SessionBridge(control_plane="unused", agent_key="ack-test",
                           event_journal_dir=str(tmp_path), start_http=False, start_grpc=False)
    bridge.attach_target(run, adapter=adapter)
    bridge.start()

    class Capture:
        def __init__(self):
            self.reports = []
            self.batches = []

        def report_execution_attempt(self, report): self.reports.append(report)
        def report_events(self, report_id, events):
            self.batches.append((report_id, events))
            return True
        def stop(self): pass

    transport = Capture()
    bridge._grpc = transport
    try:
        payload = json.dumps({"runtimeBinding": {"sessionId": "session"}}).encode()
        bridge._on_execution_attempt("a", "t", "r", "n", 1, "dispatch", "", "", "", payload, 0)
        wait_for(emitted.is_set)
        for index, committed in enumerate((20, 40, 41)):
            wait_for(lambda: len(transport.batches) > index)
            assert not any(r.action == "complete" for r in transport.reports)
            report_id, batch = transport.batches[index]
            assert batch[-1].seq == committed
            bridge._on_event_ack(asdp_pb2.EventReportAck(
                report_id=report_id,
                committed=[asdp_pb2.SessionEventCursor(session_id="session", committed_seq=committed)],
            ))
        wait_for(lambda: any(r.action == "complete" for r in transport.reports))
        assert len(bridge._event_journal) == 0
    finally:
        bridge.stop()


def test_cancel_before_dispatch_caches_terminal_report():
    executed = []
    async def run(ctx):
        executed.append(True)
    adapter = ExecutableAdapter("http://unused", run)
    bridge = SessionBridge(control_plane="unused", agent_key="test", enable_events=False,
                           start_http=False, start_grpc=False)
    bridge.attach_target(run, adapter=adapter)
    class Capture:
        def __init__(self): self.reports = []
        def report_execution_attempt(self, report): self.reports.append(report)
    transport = Capture()
    bridge._grpc = transport
    arguments = ("cancelled-attempt", "task", "run", "node", 1)
    bridge._on_execution_attempt(*arguments, "cancel", "", "", "", b"{}", 0)
    bridge._on_execution_attempt(*arguments, "dispatch", "", "", "", b"{}", 0)
    assert [report.action for report in transport.reports] == ["cancelled", "cancelled"]
    assert not executed and not bridge._attempt_futures


def test_coordinator_completion_waits_for_event_ack(tmp_path, monkeypatch):
    from agentscope_service import bridge as bridge_module
    completed = []
    async def run(ctx):
        ctx.event("item.completed", item_id="final", item={"content": [{"type": "text", "text": "result"}]})
        return TaskResult(result={"answer": "result"}, complete_run=True)
    adapter = ExecutableAdapter("http://unused", run)
    adapter._client.call_tool = lambda *args: {}
    adapter._client.task_context = lambda *args: {}
    class Client:
        def __init__(self, base): pass
        def complete_run_node(self, *args): completed.append(args)
    monkeypatch.setattr(bridge_module, "CollaborationClient", Client)
    bridge = SessionBridge(control_plane="unused", control_plane_http="http://unused", agent_key="coordinator",
                           event_journal_dir=str(tmp_path), start_http=False, start_grpc=False)
    bridge.attach_target(run, adapter=adapter)
    bridge.start()
    class Capture:
        def __init__(self): self.reports, self.batches = [], []
        def report_execution_attempt(self, report): self.reports.append(report)
        def report_events(self, report_id, events): self.batches.append((report_id, events)); return True
        def stop(self): pass
    transport = Capture()
    bridge._grpc = transport
    try:
        payload = json.dumps({"runtimeBinding": {"sessionId": "session"}}).encode()
        bridge._on_execution_attempt("a", "t", "r", "n", 1, "dispatch", "", "", "", payload, 0)
        wait_for(lambda: bool(transport.batches))
        assert not completed
        bridge._on_event_ack(asdp_pb2.EventReportAck(report_id=transport.batches[0][0],
            committed=[asdp_pb2.SessionEventCursor(session_id="session", committed_seq=1)]))
        wait_for(lambda: bool(completed))
        wait_for(lambda: "a" in bridge._attempt_reports)
        assert not any(report.action == "complete" for report in transport.reports)
    finally:
        bridge.stop()
