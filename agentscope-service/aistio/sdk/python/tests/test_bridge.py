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

"""SessionBridge + GrpcTransport + ContractHTTPServer 端到端测试。

起真实 gRPC fake 控制面与真实 HTTP 合约服务，验证四级上报、命令分发、
降级语义（sdk-design §5.3）。
"""
from __future__ import annotations

import asyncio
import json
import threading
import time
import urllib.error
import urllib.request
from concurrent import futures

import grpc
import pytest

import aistio
import aistio.bridge as bridge_mod
from aistio.bridge import SessionBridge
from aistio.events import EVENT_COMPACTION, EVENT_MESSAGE, SessionEvent
from aistio.proto import asdp_pb2, asdp_pb2_grpc
from aistio.transport.grpc import GrpcTransport


# ─── fake 控制面 ───


class FakeASDPServicer(asdp_pb2_grpc.AgentDataPlaneServiceServicer):
    def __init__(self):
        self._lock = threading.Lock()
        self.connects = []
        self.sessions = []
        self.events = []
        self.contexts = []
        self.inventories = []
        self.config_acks = []
        self.pending_commands = []
        self.pending_config_pushes = []

    def Connect(self, request_iterator, context):
        for up in request_iterator:
            kind = up.WhichOneof("payload")
            with self._lock:
                if kind == "connect":
                    self.connects.append(up.connect)
                elif kind == "session_report":
                    self.sessions.extend(up.session_report.sessions)
                elif kind == "event_report":
                    self.events.extend(up.event_report.events)
                elif kind == "context_report":
                    self.contexts.append(up.context_report)
                elif kind == "inventory":
                    self.inventories.append(up.inventory)
                elif kind == "config_ack":
                    self.config_acks.append(up.config_ack)
            if kind == "connect":
                yield asdp_pb2.Downstream(
                    connect_ack=asdp_pb2.ConnectResponse(
                        accepted=True, control_plane_version="test-cp"
                    )
                )
            with self._lock:
                commands = list(self.pending_commands)
                del self.pending_commands[:]
                pushes = list(self.pending_config_pushes)
                del self.pending_config_pushes[:]
            for session_id, command in commands:
                yield asdp_pb2.Downstream(
                    session_cmd=asdp_pb2.SessionCommand(session_id=session_id, command=command)
                )
            for push in pushes:
                yield asdp_pb2.Downstream(config_push=push)


@pytest.fixture
def fake_cp():
    servicer = FakeASDPServicer()
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=4))
    asdp_pb2_grpc.add_AgentDataPlaneServiceServicer_to_server(servicer, server)
    port = server.add_insecure_port("127.0.0.1:0")
    server.start()
    yield servicer, f"127.0.0.1:{port}"
    server.stop(0)


# ─── fake Claude 框架 ───


class FakeClaudeStore:
    def __init__(self):
        self.data = {}

    async def append(self, key, entries):
        self.data.setdefault(key["session_id"], []).extend(entries)

    async def load(self, key):
        return self.data.get(key["session_id"])


class FakeClaudeOptions:
    def __init__(self, store):
        self.session_store = store


class ClaudeSDKClient:
    def __init__(self, options):
        self.options = options
        self.commands = []

    async def compress_session(self, session_id):
        self.commands.append(("compress", session_id))

    async def terminate_session(self, session_id):
        self.commands.append(("terminate", session_id))


@pytest.fixture
def claude():
    store = FakeClaudeStore()
    return ClaudeSDKClient(FakeClaudeOptions(store))


@pytest.fixture
def fast_periods(monkeypatch):
    monkeypatch.setattr(bridge_mod, "LEVEL1_INTERVAL", 0.15)
    monkeypatch.setattr(bridge_mod, "EVENT_FLUSH_INTERVAL", 0.1)
    monkeypatch.setattr(bridge_mod, "INVENTORY_INTERVAL", 0.2)
    monkeypatch.setattr(bridge_mod, "CONTEXT_PUSH_COOLDOWN", 0.1)


def _append(client, session_id, entries):
    asyncio.run(client.options.session_store.append({"session_id": session_id}, entries))


def _wait_for(predicate, timeout=5.0, interval=0.05):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if predicate():
            return True
        time.sleep(interval)
    return False


