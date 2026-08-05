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

"""数据模型单元测试：events / context / inventory（sdk-design §3）。"""
from __future__ import annotations

import json

from aistio.context import ContextMessage, ContextSnapshot, ContextTracker, ToolInfo
from aistio.events import (
    EVENT_COMPACTION,
    EVENT_MESSAGE,
    EVENT_TOOL_CALL,
    EVENT_TOOL_RESULT,
    MAX_SUMMARY_LEN,
    MAX_TOOL_INPUT_BYTES,
    MessageItem,
    MessagePage,
    SessionEvent,
)
from aistio.inventory import InstanceHealth, Inventory, SubagentInfo, WorkspaceInfo


# ─── SessionEvent ───


def test_event_defaults_fill_occurred_at():
    ev = SessionEvent(session_id="s", seq=1, event_type=EVENT_MESSAGE)
    assert ev.occurred_at > 0


def test_event_truncates_summary_fields():
    ev = SessionEvent(
        session_id="s",
        seq=1,
        event_type=EVENT_MESSAGE,
        content="x" * (MAX_SUMMARY_LEN + 100),
        tool_output="y" * (MAX_SUMMARY_LEN + 100),
    )
    assert len(ev.content) == MAX_SUMMARY_LEN
    assert len(ev.tool_output) == MAX_SUMMARY_LEN


def test_event_encode_tool_input_limits_bytes():
    data = SessionEvent.encode_tool_input({"k": "v" * MAX_TOOL_INPUT_BYTES})
    assert len(data) == MAX_TOOL_INPUT_BYTES
    assert SessionEvent.encode_tool_input(None) == b""
    assert SessionEvent.encode_tool_input('{"a":1}') == b'{"a":1}'


def test_event_to_proto_roundtrip():
    ev = SessionEvent(
        session_id="s1",
        seq=7,
        event_type=EVENT_TOOL_CALL,
        role="assistant",
        content="call bash",
        tool_name="bash",
        tool_input=SessionEvent.encode_tool_input({"cmd": "ls"}),
        tokens_in=10,
        tokens_out=5,
        duration_ms=42,
        framework_meta=SessionEvent.encode_meta({"trace": "t-1"}),
    )
    p = ev.to_proto()
    assert p.session_id == "s1" and p.seq == 7 and p.event_type == "tool_call"
    assert json.loads(p.tool_input.decode()) == {"cmd": "ls"}
    assert json.loads(p.framework_meta.decode()) == {"trace": "t-1"}
    assert p.tokens_in == 10 and p.tokens_out == 5 and p.duration_ms == 42


# ─── MessagePage（Level 3 合约形状）───


def test_message_page_json_shape():
    page = MessagePage(
        session_id="s1",
        messages=[
            MessageItem(seq=1, role="user", content="hello", occurred_at=1_700_000_000_000),
            MessageItem(seq=2, role="assistant", content="hi", tool_name="bash", tool_output="ok"),
        ],
        offset=0,
        limit=50,
    )
    d = page.to_json_dict()
    assert d["sessionId"] == "s1" and d["total"] == 2 and d["limit"] == 50
    assert d["messages"][0]["occurredAt"].endswith("Z")
    assert d["messages"][1]["toolName"] == "bash"
    assert "toolInput" not in d["messages"][0]


# ─── ContextSnapshot ───


def _sample_snapshot() -> ContextSnapshot:
    return ContextSnapshot(
        session_id="s1",
        system_prompt="you are helpful",
        messages=[ContextMessage(role="user", content="q")],
        tools=[ToolInfo(name="bash", description="run shell", parameters={"type": "object"})],
        framework="claude-agent-sdk",
    )


def test_context_hash_is_stable_and_content_addressed():
    a = _sample_snapshot().compute_hash()
    b = _sample_snapshot().compute_hash()
    assert a == b and len(a) == 16
    changed = _sample_snapshot()
    changed.messages.append(ContextMessage(role="assistant", content="a"))
    assert changed.compute_hash() != a


def test_context_snapshot_to_proto_and_json():
    snap = _sample_snapshot()
    snap.refresh_hash()
    p = snap.to_proto()
    assert p.session_id == "s1" and p.context_hash == snap.context_hash
    assert json.loads(p.messages.decode())[0]["role"] == "user"
    assert json.loads(p.tools.decode())[0]["name"] == "bash"

    d = snap.to_json_dict()
    assert d["sessionId"] == "s1" and d["contextHash"] == snap.context_hash
    assert d["systemPrompt"] == "you are helpful"
    assert d["tools"][0]["parameters"] == {"type": "object"}
    assert "isCompacted" not in d  # 空值省略