def _make_bridge(fake_cp, claude, **kwargs):
    addr, _ = fake_cp[1], None
    kwargs.setdefault("control_plane", fake_cp[1])
    kwargs.setdefault("agent_name", "test-agent")
    kwargs.setdefault("instance_id", "inst-test")
    kwargs.setdefault("contract_http_port", 0)
    return aistio.instrument(claude, **kwargs)


def _http(port, path, method="GET"):
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", method=method)
    with urllib.request.urlopen(req, timeout=5) as resp:
        return json.loads(resp.read())


# ─── 端到端 ───


def test_handshake_reports_capabilities(fake_cp, claude, fast_periods):
    servicer, _ = fake_cp
    bridge = _make_bridge(fake_cp, claude, enable_events=True)
    try:
        assert bridge.grpc_transport.wait_connected(5)
        assert _wait_for(lambda: len(servicer.connects) > 0)
        caps = set(servicer.connects[0].capabilities)
        assert {
            "session-reporting",
            "event-reporting",
            "context-reporting",
            "context-query",
            "message-query",
            "session-command",
        } <= caps
        assert servicer.connects[0].runtime.startswith("python-")
    finally:
        bridge.stop()


def test_level1_snapshot_carries_framework_and_context_fields(
    fake_cp, claude, fast_periods
):
    servicer, _ = fake_cp
    bridge = _make_bridge(fake_cp, claude)
    try:
        _append(claude, "s1", [{"type": "user", "content": "hello"}])
        _append(claude, "s1", [{"type": "summary", "summary": "compressed"}])
        assert _wait_for(
            lambda: any(s.session_id == "s1" and s.is_compacted for s in servicer.sessions)
        )
        snap = [s for s in servicer.sessions if s.session_id == "s1"][-1]
        assert snap.framework == "claude-agent-sdk"
        assert snap.context_hash
        assert snap.effective_message_count == 1
        assert snap.message_count == 1
    finally:
        bridge.stop()


def test_level2_events_have_monotonic_seq_per_session(fake_cp, claude, fast_periods):
    servicer, _ = fake_cp
    bridge = _make_bridge(fake_cp, claude, enable_events=True)
    try:
        _append(claude, "s1", [{"type": "user", "content": "a"}, {"type": "assistant", "content": "b"}])
        _append(claude, "s2", [{"type": "user", "content": "x"}])
        assert _wait_for(lambda: len(servicer.events) >= 3)
        s1 = [e for e in servicer.events if e.session_id == "s1"]
        s2 = [e for e in servicer.events if e.session_id == "s2"]
        assert [e.seq for e in s1] == [1, 2]
        assert [e.seq for e in s2] == [1]
    finally:
        bridge.stop()


def test_level2_disabled_by_default(fake_cp, claude, fast_periods):
    servicer, _ = fake_cp
    bridge = _make_bridge(fake_cp, claude)  # enable_events=False
    try:
        _append(claude, "s1", [{"type": "user", "content": "a"}])
        assert _wait_for(lambda: len(servicer.sessions) > 0)
        time.sleep(0.4)
        assert servicer.events == []
    finally:
        bridge.stop()


def test_level4_compaction_pushes_immediately(fake_cp, claude, fast_periods):
    servicer, _ = fake_cp
    bridge = _make_bridge(fake_cp, claude)
    try:
        _append(claude, "s1", [{"type": "user", "content": "a"}])
        _append(claude, "s1", [{"type": "summary", "summary": "S"}])
        assert _wait_for(
            lambda: any(c.session_id == "s1" and c.is_compacted for c in servicer.contexts)
        )
        report = [c for c in servicer.contexts if c.session_id == "s1" and c.is_compacted][-1]
        assert report.is_compacted and report.compaction_summary == "S"
        messages = json.loads(report.messages.decode())
        assert messages[0]["isCompaction"] is True
        assert report.framework == "claude-agent-sdk"
    finally:
        bridge.stop()


def test_level4_hash_change_debounced(fake_cp, claude, monkeypatch):
    monkeypatch.setattr(bridge_mod, "LEVEL1_INTERVAL", 10.0)
    monkeypatch.setattr(bridge_mod, "EVENT_FLUSH_INTERVAL", 10.0)
    monkeypatch.setattr(bridge_mod, "INVENTORY_INTERVAL", 10.0)
    monkeypatch.setattr(bridge_mod, "CONTEXT_PUSH_COOLDOWN", 60.0)  # 长冷却
    servicer, _ = fake_cp
    bridge = _make_bridge(fake_cp, claude)
    try:
        _append(claude, "s1", [{"type": "user", "content": "m1"}])
        assert _wait_for(lambda: len(servicer.contexts) >= 1)
        _append(claude, "s1", [{"type": "assistant", "content": "m2"}])
        _append(claude, "s1", [{"type": "assistant", "content": "m3"}])
        time.sleep(0.4)
        # 冷却期内 hash 多次变化只推了一次
        assert len(servicer.contexts) == 1
    finally:
        bridge.stop()


def test_inventory_reported_on_connect(fake_cp, claude, fast_periods):
    servicer, _ = fake_cp
    bridge = _make_bridge(fake_cp, claude)
    try:
        assert _wait_for(lambda: len(servicer.inventories) > 0)
        health = servicer.inventories[0].health
        assert health.healthy
    finally:
        bridge.stop()


def test_asdp_session_command_dispatches_to_adapter(fake_cp, claude, fast_periods):
    servicer, _ = fake_cp
    bridge = _make_bridge(fake_cp, claude)
    try:
        assert bridge.grpc_transport.wait_connected(5)
        _append(claude, "s1", [{"type": "user", "content": "a"}])
        servicer.pending_commands.append(("s1", "compress"))
        bridge.grpc_transport.report_sessions([])  # 触发一次收发交换
        assert _wait_for(lambda: ("compress", "s1") in claude.commands)
    finally:
        bridge.stop()


def test_config_push_ack(fake_cp, claude, fast_periods):
    servicer, _ = fake_cp
    bridge = _make_bridge(fake_cp, claude)
    try:
        assert bridge.grpc_transport.wait_connected(5)
        servicer.pending_config_pushes.append(
            asdp_pb2.ConfigPush(config_type=1, version="v1", resources=b"{}", nonce="n-1")
        )
        bridge.grpc_transport.report_sessions([])
        assert _wait_for(lambda: len(servicer.config_acks) > 0)
        ack = servicer.config_acks[0]
        assert ack.nonce == "n-1" and ack.accepted
    finally:
        bridge.stop()


# ─── HTTP 合约 ───


def test_http_contract_endpoints(fake_cp, claude, fast_periods):
    bridge = _make_bridge(fake_cp, claude)
    try:
        _append(claude, "s1", [{"type": "user", "content": "hello"}, {"type": "assistant", "content": "hi"}])
        _append(claude, "s1", [{"type": "summary", "summary": "S"}])
        port = bridge.http_port

        info = _http(port, "/agentscope/info")
        assert info["name"] == "test-agent" and info["runtime"] == "claude-agent-sdk"
        assert info["contractLevel"] == 3
        assert "message-query" in info["capabilities"]

        health = _http(port, "/agentscope/health")
        assert health == {"status": "ok"}

        sessions = _http(port, "/agentscope/sessions")["sessions"]
        assert sessions[0]["id"] == "s1" and sessions[0]["framework"] == "claude-agent-sdk"
        assert sessions[0]["isCompacted"] is True

        state = _http(port, "/agentscope/sessions/s1/state")
        assert state["sessionId"] == "s1" and "contextPressure" in state

        ctx = _http(port, "/agentscope/sessions/s1/context")
        assert ctx["isCompacted"] is True and ctx["compactionSummary"] == "S"
        assert ctx["contextHash"]

        page = _http(port, "/agentscope/sessions/s1/messages?offset=1&limit=1")
        assert page["total"] == 3 and len(page["messages"]) == 1
    finally:
        bridge.stop()


def test_http_contract_404_and_501(fake_cp, claude, fast_periods):
    bridge = _make_bridge(fake_cp, claude)
    try:
        port = bridge.http_port
        with pytest.raises(urllib.error.HTTPError) as exc:
            _http(port, "/agentscope/sessions/nope/state")
        assert exc.value.code == 404

        # Claude 适配器不支持 inventory → 501
        with pytest.raises(urllib.error.HTTPError) as exc:
            _http(port, "/agentscope/subagents")
        assert exc.value.code == 501

        with pytest.raises(urllib.error.HTTPError) as exc:
            _http(port, "/agentscope/unknown")
        assert exc.value.code == 404
    finally:
        bridge.stop()