def test_context_snapshot_compaction_fields():
    snap = ContextSnapshot(
        session_id="s1",
        is_compacted=True,
        compaction_summary="summary",
        original_message_count=42,
        compacted_at=1_700_000_000_000,
    )
    d = snap.to_json_dict()
    assert d["isCompacted"] is True
    assert d["compactionSummary"] == "summary"
    assert d["originalMessageCount"] == 42
    assert d["compactedAt"].endswith("Z")


# ─── ContextTracker ───


def test_tracker_accumulates_and_reports_hash_change():
    tracker = ContextTracker("s1", framework="test", system_prompt="sp")
    base = tracker.context_hash
    changed = tracker.on_event(
        SessionEvent(session_id="s1", seq=1, event_type=EVENT_MESSAGE, role="user", content="hi")
    )
    assert changed and tracker.context_hash != base
    assert tracker.effective_message_count == 1 and tracker.message_count == 1


def test_tracker_tool_events_extend_view_without_message_count():
    tracker = ContextTracker("s1")
    tracker.on_event(
        SessionEvent(
            session_id="s1", seq=1, event_type=EVENT_TOOL_CALL, tool_name="bash", content="ls"
        )
    )
    tracker.on_event(
        SessionEvent(session_id="s1", seq=2, event_type=EVENT_TOOL_RESULT, tool_output="files")
    )
    assert tracker.effective_message_count == 2
    assert tracker.message_count == 0  # 工具事件不计入全量消息数
    roles = [m.role for m in tracker.messages]
    assert roles == ["assistant", "tool"]


def test_tracker_compaction_resets_view():
    tracker = ContextTracker("s1")
    for i in range(3):
        tracker.on_event(
            SessionEvent(
                session_id="s1", seq=i + 1, event_type=EVENT_MESSAGE, role="user", content=f"m{i}"
            )
        )
    assert tracker.message_count == 3
    tracker.on_event(
        SessionEvent(session_id="s1", seq=4, event_type=EVENT_COMPACTION, content="sum of 3")
    )
    assert tracker.is_compacted
    assert tracker.effective_message_count == 1
    snap = tracker.snapshot()
    assert snap.original_message_count == 3
    assert snap.compaction_summary == "sum of 3"
    assert snap.messages[0].is_compaction
    assert snap.context_hash == tracker.context_hash


def test_tracker_ignores_other_sessions_and_unknown_types():
    tracker = ContextTracker("s1")
    assert tracker.on_event(
        SessionEvent(session_id="other", seq=1, event_type=EVENT_MESSAGE, content="x")
    ) is False
    assert tracker.on_event(
        SessionEvent(session_id="s1", seq=1, event_type="session_start")
    ) is False
    assert tracker.effective_message_count == 0


def test_tracker_token_aggregation():
    tracker = ContextTracker("s1")
    tracker.on_event(
        SessionEvent(
            session_id="s1",
            seq=1,
            event_type=EVENT_MESSAGE,
            content="a",
            tokens_in=10,
            tokens_out=4,
        )
    )
    tracker.on_event(
        SessionEvent(
            session_id="s1",
            seq=2,
            event_type=EVENT_MESSAGE,
            content="b",
            tokens_in=6,
            tokens_out=2,
        )
    )
    snap = tracker.snapshot()
    assert snap.total_tokens == 22


# ─── Inventory ───


def test_inventory_to_proto_and_json():
    inv = Inventory(
        subagents=[
            SubagentInfo(
                name="helper",
                description="does things",
                tools=["bash", "edit"],
                workspace_mode="isolated",
                invoke_count=3,
                last_invoked_at=1_700_000_000_000,
            )
        ],
        workspaces=[WorkspaceInfo(path="/ws/a", mode="shared", size_bytes=128, owner_ref="s1")],
        health=InstanceHealth(healthy=True, active_sessions=2),
    )
    p = inv.to_proto()
    assert p.subagents[0].name == "helper" and p.subagents[0].tools == ["bash", "edit"]
    assert p.workspaces[0].path == "/ws/a" and p.health.active_sessions == 2

    sd = inv.subagents[0].to_json_dict()
    assert sd["workspaceMode"] == "isolated" and sd["invokeCount"] == 3
    assert sd["lastInvokedAt"].endswith("Z")
    wd = inv.workspaces[0].to_json_dict()
    assert wd == {"path": "/ws/a", "mode": "shared", "sizeBytes": 128, "ownerRef": "s1"}