def test_http_compress_and_terminate_commands(fake_cp, claude, fast_periods):
    bridge = _make_bridge(fake_cp, claude)
    try:
        port = bridge.http_port
        resp = _http(port, "/agentscope/sessions/s1/compress", method="POST")
        assert resp == {"sessionId": "s1", "command": "compress", "status": "initiated"}
        resp = _http(port, "/agentscope/sessions/s1/terminate", method="POST")
        assert resp["command"] == "terminate"
        assert claude.commands == [("compress", "s1"), ("terminate", "s1")]
    finally:
        bridge.stop()


def test_http_abort_and_tasks_501_without_adapter_support(fake_cp, claude, fast_periods):
    bridge = _make_bridge(fake_cp, claude)
    try:
        port = bridge.http_port
        with pytest.raises(urllib.error.HTTPError) as exc:
            _http(port, "/agentscope/sessions/s1/abort", method="POST")
        assert exc.value.code == 501

        with pytest.raises(urllib.error.HTTPError) as exc:
            _http(port, "/agentscope/sessions/s1/tasks")
        assert exc.value.code == 501
    finally:
        bridge.stop()


def test_http_session_state_includes_frozen_fields(fake_cp, claude, fast_periods):
    bridge = _make_bridge(fake_cp, claude)
    try:
        _append(claude, "s1", [{"type": "user", "content": "hello"}])
        # Seed context window without fabricating busy/model (claude has no overlay).
        with bridge._lock:
            bridge._trackers["s1"].max_tokens = 32000
        state = _http(bridge.http_port, "/agentscope/sessions/s1/state")
        assert state["id"] == "s1" and state["phase"] == "running"
        assert state["tokenUsage"]["maxTokens"] == 32000
        assert "busy" not in state  # unknown → omit
        sessions = _http(bridge.http_port, "/agentscope/sessions")["sessions"]
        assert sessions[0]["tokenUsage"]["maxTokens"] == 32000
    finally:
        bridge.stop()


# ─── 降级语义 ───


def test_event_buffer_bounded_drops_oldest(fake_cp, claude, fast_periods, monkeypatch):
    # 关闭满批 flush，让 buffer 涨过上限以验证丢最旧。
    monkeypatch.setattr(bridge_mod, "EVENT_BATCH_SIZE", bridge_mod.EVENT_BUFFER_MAX * 10)
    bridge = _make_bridge(fake_cp, claude, enable_events=True)
    try:
        total = bridge_mod.EVENT_BUFFER_MAX + 100
        for i in range(total):
            bridge.on_event(
                SessionEvent(session_id="s-bulk", seq=0, event_type=EVENT_MESSAGE, content=f"m{i}")
            )
        with bridge._lock:
            assert len(bridge._event_buffer) == bridge_mod.EVENT_BUFFER_MAX
            # 最旧的 100 条已被丢弃，buffer 里是最后 EVENT_BUFFER_MAX 条
            assert bridge._event_buffer[0].content == "m100"
            assert bridge._event_buffer[-1].content == f"m{total - 1}"
    finally:
        bridge.stop()


def test_grpc_send_queue_full_counts_dropped():
    transport = GrpcTransport("127.0.0.1:1", agent_name="x")  # 未启动，队列只会堆积
    for _ in range(GrpcTransport("127.0.0.1:1", agent_name="x")._send_q.maxsize):
        assert transport.report_sessions([]) is True
    assert transport.report_sessions([]) is False
    assert transport.dropped == 1


def test_bypass_failure_never_raises(fake_cp, claude, fast_periods):
    """旁路原则：控制面不可达时 instrument 与事件路径不抛异常。"""
    bridge = aistio.instrument(
        claude,
        control_plane="127.0.0.1:1",  # 无监听
        agent_name="test-agent",
        instance_id="inst-test",
        contract_http_port=0,
    )
    try:
        _append(claude, "s1", [{"type": "user", "content": "a"}])
        time.sleep(0.3)  # 调度循环跑过若干轮，不应抛异常
        assert bridge.grpc_transport is not None and not bridge.grpc_transport.connected
    finally:
        bridge.stop()
